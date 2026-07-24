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
import static org.apache.gravitino.storage.relational.po.FunctionPO.buildFunctionPO;
import static org.apache.gravitino.storage.relational.po.FunctionPO.fromFunctionPO;
import static org.apache.gravitino.storage.relational.po.FunctionPO.initializeFunctionPO;

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
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionMetaMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionVersionMetaMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.FunctionMaxVersionPO;
import org.apache.gravitino.storage.relational.po.FunctionPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FunctionMetaService {
  private static final Logger LOG = LoggerFactory.getLogger(FunctionMetaService.class);
  private static final FunctionMetaService INSTANCE = new FunctionMetaService();
  private BasePOStorageOps<FunctionPO, FunctionMetaMapper> ops;

  public static FunctionMetaService getInstance() {
    return INSTANCE;
  }

  private FunctionMetaService() {
    this.ops = new HierarchicalConversionPOStorageOps<>(new FunctionPOStorageOps());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listFunctionsByNamespace")
  public List<FunctionEntity> listFunctionsByNamespace(Namespace ns) {
    NamespaceUtil.checkFunction(ns);

    List<FunctionPO> functionPOs = listFunctionPOs(ns);
    return functionPOs.stream().map(f -> fromFunctionPO(f, ns)).collect(Collectors.toList());
  }

  /**
   * Lists deleted function base rows under one live schema.
   *
   * @param namespace function namespace
   * @return deleted function generations, newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedFunctionsByNamespace")
  public List<FunctionPO> listDeletedFunctionsByNamespace(Namespace namespace) {
    NamespaceUtil.checkFunction(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        FunctionRecoveryMapper.class, mapper -> mapper.listDeletedFunctions(schemaId));
  }

  /**
   * Lists live function base rows under one schema for recovery conflict detection.
   *
   * @param namespace function namespace
   * @return live function base rows
   */
  public List<FunctionPO> listLiveFunctionPOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkFunction(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        FunctionRecoveryMapper.class, mapper -> mapper.listLiveFunctions(schemaId));
  }

  /**
   * Lists globally live function rows matching candidate immutable IDs.
   *
   * @param functionIds candidate function IDs
   * @return matching live function rows
   */
  public List<FunctionPO> listLiveFunctionsByIds(List<Long> functionIds) {
    if (functionIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        FunctionRecoveryMapper.class, mapper -> mapper.listLiveFunctionsByIds(functionIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFunctionByIdentifier")
  public FunctionEntity getFunctionByIdentifier(NameIdentifier ident) {
    FunctionPO functionPO = getFunctionPOByIdentifier(ident);
    return fromFunctionPO(functionPO, ident.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFunctionIdBySchemaIdAndFunctionName")
  public Long getFunctionIdBySchemaIdAndFunctionName(Long schemaId, String functionName) {
    FunctionPO functionPO =
        SessionUtils.getWithoutCommit(
            FunctionMetaMapper.class, mapper -> ops.getPO(mapper, schemaId, functionName));

    if (functionPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FUNCTION.name().toLowerCase(Locale.ROOT),
          functionName);
    }
    return functionPO.functionId();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertFunction")
  public void insertFunction(FunctionEntity functionEntity, boolean overwrite) throws IOException {
    NameIdentifierUtil.checkFunction(functionEntity.nameIdentifier());

    FunctionPO.FunctionPOBuilder builder = FunctionPO.builder();
    try {
      fillFunctionPOBuilderParentEntityId(builder, functionEntity.namespace());
      FunctionPO po = initializeFunctionPO(functionEntity, builder);

      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionRecoveryMapper.class,
                  recoveryMapper -> lockLiveSchema(recoveryMapper, po.schemaId())),
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionRecoveryMapper.class,
                  recoveryMapper -> {
                    if (overwrite
                        && recoveryMapper.selectRecordedDeletedFunctionForUpdate(po.functionId())
                            != null) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Function ID %s belongs to a recoverable deletion; use metadata restore",
                          po.functionId());
                    }
                    SessionUtils.doWithoutCommit(
                        FunctionMetaMapper.class, mapper -> ops.insertPO(mapper, po, overwrite));
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionVersionMetaMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertFunctionVersionMetaOnDuplicateKeyUpdate(po.functionVersionPO());
                    } else {
                      mapper.insertFunctionVersionMeta(po.functionVersionPO());
                    }
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.FUNCTION, functionEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteFunction")
  public boolean deleteFunction(NameIdentifier identifier) {
    return deleteFunction(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a function and records its durable deletion generation atomically.
   *
   * @param identifier function identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live function row was deleted
   */
  public boolean deleteFunction(NameIdentifier identifier, long retentionMs) {
    return deleteFunction(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a function using one timestamp and generation token for every affected row.
   *
   * @param identifier function identifier
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live function snapshot was deleted
   */
  public boolean deleteFunction(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkFunction(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicInteger deleteResult = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                FunctionRecoveryMapper.class,
                mapper -> {
                  lockLiveSchema(mapper, schemaId);
                  FunctionPO function =
                      mapper.selectLiveFunctionForUpdate(schemaId, identifier.name());
                  if (function == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.FUNCTION.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  if (!Objects.equals(
                      function.functionCurrentVersion(), function.functionLatestVersion())) {
                    throw new TombstoneChangedException(
                        "Function versions changed while deleting %s", identifier);
                  }

                  long deletionTimestamp =
                      chooseDeletedAt(
                          mapper,
                          function.functionId(),
                          schemaId,
                          function.functionName(),
                          deletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.FUNCTION,
                              function.functionId(),
                              function.metalakeId(),
                              function.catalogId(),
                              function.schemaId(),
                              function.functionName(),
                              function.functionCurrentVersion().longValue(),
                              deletionTimestamp,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int functionMeta =
                      mapper.softDeleteFunctionMeta(
                          function.functionId(),
                          function.schemaId(),
                          function.functionName(),
                          function.functionCurrentVersion(),
                          function.functionLatestVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId());
                  deleteResult.set(functionMeta);
                  if (functionMeta != 1) {
                    throw new TombstoneChangedException(
                        "Function changed while deleting %s", identifier);
                  }

                  int versions =
                      mapper.softDeleteFunctionVersions(
                          function.functionId(), deletionTimestamp, deletion.getDeletionId());
                  if (versions < 1) {
                    throw new TombstoneChangedException(
                        "Function versions changed while deleting %s", identifier);
                  }
                  mapper.softDeleteOwnerRelations(
                      function.functionId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteSecurableObjects(
                      function.functionId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteTagRelations(
                      function.functionId(), deletionTimestamp, deletion.getDeletionId());
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.FUNCTION.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleteResult.get() == 1;
  }

  /**
   * Restores one exact function deletion generation transactionally.
   *
   * @param identifier original function identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored function entity
   */
  public FunctionEntity restoreFunction(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkFunction(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<FunctionEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          SessionUtils.doWithoutCommit(
              FunctionRecoveryMapper.class,
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
                                Entity.EntityType.FUNCTION.name(), schemaId, identifier.name()));
                if (latest == null
                    || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                      "Deletion generation %s is no longer latest for function %s",
                      observed.getDeletionId(),
                      identifier);
                }

                EntityDeletionPO actual =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
                  restored.set(loadIdempotentlyRestoredFunction(identifier, schemaId, actual));
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

                FunctionPO generation =
                    mapper.selectFunctionGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateFunctionGeneration(actual, generation);
                if (mapper.countCurrentVersionGeneration(
                        actual.getEntityId(),
                        actual.getEntityVersion(),
                        actual.getDeletedAt(),
                        actual.getDeletionId())
                    != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }

                mapper.restoreOwnerRelations(
                    actual.getEntityId(),
                    actual.getDeletedAt(),
                    actual.getDeletionId(),
                    restoredAt);
                mapper.restoreSecurableObjects(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreTagRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (mapper.restoreFunctionVersions(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                    < 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }
                if (restoreFunctionMeta(mapper, actual, generation, identifier) != 1) {
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
                            Entity.EntityType.FUNCTION.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getFunctionByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored function must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded function deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredFunctionDeletions")
  public int purgeExpiredFunctionDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.FUNCTION, legacyTimeline, limit);
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
                FunctionRecoveryMapper.class,
                mapper -> purgeFunctionDeletionGeneration(mapper, actual));
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
      baseMetricName = "deleteFunctionMetasByLegacyTimeline")
  public int deleteFunctionMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    int functionVersionDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            FunctionVersionMetaMapper.class,
            mapper -> mapper.deleteFunctionVersionMetasByLegacyTimeline(legacyTimeline, limit));

    int functionMetaDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            FunctionMetaMapper.class,
            mapper -> mapper.deleteFunctionMetasByLegacyTimeline(legacyTimeline, limit));

    return functionVersionDeletedCount + functionMetaDeletedCount;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteFunctionVersionsByRetentionCount")
  public int deleteFunctionVersionsByRetentionCount(Long versionRetentionCount, int limit) {
    List<FunctionMaxVersionPO> functionCurVersions =
        SessionUtils.getWithoutCommit(
            FunctionVersionMetaMapper.class,
            mapper -> mapper.selectFunctionVersionsByRetentionCount(versionRetentionCount));

    int totalDeletedCount = 0;
    for (FunctionMaxVersionPO functionCurVersion : functionCurVersions) {
      long versionRetentionLine = functionCurVersion.version() - versionRetentionCount;
      int deletedCount =
          SessionUtils.doWithCommitAndFetchResult(
              FunctionVersionMetaMapper.class,
              mapper ->
                  mapper.softDeleteFunctionVersionsByRetentionLine(
                      functionCurVersion.functionId(), versionRetentionLine, limit));
      totalDeletedCount += deletedCount;

      LOG.info(
          "Soft delete functionVersions count: {} which versions are older than or equal to"
              + " versionRetentionLine: {}, the current functionId and version is: <{}, {}>.",
          deletedCount,
          versionRetentionLine,
          functionCurVersion.functionId(),
          functionCurVersion.version());
    }
    return totalDeletedCount;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFunctionPOByIdentifier")
  FunctionPO getFunctionPOByIdentifier(NameIdentifier ident) {
    NameIdentifierUtil.checkFunction(ident);
    FunctionPO functionPO =
        SessionUtils.getWithoutCommit(
            FunctionMetaMapper.class,
            mapper -> POStorageReadRouting.getPO(mapper, ident, ops, Entity.EntityType.FUNCTION));

    if (functionPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FUNCTION.name().toLowerCase(Locale.ROOT),
          ident.name());
    }
    return functionPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateFunction")
  public <E extends Entity & HasIdentifier> FunctionEntity updateFunction(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    FunctionPO oldFunctionPO = getFunctionPOByIdentifier(identifier);
    FunctionEntity oldFunctionEntity = fromFunctionPO(oldFunctionPO, identifier.namespace());
    FunctionEntity newEntity = (FunctionEntity) updater.apply((E) oldFunctionEntity);
    Preconditions.checkArgument(
        Objects.equals(oldFunctionEntity.id(), newEntity.id()),
        "The updated function entity id: %s should be same with the entity id before: %s",
        newEntity.id(),
        oldFunctionEntity.id());

    try {
      FunctionPO newFunctionPO = updateFunctionPO(oldFunctionPO, newEntity);
      // Insert a new version and update function meta
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionRecoveryMapper.class,
                  recoveryMapper -> lockLiveSchema(recoveryMapper, oldFunctionPO.schemaId())),
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionVersionMetaMapper.class,
                  mapper -> mapper.insertFunctionVersionMeta(newFunctionPO.functionVersionPO())),
          () ->
              SessionUtils.doWithoutCommit(
                  FunctionMetaMapper.class,
                  mapper -> {
                    if (ops.updatePO(mapper, newFunctionPO, oldFunctionPO) != 1) {
                      throw new TombstoneChangedException(
                          "Function changed while updating %s", identifier);
                    }
                  }));

      return newEntity;
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.FUNCTION, newEntity.nameIdentifier().toString());
      throw re;
    }
  }

  public BasePOStorageOps<FunctionPO, FunctionMetaMapper> ops() {
    return ops;
  }

  private static void lockLiveSchema(FunctionRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      FunctionRecoveryMapper mapper,
      long functionId,
      long schemaId,
      String functionName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestFunctionDeletedAt(functionId, schemaId, functionName);
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
            && Entity.EntityType.FUNCTION.name().equals(actual.getEntityType())
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

  private static FunctionEntity loadIdempotentlyRestoredFunction(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    FunctionEntity liveFunction;
    try {
      liveFunction = getInstance().getFunctionByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveFunction.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Function ID %s is active under a different logical function",
          deletion.getEntityId());
    }
    return liveFunction;
  }

  private static void validateFunctionGeneration(
      EntityDeletionPO deletion, @Nullable FunctionPO generation) {
    if (generation == null
        || !Objects.equals(generation.functionId(), deletion.getEntityId())
        || !Objects.equals(generation.schemaId(), deletion.getParentId())
        || !Objects.equals(generation.functionName(), deletion.getEntityName())
        || !Objects.equals(
            generation.functionCurrentVersion().longValue(), deletion.getEntityVersion())
        || !Objects.equals(
            generation.functionLatestVersion().longValue(), deletion.getEntityVersion())
        || !Objects.equals(generation.deletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.deletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static int restoreFunctionMeta(
      FunctionRecoveryMapper mapper,
      EntityDeletionPO deletion,
      FunctionPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreFunctionMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.functionCurrentVersion(),
          generation.functionLatestVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.FUNCTION, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live function already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void purgeFunctionDeletionGeneration(
      FunctionRecoveryMapper mapper, EntityDeletionPO deletion) {
    FunctionPO generation =
        mapper.selectFunctionGeneration(
            deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    validateFunctionGeneration(deletion, generation);
    mapper.hardDeleteOwnerRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteSecurableObjects(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteTagRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteFunctionVersions(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    if (mapper.hardDeleteFunctionMeta(
            deletion.getEntityId(),
            deletion.getParentId(),
            deletion.getEntityName(),
            generation.functionCurrentVersion(),
            generation.functionLatestVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private List<FunctionPO> listFunctionPOs(Namespace namespace) {
    return SessionUtils.getWithoutCommit(
        FunctionMetaMapper.class,
        mapper -> POStorageReadRouting.listPOs(mapper, namespace, ops, Entity.EntityType.FUNCTION));
  }

  private void fillFunctionPOBuilderParentEntityId(
      FunctionPO.FunctionPOBuilder builder, Namespace ns) {
    NamespaceUtil.checkFunction(ns);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(NameIdentifier.of(ns.levels()), Entity.EntityType.SCHEMA);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.namespaceIds()[1]);
    builder.withSchemaId(namespacedEntityId.entityId());
  }

  private FunctionPO updateFunctionPO(FunctionPO oldFunctionPO, FunctionEntity newFunction) {
    Integer newVersion = oldFunctionPO.functionLatestVersion() + 1;
    FunctionPO.FunctionPOBuilder builder =
        FunctionPO.builder()
            .withMetalakeId(oldFunctionPO.metalakeId())
            .withCatalogId(oldFunctionPO.catalogId())
            .withSchemaId(oldFunctionPO.schemaId())
            .withFunctionLatestVersion(newVersion)
            .withFunctionCurrentVersion(newVersion);
    return buildFunctionPO(newFunction, builder, newVersion);
  }
}
