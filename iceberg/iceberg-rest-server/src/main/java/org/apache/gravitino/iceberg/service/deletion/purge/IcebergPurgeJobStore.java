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
import org.apache.gravitino.iceberg.service.cleanup.mapper.provider.IcebergCleanupMapperPackageProvider;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgeActionMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgeJobMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgeMetadataMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgePlanMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgeTargetMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeCountsPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgePlanPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionAuditMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionAuditPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.ibatis.session.Configuration;

/**
 * Durable batch, lease, and progress storage for deletion-action based Iceberg hard purge.
 *
 * <p>The collector transaction claims table-level {@code entity_deletion} rows directly from
 * DELETED to PURGING, creates exactly one nonempty batch header, and appends one audit event per
 * winner. There is deliberately no persisted PURGE_PENDING action state: eligibility is derived
 * from the fixed retention deadline.
 */
public class IcebergPurgeJobStore {

  /** Durable executor discriminator captured on every supported deletion action. */
  public static final String PURGE_JOB_TYPE = "ICEBERG_REST_PURGE";

  /** Maximum target rows accepted by one planning write transaction. */
  public static final int MAX_TARGET_WRITE_BATCH = 1000;

  private static final int MAX_ERROR_LENGTH = 2048;

  private final IdGenerator idGenerator;
  private final Runnable afterCandidateScan;

  /**
   * Creates a purge store on the shared entity-store connection pool.
   *
   * @param idGenerator generator used for opaque batch and audit identifiers
   */
  public IcebergPurgeJobStore(IdGenerator idGenerator) {
    this(idGenerator, () -> {});
  }

  IcebergPurgeJobStore(IdGenerator idGenerator, Runnable afterCandidateScan) {
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
    this.afterCandidateScan =
        Objects.requireNonNull(afterCandidateScan, "afterCandidateScan must not be null");
    registerMappers();
  }

  /**
   * Atomically claims a bounded set of eligible retained actions into one nonempty purge batch.
   *
   * <p>Every winning action is moved directly from DELETED to PURGING and stamped with the same
   * batch identifier in the transaction that inserts the header and audit events. A candidate that
   * loses its revision/state/deadline CAS is omitted; if all candidates lose, the transaction
   * creates no durable header.
   *
   * @param now authoritative server timestamp in milliseconds
   * @param limit maximum table actions in the batch
   * @param collector identity of the collector creating the batch
   * @param requestId optional collection request identifier
   * @param correlationId non-secret audit correlation identifier
   * @return the created nonempty batch, or empty when no action was claimed
   */
  public Optional<IcebergPurgeJobPO> claimEligibleBatch(
      long now, int limit, String collector, String requestId, String correlationId) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    Objects.requireNonNull(collector, "collector must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");

    SessionUtils.beginTransaction();
    try {
      List<EntityDeletionPO> candidates =
          SessionUtils.getWithoutCommit(
              IcebergPurgeActionMapper.class,
              mapper -> mapper.selectEligibleActions(PURGE_JOB_TYPE, now, limit));
      if (candidates.isEmpty()) {
        SessionUtils.rollbackTransaction();
        return Optional.empty();
      }
      afterCandidateScan.run();

      String purgeJobId = nextOpaqueId();
      List<EntityDeletionPO> claimed = new ArrayList<>(candidates.size());
      for (EntityDeletionPO candidate : candidates) {
        int updated =
            SessionUtils.doWithCommitAndFetchResult(
                IcebergPurgeActionMapper.class,
                mapper ->
                    mapper.claimAction(
                        candidate.getDeletionId(),
                        candidate.getRevision(),
                        PURGE_JOB_TYPE,
                        purgeJobId,
                        now));
        if (updated == 1) {
          claimed.add(candidate);
        }
      }

      if (claimed.isEmpty()) {
        SessionUtils.rollbackTransaction();
        return Optional.empty();
      }

      IcebergPurgeJobPO job =
          newPendingJob(purgeJobId, claimed.size(), collector, requestId, correlationId, now);
      SessionUtils.doWithCommit(IcebergPurgeJobMapper.class, mapper -> mapper.insertJob(job));
      for (EntityDeletionPO action : claimed) {
        EntityDeletionAuditPO audit = claimAudit(action, purgeJobId, collector, correlationId, now);
        SessionUtils.doWithCommit(
            EntityDeletionAuditMapper.class, mapper -> mapper.insertAudit(audit));
      }
      SessionUtils.commitTransaction();
      return Optional.of(job);
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /**
   * Claims or reclaims the first available batch with a fenced lease.
   *
   * @param owner stable worker identity
   * @param now authoritative server timestamp in milliseconds
   * @param leaseDurationMs positive lease duration
   * @param window maximum candidate headers to inspect
   * @return the claimed batch including its newly incremented lease epoch
   */
  public Optional<IcebergPurgeJobPO> takeJob(
      String owner, long now, long leaseDurationMs, int window) {
    Objects.requireNonNull(owner, "owner must not be null");
    if (leaseDurationMs <= 0 || window <= 0) {
      throw new IllegalArgumentException("leaseDurationMs and window must be positive");
    }
    long leaseExpiresAt = Math.addExact(now, leaseDurationMs);
    List<IcebergPurgeJobPO> candidates =
        SessionUtils.getWithoutCommit(
            IcebergPurgeJobMapper.class, mapper -> mapper.selectClaimableJobs(now, window));
    for (IcebergPurgeJobPO candidate : candidates) {
      SessionUtils.beginTransaction();
      try {
        int updated =
            SessionUtils.doWithCommitAndFetchResult(
                IcebergPurgeJobMapper.class,
                mapper -> mapper.claimJob(candidate.getPurgeJobId(), owner, now, leaseExpiresAt));
        if (updated == 0) {
          SessionUtils.rollbackTransaction();
          continue;
        }

        IcebergPurgeJobPO claimed =
            SessionUtils.getWithoutCommit(
                IcebergPurgeJobMapper.class, mapper -> mapper.selectJob(candidate.getPurgeJobId()));
        if (claimed == null) {
          throw new IllegalStateException("Claimed purge job disappeared");
        }
        SessionUtils.doWithCommit(
            IcebergPurgeTargetMapper.class,
            mapper ->
                mapper.resetRunningTargets(claimed.getPurgeJobId(), claimed.getLeaseEpoch(), now));
        SessionUtils.doWithCommit(
            IcebergPurgeActionMapper.class,
            mapper -> mapper.resetRunningActions(claimed.getPurgeJobId(), now));
        SessionUtils.commitTransaction();
        return Optional.of(claimed);
      } catch (Throwable t) {
        SessionUtils.rollbackTransaction();
        throw t;
      }
    }
    return Optional.empty();
  }

  /**
   * Renews a batch lease only for its exact current owner and fencing epoch.
   *
   * @param purgeJobId batch identifier
   * @param owner worker identity
   * @param leaseEpoch fencing epoch returned by {@link #takeJob}
   * @param now authoritative server timestamp
   * @param leaseDurationMs positive lease duration
   * @return true only when the worker still owns an unexpired lease
   */
  public boolean heartbeat(
      String purgeJobId, String owner, long leaseEpoch, long now, long leaseDurationMs) {
    Objects.requireNonNull(purgeJobId, "purgeJobId must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    if (leaseDurationMs <= 0) {
      throw new IllegalArgumentException("leaseDurationMs must be positive");
    }
    long leaseExpiresAt = Math.addExact(now, leaseDurationMs);
    return SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeJobMapper.class,
            mapper -> mapper.heartbeatJob(purgeJobId, owner, leaseEpoch, now, leaseExpiresAt))
        == 1;
  }

  /** Returns true only while the exact worker owns the exact unexpired fencing epoch. */
  public boolean ownsLease(String purgeJobId, String owner, long leaseEpoch, long now) {
    return SessionUtils.getWithoutCommit(
            IcebergPurgeJobMapper.class,
            mapper -> mapper.ownsJobLease(purgeJobId, owner, leaseEpoch, now))
        == 1;
  }

  /** Returns one exact batch header, or {@code null} when absent. */
  public IcebergPurgeJobPO getJob(String purgeJobId) {
    Objects.requireNonNull(purgeJobId, "purgeJobId must not be null");
    return SessionUtils.getWithoutCommit(
        IcebergPurgeJobMapper.class, mapper -> mapper.selectJob(purgeJobId));
  }

  /** Returns all durable table-level actions associated with a batch. */
  public List<EntityDeletionPO> listActions(String purgeJobId) {
    Objects.requireNonNull(purgeJobId, "purgeJobId must not be null");
    return SessionUtils.getWithoutCommit(
        IcebergPurgeActionMapper.class, mapper -> mapper.selectActionsByJob(purgeJobId));
  }

  /** Returns one exact deletion action, or {@code null} when absent. */
  public EntityDeletionPO getAction(String deletionId) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    return SessionUtils.getWithoutCommit(
        IcebergPurgeActionMapper.class, mapper -> mapper.selectAction(deletionId));
  }

  /** Starts one pending table-level item under the exact live batch lease. */
  public Optional<EntityDeletionPO> beginAction(
      String deletionId, String purgeJobId, String owner, long leaseEpoch, long now) {
    int updated =
        SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeActionMapper.class,
            mapper -> mapper.beginAction(deletionId, purgeJobId, owner, leaseEpoch, now));
    if (updated == 0) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        SessionUtils.getWithoutCommit(
            IcebergPurgeActionMapper.class, mapper -> mapper.selectAction(deletionId)));
  }

  /**
   * Records a retryable or permanent table-item failure without rolling its lifecycle to DELETED.
   */
  public boolean recordActionFailure(
      EntityDeletionPO action,
      String owner,
      long leaseEpoch,
      boolean retryable,
      int maxAttempts,
      String reasonCode,
      String reason,
      long now) {
    Objects.requireNonNull(action, "action must not be null");
    String nextStatus =
        retryable && action.getCleanupAttemptCount() + 1 < maxAttempts ? "PENDING" : "FAILED";
    String safeReason = sanitizeReason(reason);
    SessionUtils.beginTransaction();
    try {
      int updated =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeActionMapper.class,
              mapper ->
                  mapper.recordActionFailure(
                      action.getDeletionId(),
                      action.getPurgeJobId(),
                      owner,
                      leaseEpoch,
                      nextStatus,
                      safeReason,
                      now));
      if (updated == 0) {
        SessionUtils.rollbackTransaction();
        return false;
      }
      IcebergPurgeJobPO job =
          SessionUtils.getWithoutCommit(
              IcebergPurgeJobMapper.class, mapper -> mapper.selectJob(action.getPurgeJobId()));
      if (job == null) {
        throw new IllegalStateException("Purge job disappeared while recording item failure");
      }
      EntityDeletionAuditPO audit =
          EntityDeletionAuditPO.builder()
              .auditId(nextOpaqueId())
              .deletionId(action.getDeletionId())
              .entityType(action.getEntityType())
              .entityId(action.getEntityId())
              .eventType("FAILED".equals(nextStatus) ? "PURGE_ITEM_FAILED" : "PURGE_ITEM_RETRY")
              .actionRevision(action.getRevision())
              .priorState("PURGING")
              .newState("PURGING")
              .priorCleanupStatus("RUNNING")
              .newCleanupStatus(nextStatus)
              .purgeJobId(action.getPurgeJobId())
              .leaseEpoch(leaseEpoch)
              .actor(owner)
              .requestId(action.getRequestId())
              .correlationId(job.getCorrelationId())
              .reasonCode(reasonCode)
              .reason(safeReason)
              .createdAt(now)
              .build();
      SessionUtils.doWithCommit(
          EntityDeletionAuditMapper.class, mapper -> mapper.insertAudit(audit));
      SessionUtils.commitTransaction();
      return true;
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /** Yields an item after bounded work without counting it as a cleanup failure. */
  public boolean yieldAction(EntityDeletionPO action, String owner, long leaseEpoch, long now) {
    return SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeActionMapper.class,
            mapper ->
                mapper.yieldAction(
                    action.getDeletionId(), action.getPurgeJobId(), owner, leaseEpoch, now))
        == 1;
  }

  long countJobs() {
    return SessionUtils.getWithoutCommit(IcebergPurgeJobMapper.class, mapper -> mapper.countJobs());
  }

  long countAudits(String purgeJobId) {
    return SessionUtils.getWithoutCommit(
        IcebergPurgeActionMapper.class, mapper -> mapper.countAuditsByJob(purgeJobId));
  }

  /**
   * Creates an idempotent PLANNING marker before streamed target enumeration begins.
   *
   * @param deletionId deletion action identifier
   * @param purgeJobId owning batch identifier
   * @param contextDigest immutable deletion-context digest
   * @param now authoritative server timestamp
   * @return the new or existing matching marker
   */
  public IcebergPurgePlanPO beginPlan(
      String deletionId, String purgeJobId, String contextDigest, long now) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    Objects.requireNonNull(purgeJobId, "purgeJobId must not be null");
    Objects.requireNonNull(contextDigest, "contextDigest must not be null");
    IcebergPurgePlanPO existing = getPlan(deletionId);
    if (existing != null) {
      verifyPlanIdentity(existing, purgeJobId, contextDigest);
      return existing;
    }

    IcebergPurgePlanPO plan =
        IcebergPurgePlanPO.builder()
            .withDeletionId(deletionId)
            .withPurgeJobId(purgeJobId)
            .withContextDigest(contextDigest)
            .withState("PLANNING")
            .withTargetCount(0L)
            .withCreatedAt(now)
            .withUpdatedAt(now)
            .build();
    SessionUtils.doWithCommit(IcebergPurgePlanMapper.class, mapper -> mapper.insertPlan(plan));
    return plan;
  }

  /**
   * Appends one bounded chunk of immutable target snapshots idempotently.
   *
   * <p>Planning callers stream into this method; passing more than {@link #MAX_TARGET_WRITE_BATCH}
   * rows is rejected so a 500,000-file table never requires one giant transaction or in-memory
   * collection.
   *
   * @param targets one nonempty bounded target chunk
   */
  public void addTargetBatch(List<IcebergPurgeTargetPO> targets) {
    Objects.requireNonNull(targets, "targets must not be null");
    if (targets.isEmpty() || targets.size() > MAX_TARGET_WRITE_BATCH) {
      throw new IllegalArgumentException(
          "target batch size must be between 1 and " + MAX_TARGET_WRITE_BATCH);
    }

    SessionUtils.beginTransaction();
    try {
      for (IcebergPurgeTargetPO target : targets) {
        validatePendingTarget(target);
        IcebergPurgePlanPO plan =
            SessionUtils.getWithoutCommit(
                IcebergPurgePlanMapper.class, mapper -> mapper.selectPlan(target.getDeletionId()));
        if (plan == null || !"PLANNING".equals(plan.getState())) {
          throw new IllegalStateException(
              "Target snapshot is not in PLANNING for " + target.getDeletionId());
        }
        verifyPlanIdentity(plan, target.getPurgeJobId(), plan.getContextDigest());

        IcebergPurgeTargetPO existing =
            SessionUtils.getWithoutCommit(
                IcebergPurgeTargetMapper.class,
                mapper -> mapper.selectTarget(target.getDeletionId(), target.getTargetId()));
        if (existing == null) {
          SessionUtils.doWithCommit(
              IcebergPurgeTargetMapper.class, mapper -> mapper.insertTarget(target));
        } else {
          verifySameTarget(existing, target);
        }
      }
      SessionUtils.commitTransaction();
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /**
   * Publishes the target plan only after proving it is nonempty and root metadata is ordered last.
   *
   * @param deletionId deletion action identifier
   * @param purgeJobId owning batch identifier
   * @param contextDigest immutable deletion-context digest
   * @param rootTargetId designated root metadata target
   * @param now authoritative server timestamp
   * @return the durable target count
   */
  public long completePlan(
      String deletionId, String purgeJobId, String contextDigest, String rootTargetId, long now) {
    Objects.requireNonNull(rootTargetId, "rootTargetId must not be null");
    SessionUtils.beginTransaction();
    try {
      IcebergPurgePlanPO plan =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class, mapper -> mapper.selectPlan(deletionId));
      if (plan == null) {
        throw new IllegalStateException("No target plan for " + deletionId);
      }
      verifyPlanIdentity(plan, purgeJobId, contextDigest);
      if ("READY".equals(plan.getState())) {
        SessionUtils.commitTransaction();
        return plan.getTargetCount();
      }
      if (!"PLANNING".equals(plan.getState())) {
        throw new IllegalStateException("Unexpected target plan state " + plan.getState());
      }

      long count =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class, mapper -> mapper.countTargets(deletionId));
      Integer maximumOrder =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class, mapper -> mapper.maxDeleteOrder(deletionId));
      Integer rootOrder =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class,
              mapper -> mapper.selectTargetOrder(deletionId, rootTargetId));
      long rootCount =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class, mapper -> mapper.countRootTargets(deletionId));
      if (count == 0 || rootCount != 1 || rootOrder == null || !rootOrder.equals(maximumOrder)) {
        throw new IllegalStateException(
            "Target plan must be nonempty with designated root metadata ordered last");
      }

      int updated =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgePlanMapper.class,
              mapper ->
                  mapper.markPlanReady(
                      deletionId, purgeJobId, contextDigest, count, rootTargetId, now));
      if (updated != 1) {
        throw new IllegalStateException("Target plan changed while completing " + deletionId);
      }
      SessionUtils.commitTransaction();
      return count;
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /** Returns one exact target-plan marker, or {@code null} when absent. */
  public IcebergPurgePlanPO getPlan(String deletionId) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    return SessionUtils.getWithoutCommit(
        IcebergPurgePlanMapper.class, mapper -> mapper.selectPlan(deletionId));
  }

  /** Returns one exact physical target, or {@code null} when absent. */
  public IcebergPurgeTargetPO getTarget(String deletionId, String targetId) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    Objects.requireNonNull(targetId, "targetId must not be null");
    return SessionUtils.getWithoutCommit(
        IcebergPurgeTargetMapper.class, mapper -> mapper.selectTarget(deletionId, targetId));
  }

  /**
   * Claims a bounded child-before-parent target batch under an exact live batch lease.
   *
   * @param deletionId table-level deletion action
   * @param purgeJobId owning batch
   * @param owner current worker identity
   * @param leaseEpoch current fencing epoch
   * @param now authoritative server timestamp
   * @param limit positive target limit
   * @return successfully claimed targets with incremented attempt counts
   */
  public List<IcebergPurgeTargetPO> claimTargetBatch(
      String deletionId, String purgeJobId, String owner, long leaseEpoch, long now, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    List<IcebergPurgeTargetPO> candidates =
        SessionUtils.getWithoutCommit(
            IcebergPurgeTargetMapper.class,
            mapper -> mapper.selectTargetCandidates(deletionId, purgeJobId, limit));
    List<IcebergPurgeTargetPO> claimed = new ArrayList<>(candidates.size());
    for (IcebergPurgeTargetPO candidate : candidates) {
      int updated =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeTargetMapper.class,
              mapper ->
                  mapper.claimTarget(
                      deletionId,
                      candidate.getTargetId(),
                      candidate.getState(),
                      purgeJobId,
                      owner,
                      leaseEpoch,
                      now));
      if (updated == 1) {
        IcebergPurgeTargetPO target = getTarget(deletionId, candidate.getTargetId());
        if (target != null) {
          claimed.add(target);
        }
      }
    }
    return claimed;
  }

  /** Marks one exact target succeeded if the caller still owns both fencing epochs. */
  public boolean markTargetSucceeded(
      IcebergPurgeTargetPO target, String owner, long leaseEpoch, long now) {
    return SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeTargetMapper.class,
            mapper ->
                mapper.markTargetSucceeded(
                    target.getDeletionId(),
                    target.getTargetId(),
                    target.getPurgeJobId(),
                    owner,
                    leaseEpoch,
                    now))
        == 1;
  }

  /** Records one target as retryable or permanently failed under the exact fencing epoch. */
  public boolean markTargetFailure(
      IcebergPurgeTargetPO target,
      String owner,
      long leaseEpoch,
      boolean retryable,
      int maxAttempts,
      String reason,
      long now) {
    String nextState = retryable && target.getAttemptCount() < maxAttempts ? "RETRYING" : "FAILED";
    return SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeTargetMapper.class,
            mapper ->
                mapper.markTargetFailed(
                    target.getDeletionId(),
                    target.getTargetId(),
                    target.getPurgeJobId(),
                    owner,
                    leaseEpoch,
                    nextState,
                    sanitizeReason(reason),
                    now))
        == 1;
  }

  /** Returns aggregate physical-target progress for one deletion action. */
  public IcebergPurgeCountsPO targetCounts(String deletionId) {
    return SessionUtils.getWithoutCommit(
        IcebergPurgeTargetMapper.class, mapper -> mapper.countTargetStatuses(deletionId));
  }

  /**
   * Expires a bounded set of physical target ledgers after terminal receipt retention.
   *
   * <p>This deliberately leaves {@code entity_deletion}, its append-only audit events, and the
   * compact batch header intact. Callers supply a cutoff derived from the independently configured
   * terminal receipt-retention policy; active, failed, and recently PURGED actions never match.
   *
   * @param receiptCutoff inclusive PURGED timestamp cutoff
   * @param limit maximum action ledgers to delete
   * @return number of plan ledgers removed
   */
  public int expireTerminalLedgers(long receiptCutoff, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    SessionUtils.beginTransaction();
    try {
      List<String> deletionIds =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class,
              mapper -> mapper.selectExpiredLedgerCandidates(receiptCutoff, limit));
      int removed = 0;
      for (String deletionId : deletionIds) {
        SessionUtils.doWithCommit(
            IcebergPurgeTargetMapper.class, mapper -> mapper.deleteTargets(deletionId));
        removed +=
            SessionUtils.doWithCommitAndFetchResult(
                IcebergPurgePlanMapper.class, mapper -> mapper.deletePlan(deletionId));
      }
      SessionUtils.commitTransaction();
      return removed;
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /**
   * Atomically hard-deletes the exact metadata generation and commits the action PURGED.
   *
   * <p>The parent table and every stamped child row are addressed by immutable source table id plus
   * deletion id. A restored, re-dropped, or same-name live table cannot match this predicate. The
   * target plan must be READY and every snapshotted target must already be individually SUCCEEDED.
   *
   * @param deletionId exact deletion generation
   * @param purgeJobId owning batch
   * @param owner current worker identity
   * @param leaseEpoch current fencing epoch
   * @param now authoritative server timestamp
   * @return true when this worker committed PURGED; false when it lost its fence or action state
   */
  public boolean finalizePurgedAction(
      String deletionId, String purgeJobId, String owner, long leaseEpoch, long now) {
    SessionUtils.beginTransaction();
    try {
      int fenced =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeJobMapper.class,
              mapper -> mapper.fenceJobLease(purgeJobId, owner, leaseEpoch, now));
      if (fenced != 1) {
        SessionUtils.rollbackTransaction();
        return false;
      }
      EntityDeletionPO action =
          SessionUtils.getWithoutCommit(
              IcebergPurgeActionMapper.class, mapper -> mapper.selectAction(deletionId));
      IcebergPurgeJobPO job =
          SessionUtils.getWithoutCommit(
              IcebergPurgeJobMapper.class, mapper -> mapper.selectJob(purgeJobId));
      IcebergPurgePlanPO plan =
          SessionUtils.getWithoutCommit(
              IcebergPurgePlanMapper.class, mapper -> mapper.selectPlan(deletionId));
      IcebergPurgeCountsPO targets =
          SessionUtils.getWithoutCommit(
              IcebergPurgeTargetMapper.class, mapper -> mapper.countTargetStatuses(deletionId));
      if (action == null
          || job == null
          || plan == null
          || !"PURGING".equals(action.getState())
          || !"RUNNING".equals(action.getCleanupStatus())
          || !purgeJobId.equals(action.getPurgeJobId())
          || !"READY".equals(plan.getState())
          || targets.unfinishedCount() != 0
          || targets.getFailedCount() != 0
          || targets.getSucceededCount().longValue() != plan.getTargetCount().longValue()) {
        SessionUtils.rollbackTransaction();
        return false;
      }

      long exactParentCount =
          SessionUtils.getWithoutCommit(
              IcebergPurgeMetadataMapper.class,
              mapper -> mapper.countExactTable(action.getEntityId(), deletionId));
      if (exactParentCount != 1) {
        SessionUtils.rollbackTransaction();
        return false;
      }

      int transitioned =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeActionMapper.class,
              mapper ->
                  mapper.markActionPurged(
                      deletionId, action.getEntityId(), purgeJobId, owner, leaseEpoch, now));
      if (transitioned != 1) {
        SessionUtils.rollbackTransaction();
        return false;
      }

      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteColumns(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteVersions(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteOwners(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteSecurableObjects(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteTagRelations(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deletePolicyRelations(action.getEntityId(), deletionId));
      SessionUtils.doWithCommit(
          IcebergPurgeMetadataMapper.class,
          mapper -> mapper.deleteStatistics(action.getEntityId(), deletionId));
      int deletedParent =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeMetadataMapper.class,
              mapper -> mapper.deleteTable(action.getEntityId(), deletionId));
      if (deletedParent != 1) {
        throw new IllegalStateException("Exact table generation changed during purge finalization");
      }

      EntityDeletionAuditPO audit =
          EntityDeletionAuditPO.builder()
              .auditId(nextOpaqueId())
              .deletionId(deletionId)
              .entityType(action.getEntityType())
              .entityId(action.getEntityId())
              .eventType("PURGED")
              .actionRevision(action.getRevision() + 1)
              .priorState("PURGING")
              .newState("PURGED")
              .priorCleanupStatus("RUNNING")
              .newCleanupStatus("SUCCEEDED")
              .purgeJobId(purgeJobId)
              .leaseEpoch(leaseEpoch)
              .actor(owner)
              .requestId(action.getRequestId())
              .correlationId(job.getCorrelationId())
              .createdAt(now)
              .build();
      SessionUtils.doWithCommit(
          EntityDeletionAuditMapper.class, mapper -> mapper.insertAudit(audit));
      SessionUtils.commitTransaction();
      return true;
    } catch (Throwable t) {
      SessionUtils.rollbackTransaction();
      throw t;
    }
  }

  /**
   * Stores aggregate progress and either releases unfinished work or commits a terminal batch.
   *
   * @param purgeJobId batch identifier
   * @param owner current worker identity
   * @param leaseEpoch current fencing epoch
   * @param now authoritative server timestamp
   * @return true when the exact owner and epoch update succeeds
   */
  public boolean settleJob(String purgeJobId, String owner, long leaseEpoch, long now) {
    IcebergPurgeCountsPO counts =
        SessionUtils.getWithoutCommit(
            IcebergPurgeActionMapper.class, mapper -> mapper.countActionStatuses(purgeJobId));
    IcebergPurgeJobPO job = getJob(purgeJobId);
    if (job == null) {
      return false;
    }
    long counted =
        counts.getPendingCount()
            + counts.getRunningCount()
            + counts.getSucceededCount()
            + counts.getFailedCount()
            + counts.getRetryingCount();
    if (counted != job.getItemCount()) {
      throw new IllegalStateException("Purge job aggregate does not match its durable item count");
    }
    if (counts.unfinishedCount() > 0) {
      return SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeJobMapper.class,
              mapper ->
                  mapper.releaseJob(
                      purgeJobId,
                      owner,
                      leaseEpoch,
                      counts.getPendingCount(),
                      counts.getRunningCount(),
                      counts.getSucceededCount(),
                      counts.getFailedCount(),
                      counts.getRetryingCount(),
                      now))
          == 1;
    }

    String terminalState;
    if (counts.getFailedCount() == 0) {
      terminalState = "SUCCEEDED";
    } else if (counts.getSucceededCount() == 0) {
      terminalState = "FAILED";
    } else {
      terminalState = "PARTIAL_FAILED";
    }
    return SessionUtils.doWithCommitAndFetchResult(
            IcebergPurgeJobMapper.class,
            mapper ->
                mapper.finishJob(
                    purgeJobId,
                    owner,
                    leaseEpoch,
                    terminalState,
                    counts.getSucceededCount(),
                    counts.getFailedCount(),
                    now))
        == 1;
  }

  private static void registerMappers() {
    Configuration configuration =
        SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().getConfiguration();
    for (Class<?> mapper : new IcebergCleanupMapperPackageProvider().getMapperClasses()) {
      synchronized (configuration) {
        if (!configuration.hasMapper(mapper)) {
          configuration.addMapper(mapper);
        }
      }
    }
  }

  private String nextOpaqueId() {
    return Long.toUnsignedString(idGenerator.nextId());
  }

  private static IcebergPurgeJobPO newPendingJob(
      String purgeJobId,
      int itemCount,
      String collector,
      String requestId,
      String correlationId,
      long now) {
    return IcebergPurgeJobPO.builder()
        .withPurgeJobId(purgeJobId)
        .withPurgeJobType(PURGE_JOB_TYPE)
        .withState("PENDING")
        .withLeaseEpoch(0L)
        .withAttemptCount(0)
        .withItemCount(itemCount)
        .withPendingCount(itemCount)
        .withRunningCount(0)
        .withSucceededCount(0)
        .withFailedCount(0)
        .withRetryingCount(0)
        .withCreatedBy(collector)
        .withRequestId(requestId)
        .withCorrelationId(correlationId)
        .withCreatedAt(now)
        .withUpdatedAt(now)
        .build();
  }

  private EntityDeletionAuditPO claimAudit(
      EntityDeletionPO action,
      String purgeJobId,
      String collector,
      String correlationId,
      long now) {
    return EntityDeletionAuditPO.builder()
        .auditId(nextOpaqueId())
        .deletionId(action.getDeletionId())
        .entityType(action.getEntityType())
        .entityId(action.getEntityId())
        .eventType("PURGE_CLAIMED")
        .actionRevision(action.getRevision() + 1)
        .priorState("DELETED")
        .newState("PURGING")
        .priorCleanupStatus(action.getCleanupStatus())
        .newCleanupStatus("PENDING")
        .purgeJobId(purgeJobId)
        .actor(collector)
        .requestId(action.getRequestId())
        .correlationId(correlationId)
        .createdAt(now)
        .build();
  }

  private static void validatePendingTarget(IcebergPurgeTargetPO target) {
    Objects.requireNonNull(target, "target must not be null");
    if (!"PENDING".equals(target.getState())
        || target.getLeaseEpoch() != 0
        || target.getAttemptCount() != 0) {
      throw new IllegalArgumentException("New targets must be unclaimed PENDING snapshots");
    }
  }

  private static void verifyPlanIdentity(
      IcebergPurgePlanPO plan, String purgeJobId, String contextDigest) {
    if (!plan.getPurgeJobId().equals(purgeJobId)
        || !plan.getContextDigest().equals(contextDigest)) {
      throw new IllegalStateException("Target plan identity does not match immutable purge input");
    }
  }

  private static void verifySameTarget(
      IcebergPurgeTargetPO existing, IcebergPurgeTargetPO candidate) {
    if (!existing.getPurgeJobId().equals(candidate.getPurgeJobId())
        || !existing.getTargetType().equals(candidate.getTargetType())
        || !existing.getTargetUri().equals(candidate.getTargetUri())
        || !Objects.equals(existing.getObjectVersion(), candidate.getObjectVersion())
        || !existing.getDeleteOrder().equals(candidate.getDeleteOrder())) {
      throw new IllegalStateException(
          "Target id collision or changed immutable snapshot for " + candidate.getTargetId());
    }
  }

  private static String sanitizeReason(String reason) {
    String safe = reason == null || reason.isBlank() ? "unspecified purge failure" : reason;
    safe = safe.replaceAll("(?i)[a-z][a-z0-9+.-]*://\\S+", "<redacted-location>");
    safe =
        safe.replaceAll(
            "(?i)(access[_-]?key|secret|token|password|credential)(\\s*[:=]\\s*)\\S+",
            "$1$2<redacted>");
    safe = safe.replace('\n', ' ').replace('\r', ' ');
    return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH);
  }
}
