package org.carlspring.strongbox.artifact;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.locks.Lock;

import javax.inject.Inject;

import org.carlspring.strongbox.data.CacheName;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.event.AsyncEventListener;
import org.carlspring.strongbox.event.artifact.ArtifactEvent;
import org.carlspring.strongbox.event.artifact.ArtifactEventTypeEnum;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathLock;
import org.carlspring.strongbox.services.ArtifactEntryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.transaction.ChainedTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orientechnologies.common.concur.ONeedRetryException;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;

public abstract class AsyncArtifactEntryHandler
{

    private static final int MAX_RETRY = 10;

    private static final Logger logger = LoggerFactory.getLogger(AsyncArtifactEntryHandler.class);

    @Inject
    private ArtifactEntryService artifactEntryService;

    @Inject
    private RepositoryPathLock repositoryPathLock;

    @Inject
    private PlatformTransactionManager transactionManager;

    @Inject
    private CacheManager cacheManager;

    private final ArtifactEventTypeEnum eventType;

    public AsyncArtifactEntryHandler(ArtifactEventTypeEnum eventType)
    {
        super();
        this.eventType = eventType;
    }

    @AsyncEventListener
    public void handleEvent(final ArtifactEvent<RepositoryPath> event)
        throws IOException,
        InterruptedException
    {
        if (eventType.getType() != event.getType())
        {
            return;
        }

        RepositoryPath repositoryPath = (RepositoryPath) event.getPath();
        if (!RepositoryFiles.isArtifact(repositoryPath))
        {
            return;
        }

        // TODO: this is needed just as workadound to have new transaction
        // within this async event (expected to be replaced with
        // just Propagation.REQUIRES_NEW after SB-1200)
        Thread threadWithNewTransactionContext = new Thread(() -> {
            try
            {
                handleLocked(repositoryPath);
            }
            catch (Exception e)
            {
                logger.error(String.format("Failed to handle async event [%s]",
                                           AsyncArtifactEntryHandler.this.getClass().getSimpleName()),
                             e);
            }
        });

        threadWithNewTransactionContext.start();
        threadWithNewTransactionContext.join();
    }

    private void handleLocked(RepositoryPath repositoryPath)
        throws IOException,
        InterruptedException
    {
        Lock lock = repositoryPathLock.lock(repositoryPath,
                                            ArtifactEntry.class.getSimpleName())
                                      .writeLock();
        lock.lock();
        try
        {
            handleWithRetry(repositoryPath);
        } finally
        {
            lock.unlock();
        }
    }

    /**
     * This retry needed if {@link ArtifactEntry} fetched between DB and
     * Hazelcast transactions commits.
     *
     * @see ChainedTransactionManager
     * 
     * @param repositoryPath
     * @throws InterruptedException
     * @throws IOException
     */
    private void handleWithRetry(RepositoryPath repositoryPath)
        throws InterruptedException,
        IOException
    {
        Object sync = new Object();

        for (int i = 1; i <= MAX_RETRY; i++)
        {
            try
            {

                handleTransactional(repositoryPath);

                return;
            }
            catch (ONeedRetryException e)
            {
                logger.debug(String.format("Retry event [%s] for path [%s]", this.getClass().getSimpleName(),
                                           repositoryPath));
                propogateIfNeeded(i, repositoryPath, e);
            }

            synchronized (sync)
            {
                sync.wait(10);
            }
        }
    }

    private void propogateIfNeeded(int i,
                                   RepositoryPath repositoryPath,
                                   ONeedRetryException e)
        throws IOException
    {
        if (i >= MAX_RETRY)
        {
            throw e;
        }

        ArtifactEntry artifactEntry = repositoryPath.getArtifactEntry();
        cacheManager.getCache(CacheName.Artifact.ARTIFACT_ENTRIES)
                    .evict(artifactEntry.getStorageId() + "/" + artifactEntry.getRepositoryId() + "/"
                            + artifactEntry.getArtifactPath());

    }

    private void handleTransactional(RepositoryPath repositoryPath)
    {
        new TransactionTemplate(transactionManager).execute(t -> {
            try
            {
                return artifactEntryService.save(handleEvent(repositoryPath));
            }
            catch (IOException e)
            {
                throw new UndeclaredThrowableException(e);
            }
        });
    }

    protected abstract ArtifactEntry handleEvent(RepositoryPath repositoryPath)
        throws IOException;

}
