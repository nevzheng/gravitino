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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.ModelMetaMapper;
import org.apache.gravitino.storage.relational.mapper.ModelRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ModelPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelMetaService {

  private static final Logger LOG = LoggerFactory.getLogger(ModelMetaService.class);

  private static final ModelMetaService INSTANCE = new ModelMetaService();

  public static ModelMetaService getInstance() {
    return INSTANCE;
  }

  private ModelMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listModelsByNamespace")
  public List<ModelEntity> listModelsByNamespace(Namespace ns) {
    NamespaceUtil.checkModel(ns);

    List<ModelPO> modelPOs = listModelPOs(ns);
    return modelPOs.stream().map(m -> POConverters.fromModelPO(m, ns)).collect(Collectors.toList());
  }

  /**
   * Lists deleted model base rows under one live schema.
   *
   * @param namespace model namespace
   * @return deleted model generations, newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedModelsByNamespace")
  public List<ModelPO> listDeletedModelsByNamespace(Namespace namespace) {
    NamespaceUtil.checkModel(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        ModelRecoveryMapper.class, mapper -> mapper.listDeletedModels(schemaId));
  }

  /**
   * Lists live model base rows under one schema for recovery conflict detection.
   *
   * @param namespace model namespace
   * @return live model rows
   */
  public List<ModelPO> listLiveModelPOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkModel(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        ModelRecoveryMapper.class, mapper -> mapper.listLiveModels(schemaId));
  }

  /**
   * Lists globally live model rows matching candidate immutable IDs.
   *
   * @param modelIds candidate model IDs
   * @return matching live model rows
   */
  public List<ModelPO> listLiveModelsByIds(List<Long> modelIds) {
    if (modelIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        ModelRecoveryMapper.class, mapper -> mapper.listLiveModelsByIds(modelIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getModelByIdentifier")
  public ModelEntity getModelByIdentifier(NameIdentifier ident) {
    ModelPO modelPO = getModelPOByIdentifier(ident);
    return POConverters.fromModelPO(modelPO, ident.namespace());
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertModel")
  public void insertModel(ModelEntity modelEntity, boolean overwrite) throws IOException {
    NameIdentifierUtil.checkModel(modelEntity.nameIdentifier());

    try {
      ModelPO.Builder builder = ModelPO.builder();
      fillModelPOBuilderParentEntityId(builder, modelEntity.namespace());
      ModelPO po = POConverters.initializeModelPO(modelEntity, builder);

      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  ModelRecoveryMapper.class,
                  recoveryMapper -> lockLiveSchema(recoveryMapper, po.getSchemaId())),
          () ->
              SessionUtils.doWithoutCommit(
                  ModelRecoveryMapper.class,
                  recoveryMapper -> {
                    if (overwrite
                        && recoveryMapper.selectRecordedDeletedModelForUpdate(po.getModelId())
                            != null) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Model ID %s belongs to a recoverable deletion; use metadata restore",
                          po.getModelId());
                    }
                    SessionUtils.doWithoutCommit(
                        ModelMetaMapper.class,
                        mapper -> {
                          if (overwrite) {
                            mapper.insertModelMetaOnDuplicateKeyUpdate(po);
                          } else {
                            mapper.insertModelMeta(po);
                          }
                        });
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.MODEL, modelEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteModel")
  public boolean deleteModel(NameIdentifier ident) {
    return deleteModel(ident, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a model and records its durable deletion generation atomically.
   *
   * @param identifier model identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live model row was deleted
   */
  public boolean deleteModel(NameIdentifier identifier, long retentionMs) {
    return deleteModel(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a model using one timestamp and generation token for every affected row.
   *
   * @param identifier model identifier
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live model snapshot was deleted
   */
  public boolean deleteModel(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkModel(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long schemaId;
    try {
      schemaId =
          EntityIdService.getEntityId(
              NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    } catch (NoSuchEntityException e) {
      LOG.warn("Failed to delete model: {}", identifier, e);
      return false;
    }

    AtomicInteger deleteResult = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                ModelRecoveryMapper.class,
                mapper -> {
                  lockLiveSchema(mapper, schemaId);
                  ModelPO model = mapper.selectLiveModelForUpdate(schemaId, identifier.name());
                  if (model == null) {
                    return;
                  }

                  long deletionTimestamp =
                      chooseDeletedAt(
                          mapper, model.getModelId(), schemaId, model.getModelName(), deletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.MODEL,
                              model.getModelId(),
                              model.getMetalakeId(),
                              model.getCatalogId(),
                              model.getSchemaId(),
                              model.getModelName(),
                              model.getModelLatestVersion().longValue(),
                              deletionTimestamp,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int modelMeta =
                      mapper.softDeleteModelMeta(
                          model.getModelId(),
                          model.getSchemaId(),
                          model.getModelName(),
                          model.getModelLatestVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId());
                  deleteResult.set(modelMeta);
                  if (modelMeta != 1) {
                    throw new TombstoneChangedException(
                        "Model changed while deleting %s", identifier);
                  }

                  mapper.softDeleteModelVersions(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteModelAliases(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteOwnerRelations(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteSecurableObjects(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteTagRelations(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteStatistics(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeletePolicyRelations(
                      model.getModelId(), deletionTimestamp, deletion.getDeletionId());
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.MODEL.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleteResult.get() == 1;
  }

  /**
   * Restores one exact model deletion generation transactionally.
   *
   * @param identifier original model identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored model entity
   */
  public ModelEntity restoreModel(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkModel(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<ModelEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          SessionUtils.doWithoutCommit(
              ModelRecoveryMapper.class,
              mapper -> {
                Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
                if (!Objects.equals(schemaId, lockedSchemaId)) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.PARENT_CHANGED,
                      "The parent schema changed while restoring %s",
                      identifier);
                }
                EntityDeletionPO latest =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectLatestEntityDeletion(
                                Entity.EntityType.MODEL.name(), schemaId, identifier.name()));
                if (latest == null
                    || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                      "Deletion generation %s is no longer latest for model %s",
                      observed.getDeletionId(),
                      identifier);
                }

                EntityDeletionPO actual =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
                  restored.set(loadIdempotentlyRestoredModel(identifier, schemaId, actual));
                  return;
                }
                validateDeletionSnapshot(identifier, schemaId, observed, actual);
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

                ModelPO generation =
                    mapper.selectModelGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateModelGeneration(actual, generation);

                mapper.restoreModelVersions(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreModelAliases(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreOwnerRelations(
                    actual.getEntityId(),
                    actual.getDeletedAt(),
                    actual.getDeletionId(),
                    restoredAt);
                mapper.restoreSecurableObjects(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreTagRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreStatistics(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restorePolicyRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (restoreModelMeta(mapper, actual, generation, identifier) != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
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
                            Entity.EntityType.MODEL.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getModelByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored model must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded model deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredModelDeletions")
  public int purgeExpiredModelDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.MODEL, legacyTimeline, limit);
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
                ModelRecoveryMapper.class, mapper -> purgeModelDeletionGeneration(mapper, actual));
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
      baseMetricName = "deleteModelMetasByLegacyTimeline")
  public int deleteModelMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        ModelMetaMapper.class,
        mapper -> mapper.deleteModelMetasByLegacyTimeline(legacyTimeline, limit));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getModelIdBySchemaIdAndModelName")
  public Long getModelIdBySchemaIdAndModelName(Long schemaId, String modelName) {
    Long modelId =
        SessionUtils.getWithoutCommit(
            ModelMetaMapper.class,
            mapper -> mapper.selectModelIdBySchemaIdAndModelName(schemaId, modelName));

    if (modelId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.MODEL.name().toLowerCase(Locale.ROOT),
          modelName);
    }

    return modelId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getModelPOById")
  ModelPO getModelPOById(Long modelId) {
    ModelPO modelPO =
        SessionUtils.getWithoutCommit(
            ModelMetaMapper.class, mapper -> mapper.selectModelMetaByModelId(modelId));

    if (modelPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.MODEL.name().toLowerCase(Locale.ROOT),
          modelId.toString());
    }

    return modelPO;
  }

  private static void lockLiveSchema(ModelRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      ModelRecoveryMapper mapper,
      long modelId,
      long schemaId,
      String modelName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestModelDeletedAt(modelId, schemaId, modelName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier,
      long schemaId,
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
            && Objects.equals(actual.getState(), observed.getState())
            && Objects.equals(actual.getRevision(), observed.getRevision())
            && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
            && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
            && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt())
            && actual.getState() == DeletionState.DELETED
            && Entity.EntityType.MODEL.name().equals(actual.getEntityType())
            && Objects.equals(actual.getParentId(), schemaId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      long schemaId,
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
        && Objects.equals(actual.getParentId(), schemaId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  private static ModelEntity loadIdempotentlyRestoredModel(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    ModelEntity liveModel;
    try {
      liveModel = getInstance().getModelByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveModel.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Model ID %s is active under a different logical model",
          deletion.getEntityId());
    }
    return liveModel;
  }

  private static void validateModelGeneration(
      EntityDeletionPO deletion, @Nullable ModelPO generation) {
    if (generation == null
        || !Objects.equals(generation.getModelId(), deletion.getEntityId())
        || !Objects.equals(generation.getSchemaId(), deletion.getParentId())
        || !Objects.equals(generation.getModelName(), deletion.getEntityName())
        || !Objects.equals(
            generation.getModelLatestVersion().longValue(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static int restoreModelMeta(
      ModelRecoveryMapper mapper,
      EntityDeletionPO deletion,
      ModelPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreModelMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.getModelLatestVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.MODEL, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live model already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void purgeModelDeletionGeneration(
      ModelRecoveryMapper mapper, EntityDeletionPO deletion) {
    ModelPO generation =
        mapper.selectModelGeneration(
            deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    validateModelGeneration(deletion, generation);
    mapper.hardDeleteOwnerRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteSecurableObjects(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteTagRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteStatistics(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeletePolicyRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteModelAliases(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteModelVersions(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    if (mapper.hardDeleteModelMeta(
            deletion.getEntityId(),
            deletion.getParentId(),
            deletion.getEntityName(),
            generation.getModelLatestVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private void fillModelPOBuilderParentEntityId(ModelPO.Builder builder, Namespace ns) {
    NamespaceUtil.checkModel(ns);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(NameIdentifier.of(ns.levels()), Entity.EntityType.SCHEMA);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.namespaceIds()[1]);
    builder.withSchemaId(namespacedEntityId.entityId());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getModelPOByIdentifier")
  ModelPO getModelPOByIdentifier(NameIdentifier ident) {
    NameIdentifierUtil.checkModel(ident);

    return modelPOFetcher().apply(ident);
  }

  private List<ModelPO> listModelPOs(Namespace namespace) {
    return modelListFetcher().apply(namespace);
  }

  private List<ModelPO> listModelPOsBySchemaId(Namespace namespace) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        ModelMetaMapper.class, mapper -> mapper.listModelPOsBySchemaId(schemaId));
  }

  private List<ModelPO> listModelPOsByFullQualifiedName(Namespace namespace) {
    String[] namespaceLevels = namespace.levels();
    List<ModelPO> modelPOs =
        SessionUtils.getWithoutCommit(
            ModelMetaMapper.class,
            mapper ->
                mapper.listModelPOsByFullQualifiedName(
                    namespaceLevels[0], namespaceLevels[1], namespaceLevels[2]));
    if (modelPOs.isEmpty() || modelPOs.get(0).getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          namespaceLevels[2]);
    }
    return modelPOs.stream().filter(po -> po.getModelId() != null).collect(Collectors.toList());
  }

  private ModelPO getModelPOBySchemaId(NameIdentifier identifier) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);

    ModelPO modelPO =
        SessionUtils.getWithoutCommit(
            ModelMetaMapper.class,
            mapper -> mapper.selectModelMetaBySchemaIdAndModelName(schemaId, identifier.name()));

    if (modelPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.MODEL.name().toLowerCase(Locale.ROOT),
          identifier.toString());
    }
    return modelPO;
  }

  private ModelPO getModelPOByFullQualifiedName(NameIdentifier identifier) {
    String[] namespaceLevels = identifier.namespace().levels();
    ModelPO modelPO =
        SessionUtils.getWithoutCommit(
            ModelMetaMapper.class,
            mapper ->
                mapper.selectModelByFullQualifiedName(
                    namespaceLevels[0], namespaceLevels[1], namespaceLevels[2], identifier.name()));

    if (modelPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.MODEL.name().toLowerCase(Locale.ROOT),
          identifier.toString());
    }

    if (modelPO.getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          namespaceLevels[2]);
    }

    if (modelPO.getModelId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.MODEL.name().toLowerCase(Locale.ROOT),
          identifier.toString());
    }
    return modelPO;
  }

  private Function<Namespace, List<ModelPO>> modelListFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::listModelPOsBySchemaId
        : this::listModelPOsByFullQualifiedName;
  }

  private Function<NameIdentifier, ModelPO> modelPOFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::getModelPOBySchemaId
        : this::getModelPOByFullQualifiedName;
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateModel")
  public <E extends Entity & HasIdentifier> ModelEntity updateModel(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkModel(identifier);

    ModelPO oldModelPO = getModelPOByIdentifier(identifier);
    ModelEntity oldModelEntity = POConverters.fromModelPO(oldModelPO, identifier.namespace());
    ModelEntity newEntity = (ModelEntity) updater.apply((E) oldModelEntity);
    Preconditions.checkArgument(
        Objects.equals(oldModelEntity.id(), newEntity.id()),
        "The updated model entity id: %s should be same with the table entity id before: %s",
        newEntity.id(),
        oldModelEntity.id());

    String metalakeName = identifier.namespace().level(0);
    String catalogName = identifier.namespace().level(1);
    String schemaName = identifier.namespace().level(2);
    String oldFullName =
        NameIdentifierUtil.ofModel(metalakeName, catalogName, schemaName, oldModelEntity.name())
            .toString();
    boolean isRenamed = !Objects.equals(oldModelEntity.name(), newEntity.name());

    AtomicInteger updateResult = new AtomicInteger(0);
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  ModelRecoveryMapper.class,
                  recoveryMapper -> lockLiveSchema(recoveryMapper, oldModelPO.getSchemaId())),
          () ->
              updateResult.set(
                  SessionUtils.getWithoutCommit(
                      ModelMetaMapper.class,
                      mapper ->
                          mapper.updateModelMeta(
                              POConverters.updateModelPO(oldModelPO, newEntity), oldModelPO))),
          () -> {
            if (isRenamed && updateResult.get() > 0) {
              SessionUtils.doWithoutCommit(
                  EntityChangeLogMapper.class,
                  mapper ->
                      mapper.insertEntityChange(
                          metalakeName,
                          Entity.EntityType.MODEL.name(),
                          oldFullName,
                          OperateType.ALTER));
            }
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.MODEL, newEntity.nameIdentifier().toString());
      throw re;
    }

    if (updateResult.get() > 0) {
      return newEntity;
    } else {
      throw new IOException("Failed to update the entity: " + identifier);
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetModelByIdentifier")
  public List<ModelEntity> batchGetModelByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    NameIdentifier schemaIdent = NameIdentifierUtil.getSchemaIdentifier(firstIdent);
    List<String> modelNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        ModelMetaMapper.class,
        mapper -> {
          List<ModelPO> modelPOs =
              mapper.batchSelectModelByIdentifier(
                  schemaIdent.namespace().level(0),
                  schemaIdent.namespace().level(1),
                  schemaIdent.name(),
                  modelNames);
          return POConverters.fromModelPOs(modelPOs, firstIdent.namespace());
        });
  }
}
