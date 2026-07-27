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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionContextStore;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeCountsPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgePlanPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;

/**
 * Restart-safe, bounded worker for one durable Iceberg hard-purge batch at a time.
 *
 * <p>All external work is expressed through the planner and per-target deleter interfaces. The
 * worker performs no external call until the full target snapshot is durably READY, writes success
 * per exact target, and commits a table action PURGED only through the exact-generation relational
 * finalizer.
 */
public class IcebergPurgeWorker {

  private final IcebergPurgeJobStore store;
  private final IcebergDeletionContextStore contextStore;
  private final IcebergPurgePlanner planner;
  private final IcebergPurgeTargetDeleter deleter;
  private final LongSupplier clock;
  private final IcebergPurgeWorkerOptions options;
  private final Runnable beforeFinalize;

  /**
   * Creates a worker whose external Iceberg behavior is fully injectable and testable.
   *
   * @param store durable batch and progress store
   * @param contextStore immutable Iceberg deletion-context store
   * @param planner streaming exact-target snapshotter
   * @param deleter exact target hard deleter
   * @param clock authoritative server clock
   * @param options bounded lease, batch, and retry controls
   */
  public IcebergPurgeWorker(
      IcebergPurgeJobStore store,
      IcebergDeletionContextStore contextStore,
      IcebergPurgePlanner planner,
      IcebergPurgeTargetDeleter deleter,
      LongSupplier clock,
      IcebergPurgeWorkerOptions options) {
    this(store, contextStore, planner, deleter, clock, options, () -> {});
  }

  IcebergPurgeWorker(
      IcebergPurgeJobStore store,
      IcebergDeletionContextStore contextStore,
      IcebergPurgePlanner planner,
      IcebergPurgeTargetDeleter deleter,
      LongSupplier clock,
      IcebergPurgeWorkerOptions options,
      Runnable beforeFinalize) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.contextStore = Objects.requireNonNull(contextStore, "contextStore must not be null");
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.deleter = Objects.requireNonNull(deleter, "deleter must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.beforeFinalize = Objects.requireNonNull(beforeFinalize, "beforeFinalize must not be null");
    options.validate();
  }

  /**
   * Claims and advances at most one durable purge batch.
   *
   * @param owner stable worker identity
   * @return true when a batch was claimed, false when no work was available
   */
  public boolean runOnce(String owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    long now = clock.getAsLong();
    Optional<IcebergPurgeJobPO> claimed =
        store.takeJob(owner, now, options.getLeaseDurationMs(), options.getJobCandidateWindow());
    if (claimed.isEmpty()) {
      return false;
    }

    IcebergPurgeJobPO job = claimed.get();
    WorkBudget budget = new WorkBudget(options.getMaxTargetBatchesPerRun());
    boolean lostLease = false;
    for (EntityDeletionPO snapshot : store.listActions(job.getPurgeJobId())) {
      if (!budget.hasRemaining()) {
        break;
      }
      now = clock.getAsLong();
      if (!store.heartbeat(
          job.getPurgeJobId(), owner, job.getLeaseEpoch(), now, options.getLeaseDurationMs())) {
        lostLease = true;
        break;
      }

      EntityDeletionPO current = store.getAction(snapshot.getDeletionId());
      if (current == null
          || "SUCCEEDED".equals(current.getCleanupStatus())
          || "FAILED".equals(current.getCleanupStatus())) {
        continue;
      }
      Optional<EntityDeletionPO> started =
          store.beginAction(
              current.getDeletionId(),
              job.getPurgeJobId(),
              owner,
              job.getLeaseEpoch(),
              clock.getAsLong());
      if (started.isEmpty()) {
        if (!store.ownsLease(job.getPurgeJobId(), owner, job.getLeaseEpoch(), clock.getAsLong())) {
          lostLease = true;
          break;
        }
        continue;
      }

      EntityDeletionPO action = started.get();
      Outcome outcome = processAction(job, action, owner, budget);
      if (outcome.kind == OutcomeKind.LOST_LEASE) {
        lostLease = true;
        break;
      }
      if (outcome.kind == OutcomeKind.YIELDED) {
        store.yieldAction(action, owner, job.getLeaseEpoch(), clock.getAsLong());
        break;
      }
      if (outcome.kind == OutcomeKind.RETRYABLE_FAILURE
          || outcome.kind == OutcomeKind.PERMANENT_FAILURE) {
        boolean retryable = outcome.kind == OutcomeKind.RETRYABLE_FAILURE;
        if (!store.recordActionFailure(
            action,
            owner,
            job.getLeaseEpoch(),
            retryable,
            options.getMaxActionAttempts(),
            outcome.reasonCode,
            outcome.reason,
            clock.getAsLong())) {
          lostLease = true;
          break;
        }
      }
    }

    if (!lostLease) {
      store.settleJob(job.getPurgeJobId(), owner, job.getLeaseEpoch(), clock.getAsLong());
    }
    return true;
  }

  private Outcome processAction(
      IcebergPurgeJobPO job, EntityDeletionPO action, String owner, WorkBudget budget) {
    IcebergDeletionContextPO context = contextStore.get(action.getDeletionId());
    if (context == null) {
      return Outcome.permanent("MISSING_CONTEXT", "Immutable Iceberg deletion context is missing");
    }

    IcebergPurgePlanPO plan =
        store.beginPlan(
            action.getDeletionId(),
            job.getPurgeJobId(),
            context.getContextDigest(),
            clock.getAsLong());
    if ("PLANNING".equals(plan.getState())) {
      PlanningBuffer buffer =
          new PlanningBuffer(action, job, context, owner, options.getPlanningWriteBatchSize());
      try {
        String rootTargetId = planner.snapshot(context, buffer::add);
        buffer.flush();
        store.completePlan(
            action.getDeletionId(),
            job.getPurgeJobId(),
            context.getContextDigest(),
            rootTargetId,
            clock.getAsLong());
      } catch (LeaseLostDuringPlanningException e) {
        return Outcome.lostLease();
      } catch (IcebergPurgeException e) {
        return Outcome.failure(e.retryable(), e.reasonCode(), e.getMessage());
      }
    }

    while (budget.take()) {
      long now = clock.getAsLong();
      if (!store.heartbeat(
          job.getPurgeJobId(), owner, job.getLeaseEpoch(), now, options.getLeaseDurationMs())) {
        return Outcome.lostLease();
      }
      List<IcebergPurgeTargetPO> targets =
          store.claimTargetBatch(
              action.getDeletionId(),
              job.getPurgeJobId(),
              owner,
              job.getLeaseEpoch(),
              now,
              options.getTargetBatchSize());
      if (targets.isEmpty()) {
        return finishOrClassify(job, action, owner);
      }

      boolean retryableFailure = false;
      boolean permanentFailure = false;
      String reasonCode = null;
      String reason = null;
      for (IcebergPurgeTargetPO target : targets) {
        try {
          deleter.delete(context, target);
          if (!store.markTargetSucceeded(target, owner, job.getLeaseEpoch(), clock.getAsLong())) {
            return Outcome.lostLease();
          }
        } catch (IcebergPurgeException e) {
          boolean willRetry =
              e.retryable() && target.getAttemptCount() < options.getMaxTargetAttempts();
          if (!store.markTargetFailure(
              target,
              owner,
              job.getLeaseEpoch(),
              e.retryable(),
              options.getMaxTargetAttempts(),
              e.getMessage(),
              clock.getAsLong())) {
            return Outcome.lostLease();
          }
          retryableFailure |= willRetry;
          permanentFailure |= !willRetry;
          reasonCode = e.reasonCode();
          reason = e.getMessage();
        }
      }
      if (permanentFailure) {
        return Outcome.permanent(reasonCode, reason);
      }
      if (retryableFailure) {
        return Outcome.retryable(reasonCode, reason);
      }
    }
    return Outcome.yielded();
  }

  private Outcome finishOrClassify(IcebergPurgeJobPO job, EntityDeletionPO action, String owner) {
    IcebergPurgeCountsPO counts = store.targetCounts(action.getDeletionId());
    if (counts.getFailedCount() > 0) {
      return Outcome.permanent("TARGET_FAILED", "One or more exact purge targets failed");
    }
    if (counts.unfinishedCount() > 0) {
      return Outcome.retryable("TARGET_RETRY", "One or more exact purge targets need retry");
    }

    beforeFinalize.run();
    if (store.finalizePurgedAction(
        action.getDeletionId(),
        job.getPurgeJobId(),
        owner,
        job.getLeaseEpoch(),
        clock.getAsLong())) {
      return Outcome.succeeded();
    }
    if (!store.ownsLease(job.getPurgeJobId(), owner, job.getLeaseEpoch(), clock.getAsLong())) {
      return Outcome.lostLease();
    }
    return Outcome.permanent(
        "METADATA_GENERATION_MISMATCH",
        "Exact table metadata generation was not eligible for purge finalization");
  }

  private final class PlanningBuffer {
    private final EntityDeletionPO action;
    private final IcebergPurgeJobPO job;
    private final IcebergDeletionContextPO context;
    private final String owner;
    private final int batchSize;
    private final List<IcebergPurgeTargetPO> buffered;

    private PlanningBuffer(
        EntityDeletionPO action,
        IcebergPurgeJobPO job,
        IcebergDeletionContextPO context,
        String owner,
        int batchSize) {
      this.action = action;
      this.job = job;
      this.context = context;
      this.owner = owner;
      this.batchSize = batchSize;
      this.buffered = new ArrayList<>(batchSize);
    }

    private String add(IcebergPurgeTarget target) {
      String targetId = target.targetId(action.getDeletionId());
      long now = clock.getAsLong();
      buffered.add(
          IcebergPurgeTargetPO.builder()
              .withDeletionId(action.getDeletionId())
              .withTargetId(targetId)
              .withPurgeJobId(job.getPurgeJobId())
              .withTargetType(target.type().name())
              .withTargetUri(target.uri())
              .withObjectVersion(target.objectVersion())
              .withDeleteOrder(target.deleteOrder())
              .withState("PENDING")
              .withLeaseEpoch(0L)
              .withAttemptCount(0)
              .withCreatedAt(now)
              .withUpdatedAt(now)
              .build());
      if (buffered.size() == batchSize) {
        flush();
      }
      return targetId;
    }

    private void flush() {
      if (buffered.isEmpty()) {
        return;
      }
      long now = clock.getAsLong();
      if (!store.heartbeat(
          job.getPurgeJobId(), owner, job.getLeaseEpoch(), now, options.getLeaseDurationMs())) {
        throw new LeaseLostDuringPlanningException();
      }
      if (!context
          .getContextDigest()
          .equals(store.getPlan(action.getDeletionId()).getContextDigest())) {
        throw new IllegalStateException("Deletion context changed during target planning");
      }
      store.addTargetBatch(List.copyOf(buffered));
      buffered.clear();
    }
  }

  private static final class LeaseLostDuringPlanningException extends RuntimeException {}

  private enum OutcomeKind {
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    YIELDED,
    LOST_LEASE
  }

  private static final class Outcome {
    private final OutcomeKind kind;
    private final String reasonCode;
    private final String reason;

    private Outcome(OutcomeKind kind, String reasonCode, String reason) {
      this.kind = kind;
      this.reasonCode = reasonCode;
      this.reason = reason;
    }

    private static Outcome succeeded() {
      return new Outcome(OutcomeKind.SUCCEEDED, null, null);
    }

    private static Outcome retryable(String reasonCode, String reason) {
      return new Outcome(OutcomeKind.RETRYABLE_FAILURE, reasonCode, reason);
    }

    private static Outcome permanent(String reasonCode, String reason) {
      return new Outcome(OutcomeKind.PERMANENT_FAILURE, reasonCode, reason);
    }

    private static Outcome failure(boolean retryable, String reasonCode, String reason) {
      return retryable ? retryable(reasonCode, reason) : permanent(reasonCode, reason);
    }

    private static Outcome yielded() {
      return new Outcome(OutcomeKind.YIELDED, null, null);
    }

    private static Outcome lostLease() {
      return new Outcome(OutcomeKind.LOST_LEASE, null, null);
    }
  }

  private static final class WorkBudget {
    private int remaining;

    private WorkBudget(int remaining) {
      this.remaining = remaining;
    }

    private boolean hasRemaining() {
      return remaining > 0;
    }

    private boolean take() {
      if (remaining == 0) {
        return false;
      }
      remaining--;
      return true;
    }
  }
}
