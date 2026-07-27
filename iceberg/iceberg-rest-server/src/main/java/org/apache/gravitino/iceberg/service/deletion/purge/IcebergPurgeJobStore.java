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
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgePlanMapper;
import org.apache.gravitino.iceberg.service.deletion.purge.mapper.IcebergPurgeTargetMapper;
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
      int updated =
          SessionUtils.doWithCommitAndFetchResult(
              IcebergPurgeJobMapper.class,
              mapper -> mapper.claimJob(candidate.getPurgeJobId(), owner, now, leaseExpiresAt));
      if (updated == 1) {
        return Optional.ofNullable(getJob(candidate.getPurgeJobId()));
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
      if (count == 0 || rootOrder == null || !rootOrder.equals(maximumOrder)) {
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
}
