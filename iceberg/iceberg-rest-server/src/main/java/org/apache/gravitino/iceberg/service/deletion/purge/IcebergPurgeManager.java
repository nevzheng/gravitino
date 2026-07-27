/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.iceberg.service.deletion.purge;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionContextStore;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns bounded deletion-action collection and restart-safe Iceberg purge worker loops. */
public class IcebergPurgeManager implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergPurgeManager.class);
  private static final int MAX_TARGET_BATCHES_PER_RUN = 8;

  private final IcebergPurgeJobStore store;
  private final IcebergPurgeResources resources;
  private final IcebergPurgeWorker worker;
  private final int workerThreads;
  private final int collectionBatchSize;
  private final long pollIntervalMs;
  private final long receiptRetentionMs;
  private final String processId = UUID.randomUUID().toString();
  private final AtomicLong manualRun = new AtomicLong();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  private ScheduledExecutorService scheduler;
  private ExecutorService workers;

  /**
   * Creates the production purge runtime using existing bounded async-cleanup controls.
   *
   * @param store durable purge job store
   * @param contextStore immutable Iceberg deletion context store
   * @param wrapperManager current catalog configuration and credentials
   * @param config Iceberg REST configuration
   */
  public IcebergPurgeManager(
      IcebergPurgeJobStore store,
      IcebergDeletionContextStore contextStore,
      IcebergCatalogWrapperManager wrapperManager,
      IcebergConfig config) {
    this(store, contextStore, wrapperManager, config, null, UnaryOperator.identity());
  }

  /**
   * Creates the production purge runtime and records only committed per-action outcomes.
   *
   * @param store durable purge job store
   * @param contextStore immutable Iceberg deletion context store
   * @param wrapperManager current catalog configuration and credentials
   * @param config Iceberg REST configuration
   * @param metricsSource registered deletion lifecycle metrics
   */
  public IcebergPurgeManager(
      IcebergPurgeJobStore store,
      IcebergDeletionContextStore contextStore,
      IcebergCatalogWrapperManager wrapperManager,
      IcebergConfig config,
      @Nullable IcebergDeletionMetricsSource metricsSource) {
    this(store, contextStore, wrapperManager, config, metricsSource, UnaryOperator.identity());
  }

  /**
   * Creates the purge runtime with metrics and an optional target-deleter decorator.
   *
   * <p>The production-disabled integration-test hook uses the decorator to inject a classified
   * failure around the real FileIO delete. Production construction uses the identity decorator.
   *
   * @param store durable purge job store
   * @param contextStore immutable Iceberg deletion context store
   * @param wrapperManager current catalog configuration and credentials
   * @param config Iceberg REST configuration
   * @param metricsSource registered deletion lifecycle metrics
   * @param targetDeleterDecorator decorator around the real target deleter
   */
  public IcebergPurgeManager(
      IcebergPurgeJobStore store,
      IcebergDeletionContextStore contextStore,
      IcebergCatalogWrapperManager wrapperManager,
      IcebergConfig config,
      @Nullable IcebergDeletionMetricsSource metricsSource,
      UnaryOperator<IcebergPurgeTargetDeleter> targetDeleterDecorator) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(contextStore, "contextStore must not be null");
    Objects.requireNonNull(wrapperManager, "wrapperManager must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(targetDeleterDecorator, "targetDeleterDecorator must not be null");
    this.workerThreads = config.get(IcebergConfig.ASYNC_CLEANUP_WORKER_THREADS);
    this.collectionBatchSize = Math.max(1, workerThreads * 4);
    this.pollIntervalMs = config.get(IcebergConfig.ASYNC_CLEANUP_POLL_INTERVAL_SECS) * 1000L;
    this.receiptRetentionMs = config.get(IcebergConfig.ASYNC_CLEANUP_RETENTION_HOURS) * 3_600_000L;

    int targetBatchSize =
        Math.min(
            IcebergPurgeJobStore.MAX_TARGET_WRITE_BATCH,
            config.get(IcebergConfig.ASYNC_CLEANUP_DELETE_BATCH_SIZE));
    int candidateWindow = Math.max(8, workerThreads * 4);
    int maxAttempts = config.get(IcebergConfig.ASYNC_CLEANUP_MAX_ATTEMPTS);
    long leaseDurationMs = config.get(IcebergConfig.ASYNC_CLEANUP_HEARTBEAT_TIMEOUT_SECS) * 1000L;
    IcebergPurgeWorkerOptions options =
        IcebergPurgeWorkerOptions.builder()
            .withLeaseDurationMs(leaseDurationMs)
            .withJobCandidateWindow(candidateWindow)
            .withTargetBatchSize(targetBatchSize)
            .withPlanningWriteBatchSize(targetBatchSize)
            .withMaxTargetBatchesPerRun(MAX_TARGET_BATCHES_PER_RUN)
            .withMaxActionAttempts(maxAttempts)
            .withMaxTargetAttempts(maxAttempts)
            .withRetryDelayMs(pollIntervalMs)
            .build();
    this.resources = new IcebergPurgeResources(store, wrapperManager);
    IcebergPurgeRegistrationRemover registrationRemover =
        new IcebergExactRegistrationRemover(resources);
    IcebergPurgePlanner planner = new IcebergMetadataGraphPlanner(resources);
    IcebergPurgeTargetDeleter deleter =
        targetDeleterDecorator.apply(new IcebergFileIOPurgeTargetDeleter(resources));
    Objects.requireNonNull(deleter, "decorated target deleter must not be null");
    this.worker =
        metricsSource == null
            ? new IcebergPurgeWorker(
                store,
                contextStore,
                registrationRemover,
                planner,
                deleter,
                System::currentTimeMillis,
                options)
            : new IcebergPurgeWorker(
                store,
                contextStore,
                registrationRemover,
                planner,
                deleter,
                System::currentTimeMillis,
                options,
                metricsSource);
  }

  /** Starts the bounded collector, worker loops, and terminal-ledger pruning. */
  public void start() {
    if (closed.get()) {
      throw new IllegalStateException("Iceberg purge manager is already closed");
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("iceberg-purge-collector-%d")
                .build());
    scheduler.scheduleWithFixedDelay(
        this::collectSafely, 0L, pollIntervalMs, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::expireLedgersSafely, 1L, 1L, TimeUnit.HOURS);

    workers =
        Executors.newFixedThreadPool(
            workerThreads,
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("iceberg-purge-worker-%d")
                .build());
    for (int index = 0; index < workerThreads; index++) {
      String owner = processId + "-worker-" + index;
      workers.submit(() -> workerLoop(owner));
    }
  }

  /**
   * Runs one bounded collection and worker turn synchronously.
   *
   * <p>This safe trigger is used by deterministic integration tests and may also support a future
   * operator redrive endpoint. Normal background execution uses the same methods and invariants.
   *
   * @return true when an eligible batch was collected or a queued batch was claimed
   */
  public boolean runOnce() {
    boolean collected = collectOnce();
    String owner = processId + "-manual-" + manualRun.incrementAndGet();
    return worker.runOnce(owner) || collected;
  }

  /**
   * Redrives a bounded set of terminal failed items, then advances the normal worker once.
   *
   * <p>This method is called only by the explicitly enabled, authenticated integration-test hook.
   * The redrive transaction preserves successful target rows and historical attempt counters while
   * recording the new action attempt.
   *
   * @param actor non-secret audit actor
   * @param correlationId non-secret audit correlation identifier
   * @return number of failed actions made claimable
   */
  public int redriveFailedAndRunOnce(String actor, String correlationId) {
    int redriven =
        store.redriveFailedActions(
            collectionBatchSize, actor, correlationId, System.currentTimeMillis());
    runOnce();
    return redriven;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    if (workers != null) {
      workers.shutdownNow();
      awaitTermination(workers);
    }
    resources.close();
  }

  private boolean collectOnce() {
    long now = System.currentTimeMillis();
    return store
        .claimEligibleBatch(
            now, collectionBatchSize, processId + "-collector", null, UUID.randomUUID().toString())
        .isPresent();
  }

  private void collectSafely() {
    if (!running.get()) {
      return;
    }
    try {
      collectOnce();
    } catch (Throwable t) {
      LOG.warn("Iceberg purge collection failed; the next bounded poll will retry", t);
    }
  }

  private void workerLoop(String owner) {
    while (running.get()) {
      try {
        if (!worker.runOnce(owner)) {
          sleep(pollIntervalMs);
        }
      } catch (Throwable t) {
        LOG.warn("Iceberg purge worker failed; durable work will be reclaimed", t);
        sleep(pollIntervalMs);
      }
    }
  }

  private void expireLedgersSafely() {
    if (!running.get()) {
      return;
    }
    try {
      store.expireTerminalLedgers(
          System.currentTimeMillis() - receiptRetentionMs, collectionBatchSize);
    } catch (Throwable t) {
      LOG.warn("Iceberg purge target-ledger expiry failed", t);
    }
  }

  private static void awaitTermination(ExecutorService executor) {
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(long delayMs) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
