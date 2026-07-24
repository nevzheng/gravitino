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
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.InUseException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.job.JobHandle;
import org.apache.gravitino.meta.JobTemplateEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.JobTemplateMetaMapper;
import org.apache.gravitino.storage.relational.mapper.JobTemplateRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.JobRunRecoveryPO;
import org.apache.gravitino.storage.relational.po.JobTemplatePO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

public class JobTemplateMetaService {

  private static final JobTemplateMetaService INSTANCE = new JobTemplateMetaService();

  private JobTemplateMetaService() {
    // Private constructor to prevent instantiation
  }

  public static JobTemplateMetaService getInstance() {
    return INSTANCE;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listJobTemplatesByNamespace")
  public List<JobTemplateEntity> listJobTemplatesByNamespace(Namespace ns) {
    String metalakeName = ns.level(0);
    List<JobTemplatePO> jobTemplatePOs =
        SessionUtils.getWithoutCommit(
            JobTemplateMetaMapper.class,
            mapper -> mapper.listJobTemplatePOsByMetalake(metalakeName));

    return jobTemplatePOs.stream()
        .map(p -> JobTemplatePO.fromJobTemplatePO(p, ns))
        .collect(Collectors.toList());
  }

  /** Lists independently deleted job-template roots under one live metalake. */
  public List<JobTemplatePO> listDeletedJobTemplatesByNamespace(Namespace namespace) {
    NamespaceUtil.checkJobTemplate(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        JobTemplateRecoveryMapper.class, mapper -> mapper.listDeletedRootJobTemplates(metalakeId));
  }

  /** Lists live job-template rows under one metalake for recovery conflict detection. */
  public List<JobTemplatePO> listLiveJobTemplatePOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkJobTemplate(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        JobTemplateRecoveryMapper.class, mapper -> mapper.listLiveJobTemplates(metalakeId));
  }

  /** Lists globally live job-template rows matching candidate immutable IDs. */
  public List<JobTemplatePO> listLiveJobTemplatesByIds(List<Long> jobTemplateIds) {
    if (jobTemplateIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        JobTemplateRecoveryMapper.class,
        mapper -> mapper.listLiveJobTemplatesByIds(jobTemplateIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getJobTemplateByIdentifier")
  public JobTemplateEntity getJobTemplateByIdentifier(NameIdentifier jobTemplateIdent) {
    JobTemplatePO jobTemplatePO = getJobTemplatePO(jobTemplateIdent);
    return JobTemplatePO.fromJobTemplatePO(jobTemplatePO, jobTemplateIdent.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertJobTemplate")
  public void insertJobTemplate(JobTemplateEntity jobTemplateEntity, boolean overwrite)
      throws IOException {
    String metalakeName = jobTemplateEntity.namespace().level(0);

    try {
      Long metalakeId =
          EntityIdService.getEntityId(NameIdentifier.of(metalakeName), Entity.EntityType.METALAKE);
      JobTemplatePO.JobTemplatePOBuilder builder =
          JobTemplatePO.builder().withMetalakeId(metalakeId);
      JobTemplatePO jobTemplatePO =
          JobTemplatePO.initializeJobTemplatePO(jobTemplateEntity, builder);

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              SessionUtils.doWithoutCommit(
                  JobTemplateRecoveryMapper.class,
                  mapper -> {
                    if (overwrite
                        && !mapper
                            .selectRecordedDeletedJobTemplatesForUpdate(
                                Collections.singletonList(jobTemplatePO.jobTemplateId()))
                            .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Job-template ID %s belongs to a recoverable deletion; use metadata restore",
                          jobTemplatePO.jobTemplateId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  JobTemplateMetaMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertJobTemplateMetaOnDuplicateKeyUpdate(jobTemplatePO);
                    } else {
                      mapper.insertJobTemplateMeta(jobTemplatePO);
                    }
                  }));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.JOB_TEMPLATE, jobTemplateEntity.name());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteJobTemplate")
  public boolean deleteJobTemplate(NameIdentifier jobTemplateIdent) {
    return deleteJobTemplate(jobTemplateIdent, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one job-template aggregate and records its recoverable generation. */
  public boolean deleteJobTemplate(NameIdentifier identifier, long retentionMs) {
    return deleteJobTemplate(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes currently live terminal job runs first and their template root last using one
   * exact generation token.
   *
   * <p>Previously deleted job runs remain independent and are never adopted by this generation.
   */
  public boolean deleteJobTemplate(
      NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkJobTemplate(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                JobTemplateRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalake(mapper, metalakeId);
                  JobTemplatePO template =
                      mapper.lockLiveJobTemplate(metalakeId, identifier.name());
                  if (template == null) {
                    return;
                  }

                  List<JobRunRecoveryPO> jobs =
                      mapper.listLiveJobRunsForUpdate(template.jobTemplateId());
                  validateTerminalJobRuns(identifier, template, jobs);
                  long deletedAt =
                      chooseDeletedAt(
                          mapper,
                          template.jobTemplateId(),
                          metalakeId,
                          template.jobTemplateName(),
                          requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.JOB_TEMPLATE,
                              template.jobTemplateId(),
                              metalakeId,
                              null,
                              metalakeId,
                              template.jobTemplateName(),
                              template.currentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int jobCount =
                      mapper.softDeleteJobRuns(
                          template.jobTemplateId(), deletedAt, deletion.getDeletionId());
                  deletion.setAffectedRowCount(Math.addExact(1L, jobCount));
                  if (jobCount != jobs.size()
                      || mapper.countJobRunGeneration(
                              template.jobTemplateId(), deletedAt, deletion.getDeletionId())
                          != jobCount) {
                    throw incompleteGeneration(deletion.getDeletionId());
                  }

                  int rootCount =
                      mapper.softDeleteJobTemplate(
                          template.jobTemplateId(),
                          metalakeId,
                          template.jobTemplateName(),
                          template.currentVersion(),
                          deletedAt,
                          deletion.getDeletionId());
                  deleted.set(rootCount);
                  if (rootCount != 1
                      || mapper.countJobTemplateGeneration(
                              template.jobTemplateId(), deletedAt, deletion.getDeletionId())
                          != 1) {
                    throw new TombstoneChangedException(
                        "Job template changed while deleting %s", identifier);
                  }

                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.JOB_TEMPLATE.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /** Restores one exact job-template root-and-job-run deletion generation transactionally. */
  public JobTemplateEntity restoreJobTemplate(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkJobTemplate(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
    AtomicReference<JobTemplateEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                JobTemplateRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                  validateLatestDeletion(identifier, metalakeId, observed);

                  EntityDeletionPO actual =
                      SessionUtils.getWithoutCommit(
                          EntityDeletionMapper.class,
                          deletionMapper ->
                              deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                  if (isCompletedRestoreReplay(
                      identifier, metalakeId, observed, actual, restoreEtag)) {
                    restored.set(
                        loadIdempotentlyRestoredJobTemplate(identifier, metalakeId, actual));
                    return;
                  }
                  validateDeletionSnapshot(identifier, metalakeId, observed, actual);
                  if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
                    throw new TombstoneExpiredException(
                        "Deletion generation %s expired at %s",
                        observed.getDeletionId(), effectiveExpiresAt);
                  }

                  int claimed =
                      SessionUtils.getWithoutCommit(
                          EntityDeletionMapper.class,
                          deletionMapper ->
                              deletionMapper.compareAndSetState(
                                  actual.getDeletionId(),
                                  DeletionState.DELETED,
                                  actual.getRevision(),
                                  DeletionState.RESTORING,
                                  null,
                                  null));
                  if (claimed != 1) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }

                  JobTemplatePO generation =
                      mapper.selectJobTemplateGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  List<JobRunRecoveryPO> jobs =
                      mapper.listJobRunGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateJobTemplateGeneration(mapper, actual, generation, jobs);
                  if (!mapper
                      .listLiveExecutionIdConflictsForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                      .isEmpty()) {
                    throw new RecoveryConflictException(
                        RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
                        "A live job run occupies an execution ID retained by deletion generation %s",
                        actual.getDeletionId());
                  }

                  int restoredJobs = restoreJobRuns(mapper, actual, identifier, jobs.size());
                  if (restoredJobs != jobs.size()) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  if (restoreJobTemplateRoot(mapper, actual, generation, identifier) != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }

                  int receipt =
                      SessionUtils.getWithoutCommit(
                          EntityDeletionMapper.class,
                          deletionMapper ->
                              deletionMapper.compareAndSetState(
                                  actual.getDeletionId(),
                                  DeletionState.RESTORING,
                                  actual.getRevision() + 1,
                                  DeletionState.RESTORED,
                                  restoredAt,
                                  restoreEtag));
                  if (receipt != 1) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.JOB_TEMPLATE.name(),
                              identifier.toString(),
                              OperateType.RESTORE));
                  restored.set(getJobTemplateByIdentifier(identifier));
                }));
    return Objects.requireNonNull(restored.get(), "restored job template must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded job-template generations. */
  public int purgeExpiredJobTemplateDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.JOB_TEMPLATE, legacyTimeline, limit);
    if (expired.isEmpty()) {
      return 0;
    }

    long purgedAt = Instant.now().toEpochMilli();
    AtomicInteger purged = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () -> {
          for (EntityDeletionPO observed : expired) {
            EntityDeletionPO actual =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper -> mapper.selectEntityDeletion(observed.getDeletionId()));
            if (actual == null
                || actual.getState() != DeletionState.DELETED
                || !Objects.equals(actual.getRevision(), observed.getRevision())
                || actual.getDeletedAt() <= 0
                || actual.getDeletedAt() >= legacyTimeline) {
              continue;
            }
            int claimed =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper ->
                        mapper.compareAndSetState(
                            actual.getDeletionId(),
                            DeletionState.DELETED,
                            actual.getRevision(),
                            DeletionState.PURGING,
                            null,
                            null));
            if (claimed != 1) {
              continue;
            }
            SessionUtils.doWithoutCommit(
                JobTemplateRecoveryMapper.class,
                mapper -> {
                  JobTemplatePO generation =
                      mapper.selectJobTemplateGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  List<JobRunRecoveryPO> jobs =
                      mapper.listJobRunGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateJobTemplateGeneration(mapper, actual, generation, jobs);
                  if (mapper.hardDeleteJobRuns(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                      != jobs.size()) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  if (mapper.hardDeleteJobTemplate(
                          actual.getEntityId(),
                          actual.getParentId(),
                          actual.getEntityName(),
                          generation.currentVersion(),
                          actual.getDeletedAt(),
                          actual.getDeletionId())
                      != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                });
            int receipt =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper ->
                        mapper.compareAndSetState(
                            actual.getDeletionId(),
                            DeletionState.PURGING,
                            actual.getRevision() + 1,
                            DeletionState.PURGED,
                            purgedAt,
                            null));
            if (receipt != 1) {
              throw tombstoneChanged(actual.getDeletionId());
            }
            purged.incrementAndGet();
          }
        });
    return purged.get();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteJobTemplatesByLegacyTimeline")
  public int deleteJobTemplatesByLegacyTimeline(long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        JobTemplateMetaMapper.class,
        mapper -> mapper.deleteJobTemplateMetasByLegacyTimeline(legacyTimeline, limit));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateJobTemplate")
  public <E extends Entity & HasIdentifier> JobTemplateEntity updateJobTemplate(
      NameIdentifier jobTemplateIdent, Function<E, E> updater) throws IOException {
    JobTemplatePO oldJobTemplatePO = getJobTemplatePO(jobTemplateIdent);
    JobTemplateEntity oldJobTemplateEntity =
        JobTemplatePO.fromJobTemplatePO(oldJobTemplatePO, jobTemplateIdent.namespace());
    JobTemplateEntity newJobTemplateEntity =
        (JobTemplateEntity) updater.apply((E) oldJobTemplateEntity);
    Preconditions.checkArgument(
        Objects.equals(oldJobTemplateEntity.id(), newJobTemplateEntity.id()),
        "The updated job templated id: %s is not equal to the old one: %s, which is unexpected",
        newJobTemplateEntity.id(),
        oldJobTemplateEntity.id());

    JobTemplatePO.JobTemplatePOBuilder newBuilder =
        JobTemplatePO.builder().withMetalakeId(oldJobTemplatePO.metalakeId());
    JobTemplatePO newJobTemplatePO =
        JobTemplatePO.updateJobTemplatePO(oldJobTemplatePO, newJobTemplateEntity, newBuilder);

    int[] result = new int[1];
    try {
      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(oldJobTemplatePO.metalakeId()),
          () ->
              result[0] =
                  SessionUtils.getWithoutCommit(
                      JobTemplateMetaMapper.class,
                      mapper -> mapper.updateJobTemplateMeta(newJobTemplatePO, oldJobTemplatePO)));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(
          e, Entity.EntityType.JOB_TEMPLATE, oldJobTemplateEntity.name());
      throw e;
    }

    if (result[0] == 0) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.JOB_TEMPLATE.name().toLowerCase(Locale.ROOT),
          oldJobTemplateEntity.name());
    } else if (result[0] > 1) {
      throw new IOException(
          String.format(
              "Failed to update job template: %s, because more than one rows are updated: %d",
              oldJobTemplateEntity.name(), result[0]));
    } else {
      return newJobTemplateEntity;
    }
  }

  private static void lockLiveMetalake(JobTemplateRecoveryMapper mapper, long metalakeId) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          metalakeId);
    }
  }

  private static void lockLiveMetalakeForRestore(
      JobTemplateRecoveryMapper mapper, long metalakeId, NameIdentifier identifier) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.PARENT_CHANGED,
          "The parent metalake changed while restoring job template %s",
          identifier);
    }
  }

  private static void validateTerminalJobRuns(
      NameIdentifier identifier, JobTemplatePO template, List<JobRunRecoveryPO> jobs) {
    for (JobRunRecoveryPO job : jobs) {
      if (!Objects.equals(job.getJobTemplateId(), template.jobTemplateId())
          || !Objects.equals(job.getMetalakeId(), template.metalakeId())
          || job.getDeletedAt() == null
          || job.getDeletedAt() != 0
          || job.getDeletionId() != null) {
        throw new TombstoneChangedException(
            "Job-run membership changed while deleting job template %s", identifier);
      }
      if (!isTerminalStatus(job.getJobRunStatus())) {
        throw new InUseException(
            "Job template %s has active job run %s", identifier, job.getJobRunId());
      }
      if (job.getJobFinishedAt() == null || job.getJobFinishedAt() <= 0) {
        throw new TombstoneChangedException(
            "Terminal job run %s has no finished timestamp", job.getJobRunId());
      }
    }
  }

  private static boolean isTerminalStatus(@Nullable String status) {
    if (status == null) {
      return false;
    }
    try {
      JobHandle.Status parsed = JobHandle.Status.valueOf(status);
      return parsed == JobHandle.Status.CANCELLED
          || parsed == JobHandle.Status.FAILED
          || parsed == JobHandle.Status.SUCCEEDED;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static long chooseDeletedAt(
      JobTemplateRecoveryMapper mapper,
      long jobTemplateId,
      long metalakeId,
      String jobTemplateName,
      long requestedDeletedAt) {
    Long newestDeletedAt =
        mapper.selectNewestJobTemplateDeletedAt(jobTemplateId, metalakeId, jobTemplateName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static void validateLatestDeletion(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO observed) {
    EntityDeletionPO latest =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.selectLatestEntityDeletion(
                    Entity.EntityType.JOB_TEMPLATE.name(), metalakeId, identifier.name()));
    if (latest == null || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
          "Deletion generation %s is no longer latest for job template %s",
          observed.getDeletionId(),
          identifier);
    }
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier,
      long metalakeId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual) {
    boolean unchanged =
        actual != null
            && Objects.equals(actual.getDeletionId(), observed.getDeletionId())
            && Objects.equals(actual.getEntityType(), observed.getEntityType())
            && Objects.equals(actual.getEntityId(), observed.getEntityId())
            && Objects.equals(actual.getMetalakeId(), observed.getMetalakeId())
            && Objects.equals(actual.getCatalogId(), observed.getCatalogId())
            && Objects.equals(actual.getParentId(), observed.getParentId())
            && Objects.equals(actual.getEntityName(), observed.getEntityName())
            && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
            && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
            && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
            && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
            && Objects.equals(actual.getAffectedRowCount(), observed.getAffectedRowCount())
            && Objects.equals(actual.getState(), observed.getState())
            && Objects.equals(actual.getRevision(), observed.getRevision())
            && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
            && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
            && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt())
            && actual.getState() == DeletionState.DELETED
            && Entity.EntityType.JOB_TEMPLATE.name().equals(actual.getEntityType())
            && Objects.equals(actual.getMetalakeId(), metalakeId)
            && actual.getCatalogId() == null
            && Objects.equals(actual.getParentId(), metalakeId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      long metalakeId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual,
      String restoreEtag) {
    return actual != null
        && observed.getState() == DeletionState.DELETED
        && actual.getState() == DeletionState.RESTORED
        && Objects.equals(actual.getDeletionId(), observed.getDeletionId())
        && Objects.equals(actual.getEntityType(), observed.getEntityType())
        && Objects.equals(actual.getEntityId(), observed.getEntityId())
        && Objects.equals(actual.getMetalakeId(), observed.getMetalakeId())
        && Objects.equals(actual.getCatalogId(), observed.getCatalogId())
        && Objects.equals(actual.getParentId(), observed.getParentId())
        && Objects.equals(actual.getEntityName(), observed.getEntityName())
        && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
        && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
        && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
        && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
        && Objects.equals(actual.getAffectedRowCount(), observed.getAffectedRowCount())
        && Objects.equals(actual.getMetalakeId(), metalakeId)
        && actual.getCatalogId() == null
        && Objects.equals(actual.getParentId(), metalakeId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  private static JobTemplateEntity loadIdempotentlyRestoredJobTemplate(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    JobTemplateEntity live;
    try {
      live = getInstance().getJobTemplateByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Job-template ID %s is active under a different logical template",
          deletion.getEntityId());
    }
    return live;
  }

  private static void validateJobTemplateGeneration(
      JobTemplateRecoveryMapper mapper,
      EntityDeletionPO deletion,
      @Nullable JobTemplatePO generation,
      List<JobRunRecoveryPO> jobs) {
    if (generation == null
        || !Objects.equals(generation.jobTemplateId(), deletion.getEntityId())
        || !Objects.equals(generation.metalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.metalakeId(), deletion.getParentId())
        || !Objects.equals(generation.jobTemplateName(), deletion.getEntityName())
        || !Objects.equals(generation.currentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.deletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.deletionId(), deletion.getDeletionId())
        || deletion.getAffectedRowCount() == null
        || deletion.getAffectedRowCount() != 1L + jobs.size()
        || mapper.countJobTemplateGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != 1
        || mapper.countJobRunGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != jobs.size()) {
      throw incompleteGeneration(deletion.getDeletionId());
    }

    Set<Long> jobRunIds = new HashSet<>();
    Set<String> executionIds = new HashSet<>();
    for (JobRunRecoveryPO job : jobs) {
      if (!Objects.equals(job.getJobTemplateId(), deletion.getEntityId())
          || !Objects.equals(job.getMetalakeId(), deletion.getMetalakeId())
          || !Objects.equals(job.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(job.getDeletionId(), deletion.getDeletionId())
          || job.getJobRunId() == null
          || !jobRunIds.add(job.getJobRunId())
          || job.getJobExecutionId() == null
          || !executionIds.add(job.getJobExecutionId())
          || !isTerminalStatus(job.getJobRunStatus())
          || job.getJobFinishedAt() == null
          || job.getJobFinishedAt() <= 0) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }
  }

  private static int restoreJobRuns(
      JobTemplateRecoveryMapper mapper,
      EntityDeletionPO deletion,
      NameIdentifier identifier,
      int expectedCount) {
    try {
      int restored =
          mapper.restoreJobRuns(
              deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
      if (restored != expectedCount) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
      return restored;
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.JOB, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateExecutionId) {
        throw new RecoveryConflictException(
            duplicateExecutionId,
            RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
            "A live job run occupies an execution ID retained by %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static int restoreJobTemplateRoot(
      JobTemplateRecoveryMapper mapper,
      EntityDeletionPO deletion,
      JobTemplatePO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreJobTemplate(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.currentVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.JOB_TEMPLATE, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live job template already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private static RecoveryConflictException incompleteGeneration(String deletionId) {
    return new RecoveryConflictException(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        "Job-template deletion generation %s is incomplete and requires manual metadata repair",
        deletionId);
  }

  private JobTemplatePO getJobTemplatePO(NameIdentifier jobTemplateIdent) {
    String metalakeName = jobTemplateIdent.namespace().level(0);
    String jobTemplateName = jobTemplateIdent.name();

    JobTemplatePO jobTemplatePO =
        SessionUtils.getWithoutCommit(
            JobTemplateMetaMapper.class,
            mapper -> mapper.selectJobTemplatePOByMetalakeAndName(metalakeName, jobTemplateName));

    if (jobTemplatePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.JOB_TEMPLATE.name().toLowerCase(Locale.ROOT),
          jobTemplateName);
    }
    return jobTemplatePO;
  }

  public long getJobTemplateIdByMetalakeIdAndName(long metalakeId, String name) {
    Long jobTemplateId =
        SessionUtils.getWithoutCommit(
            JobTemplateMetaMapper.class,
            mapper -> mapper.selectJobTemplateIdByMetalakeAndName(metalakeId, name));

    if (jobTemplateId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.JOB_TEMPLATE.name().toLowerCase(Locale.ROOT),
          name);
    }
    return jobTemplateId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetJobTemplateByIdentifier")
  public List<JobTemplateEntity> batchGetJobTemplateByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    String metalakeName = firstIdent.namespace().level(0);
    List<String> jobTemplateNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        JobTemplateMetaMapper.class,
        mapper -> {
          List<JobTemplatePO> jobTemplatePOs =
              mapper.batchSelectJobTemplateByIdentifier(metalakeName, jobTemplateNames);
          return jobTemplatePOs.stream()
              .map(po -> JobTemplatePO.fromJobTemplatePO(po, firstIdent.namespace()))
              .collect(Collectors.toList());
        });
  }
}
