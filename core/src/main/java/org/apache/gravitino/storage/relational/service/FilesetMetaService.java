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
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetMetaMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetVersionMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.FilesetMaxVersionPO;
import org.apache.gravitino.storage.relational.po.FilesetPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The service class for fileset metadata and version info. It provides the basic database
 * operations for fileset and version info.
 */
public class FilesetMetaService {
  private static final FilesetMetaService INSTANCE = new FilesetMetaService();

  private static final Logger LOG = LoggerFactory.getLogger(FilesetMetaService.class);

  public static FilesetMetaService getInstance() {
    return INSTANCE;
  }

  private FilesetMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFilesetPOBySchemaIdAndName")
  public FilesetPO getFilesetPOBySchemaIdAndName(Long schemaId, String filesetName) {
    FilesetPO filesetPO =
        SessionUtils.getWithoutCommit(
            FilesetMetaMapper.class,
            mapper -> mapper.selectFilesetMetaBySchemaIdAndName(schemaId, filesetName));

    if (filesetPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FILESET.name().toLowerCase(),
          filesetName);
    }
    return filesetPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFilesetIdBySchemaIdAndName")
  public Long getFilesetIdBySchemaIdAndName(Long schemaId, String filesetName) {
    Long filesetId =
        SessionUtils.getWithoutCommit(
            FilesetMetaMapper.class,
            mapper -> mapper.selectFilesetIdBySchemaIdAndName(schemaId, filesetName));

    if (filesetId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FILESET.name().toLowerCase(),
          filesetName);
    }
    return filesetId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getFilesetByIdentifier")
  public FilesetEntity getFilesetByIdentifier(NameIdentifier identifier) {
    FilesetPO filesetPO = getFilesetPOByIdentifier(identifier);
    return POConverters.fromFilesetPO(filesetPO, identifier.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listFilesetsByNamespace")
  public List<FilesetEntity> listFilesetsByNamespace(Namespace namespace) {
    NamespaceUtil.checkFileset(namespace);

    List<FilesetPO> filesetPOs = listFilesetPOs(namespace);
    return POConverters.fromFilesetPOs(filesetPOs, namespace);
  }

  /**
   * Lists deleted fileset base rows under one live schema.
   *
   * @param namespace fileset namespace
   * @return deleted fileset generations, newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedFilesetsByNamespace")
  public List<FilesetPO> listDeletedFilesetsByNamespace(Namespace namespace) {
    NamespaceUtil.checkFileset(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        FilesetRecoveryMapper.class, mapper -> mapper.listDeletedFilesets(schemaId));
  }

  /**
   * Lists live fileset base rows under one schema for recovery conflict detection.
   *
   * @param namespace fileset namespace
   * @return live fileset base rows
   */
  public List<FilesetPO> listLiveFilesetPOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkFileset(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        FilesetRecoveryMapper.class, mapper -> mapper.listLiveFilesets(schemaId));
  }

  /**
   * Lists globally live fileset rows matching candidate immutable IDs.
   *
   * @param filesetIds candidate fileset IDs
   * @return matching live fileset rows
   */
  public List<FilesetPO> listLiveFilesetsByIds(List<Long> filesetIds) {
    if (filesetIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        FilesetRecoveryMapper.class, mapper -> mapper.listLiveFilesetsByIds(filesetIds));
  }

  private List<FilesetPO> listFilesetPOs(Namespace namespace) {
    return filesetListFetcher().apply(namespace);
  }

  private List<FilesetPO> listFilesetPOsBySchemaId(Namespace namespace) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        FilesetMetaMapper.class, mapper -> mapper.listFilesetPOsBySchemaId(schemaId));
  }

  private List<FilesetPO> listFilesetPOsByFullQualifiedName(Namespace namespace) {
    String[] namespaceLevels = namespace.levels();
    List<FilesetPO> filesetPOs =
        SessionUtils.getWithoutCommit(
            FilesetMetaMapper.class,
            mapper ->
                mapper.listFilesetPOsByFullQualifiedName(
                    namespaceLevels[0], namespaceLevels[1], namespaceLevels[2]));
    if (filesetPOs.isEmpty() || filesetPOs.get(0).getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          namespaceLevels[2]);
    }
    return filesetPOs.stream().filter(po -> po.getFilesetId() != null).collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertFileset")
  public void insertFileset(FilesetEntity filesetEntity, boolean overwrite) throws IOException {
    try {
      NameIdentifierUtil.checkFileset(filesetEntity.nameIdentifier());

      FilesetPO.Builder builder = FilesetPO.builder();
      fillFilesetPOBuilderParentEntityId(builder, filesetEntity.namespace());

      FilesetPO po = POConverters.initializeFilesetPOWithVersion(filesetEntity, builder);

      // insert both fileset meta table and version table
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  FilesetRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, po.getSchemaId());
                    if (overwrite
                        && recoveryMapper.selectRecordedDeletedFilesetForUpdate(po.getFilesetId())
                            != null) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Fileset ID %s belongs to a recoverable deletion; use metadata restore",
                          po.getFilesetId());
                    }
                    SessionUtils.doWithoutCommit(
                        FilesetMetaMapper.class,
                        mapper -> {
                          if (overwrite) {
                            mapper.insertFilesetMetaOnDuplicateKeyUpdate(po);
                          } else {
                            mapper.insertFilesetMeta(po);
                          }
                        });
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  FilesetVersionMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertFilesetVersionsOnDuplicateKeyUpdate(po.getFilesetVersionPOs());
                    } else {
                      mapper.insertFilesetVersions(po.getFilesetVersionPOs());
                    }
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.FILESET, filesetEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateFileset")
  public <E extends Entity & HasIdentifier> FilesetEntity updateFileset(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkFileset(identifier);
    Objects.requireNonNull(updater, "updater must not be null");
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicReference<FilesetEntity> candidate = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  FilesetRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, schemaId);
                    FilesetPO locked =
                        recoveryMapper.selectLiveFilesetForUpdate(schemaId, identifier.name());
                    if (locked == null) {
                      throw new NoSuchEntityException(
                          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                          Entity.EntityType.FILESET.name().toLowerCase(Locale.ROOT),
                          identifier.name());
                    }

                    FilesetPO oldFilesetPO =
                        SessionUtils.getWithoutCommit(
                            FilesetMetaMapper.class,
                            mapper ->
                                mapper.selectFilesetMetaBySchemaIdAndName(
                                    schemaId, identifier.name()));
                    if (oldFilesetPO == null
                        || !Objects.equals(oldFilesetPO.getFilesetId(), locked.getFilesetId())) {
                      throw new TombstoneChangedException(
                          "Fileset changed while updating %s", identifier);
                    }
                    FilesetEntity oldFilesetEntity =
                        POConverters.fromFilesetPO(oldFilesetPO, identifier.namespace());
                    FilesetEntity newEntity = (FilesetEntity) updater.apply((E) oldFilesetEntity);
                    candidate.set(newEntity);
                    Preconditions.checkArgument(
                        Objects.equals(oldFilesetEntity.id(), newEntity.id()),
                        "The updated fileset entity id: %s should be same with the entity id before: %s",
                        newEntity.id(),
                        oldFilesetEntity.id());

                    boolean updateVersion =
                        POConverters.checkFilesetVersionNeedUpdate(
                            oldFilesetPO.getFilesetVersionPOs(), newEntity);
                    FilesetPO newFilesetPO =
                        POConverters.updateFilesetPOWithVersion(
                            oldFilesetPO, newEntity, updateVersion);
                    if (updateVersion) {
                      SessionUtils.doWithoutCommit(
                          FilesetVersionMapper.class,
                          mapper ->
                              mapper.insertFilesetVersions(newFilesetPO.getFilesetVersionPOs()));
                    }
                    int updated =
                        SessionUtils.getWithoutCommit(
                            FilesetMetaMapper.class,
                            mapper -> mapper.updateFilesetMeta(newFilesetPO, oldFilesetPO));
                    if (updated != 1) {
                      throw new TombstoneChangedException(
                          "Fileset changed while updating %s", identifier);
                    }

                    if (!Objects.equals(oldFilesetEntity.name(), newEntity.name())) {
                      String oldFullName =
                          NameIdentifierUtil.ofFileset(
                                  identifier.namespace().level(0),
                                  identifier.namespace().level(1),
                                  identifier.namespace().level(2),
                                  oldFilesetEntity.name())
                              .toString();
                      SessionUtils.doWithoutCommit(
                          EntityChangeLogMapper.class,
                          mapper ->
                              mapper.insertEntityChange(
                                  identifier.namespace().level(0),
                                  Entity.EntityType.FILESET.name(),
                                  oldFullName,
                                  OperateType.ALTER));
                    }
                  }));
    } catch (RuntimeException re) {
      FilesetEntity newEntity = candidate.get();
      ExceptionUtils.checkSQLException(
          re,
          Entity.EntityType.FILESET,
          newEntity == null ? identifier.toString() : newEntity.nameIdentifier().toString());
      throw re;
    }
    return Objects.requireNonNull(candidate.get(), "updated fileset must not be null");
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteFileset")
  public boolean deleteFileset(NameIdentifier identifier) {
    return deleteFileset(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a fileset and records its durable deletion generation atomically.
   *
   * @param identifier fileset identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live fileset row was deleted
   */
  public boolean deleteFileset(NameIdentifier identifier, long retentionMs) {
    return deleteFileset(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a fileset using one timestamp and generation token for every affected row.
   *
   * @param identifier fileset identifier
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live fileset snapshot was deleted
   */
  public boolean deleteFileset(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkFileset(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicInteger deleteResult = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                FilesetRecoveryMapper.class,
                mapper -> {
                  lockLiveSchema(mapper, schemaId);
                  FilesetPO fileset =
                      mapper.selectLiveFilesetForUpdate(schemaId, identifier.name());
                  if (fileset == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.FILESET.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  long deletionTimestamp =
                      chooseDeletedAt(
                          mapper,
                          fileset.getFilesetId(),
                          schemaId,
                          fileset.getFilesetName(),
                          deletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.FILESET,
                              fileset.getFilesetId(),
                              fileset.getMetalakeId(),
                              fileset.getCatalogId(),
                              fileset.getSchemaId(),
                              fileset.getFilesetName(),
                              fileset.getCurrentVersion(),
                              deletionTimestamp,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int filesetMeta =
                      mapper.softDeleteFilesetMeta(
                          fileset.getFilesetId(),
                          fileset.getSchemaId(),
                          fileset.getFilesetName(),
                          fileset.getCurrentVersion(),
                          fileset.getLastVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId());
                  deleteResult.set(filesetMeta);
                  if (filesetMeta != 1) {
                    throw new TombstoneChangedException(
                        "Fileset changed while deleting %s", identifier);
                  }

                  if (mapper.softDeleteFilesetVersions(
                          fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId())
                      < 1) {
                    throw new TombstoneChangedException(
                        "Fileset versions changed while deleting %s", identifier);
                  }
                  if (mapper.countCurrentVersionGeneration(
                          fileset.getFilesetId(),
                          fileset.getCurrentVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId())
                      < 1) {
                    throw new TombstoneChangedException(
                        "Fileset current version changed while deleting %s", identifier);
                  }
                  mapper.softDeleteOwnerRelations(
                      fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteSecurableObjects(
                      fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteTagRelations(
                      fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteStatistics(
                      fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeletePolicyRelations(
                      fileset.getFilesetId(), deletionTimestamp, deletion.getDeletionId());
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.FILESET.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleteResult.get() == 1;
  }

  /**
   * Restores one exact fileset metadata deletion generation transactionally.
   *
   * <p>This operation never calls a connector or filesystem. Managed and external filesets both
   * restore only their Gravitino metadata.
   *
   * @param identifier original fileset identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored fileset entity
   */
  public FilesetEntity restoreFileset(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkFileset(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<FilesetEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          SessionUtils.doWithoutCommit(
              FilesetRecoveryMapper.class,
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
                                Entity.EntityType.FILESET.name(), schemaId, identifier.name()));
                if (latest == null
                    || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                      "Deletion generation %s is no longer latest for fileset %s",
                      observed.getDeletionId(),
                      identifier);
                }

                EntityDeletionPO actual =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
                  restored.set(loadIdempotentlyRestoredFileset(identifier, schemaId, actual));
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

                FilesetPO generation =
                    mapper.selectFilesetGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateFilesetGeneration(actual, generation);
                if (mapper.countCurrentVersionGeneration(
                        actual.getEntityId(),
                        actual.getEntityVersion(),
                        actual.getDeletedAt(),
                        actual.getDeletionId())
                    < 1) {
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
                mapper.restoreStatistics(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restorePolicyRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (mapper.restoreFilesetVersions(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                    < 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }
                if (restoreFilesetMeta(mapper, actual, generation, identifier) != 1) {
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
                            Entity.EntityType.FILESET.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getFilesetByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored fileset must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded fileset deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredFilesetDeletions")
  public int purgeExpiredFilesetDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.FILESET, legacyTimeline, limit);
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
                FilesetRecoveryMapper.class,
                mapper -> purgeFilesetDeletionGeneration(mapper, actual));
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
      baseMetricName = "deleteFilesetAndVersionMetasByLegacyTimeline")
  public int deleteFilesetAndVersionMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    int filesetDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            FilesetMetaMapper.class,
            mapper -> {
              return mapper.deleteFilesetMetasByLegacyTimeline(legacyTimeline, limit);
            });
    int filesetVersionDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            FilesetVersionMapper.class,
            mapper -> {
              return mapper.deleteFilesetVersionsByLegacyTimeline(legacyTimeline, limit);
            });
    return filesetDeletedCount + filesetVersionDeletedCount;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteFilesetVersionsByRetentionCount")
  public int deleteFilesetVersionsByRetentionCount(Long versionRetentionCount, int limit) {
    // get the current version of all filesets.
    List<FilesetMaxVersionPO> filesetCurVersions =
        SessionUtils.getWithoutCommit(
            FilesetVersionMapper.class,
            mapper -> mapper.selectFilesetVersionsByRetentionCount(versionRetentionCount));

    // soft delete old versions that are older than or equal to (currentVersion -
    // versionRetentionCount).
    int totalDeletedCount = 0;
    for (FilesetMaxVersionPO filesetCurVersion : filesetCurVersions) {
      long versionRetentionLine = filesetCurVersion.getVersion() - versionRetentionCount;
      int deletedCount =
          SessionUtils.doWithCommitAndFetchResult(
              FilesetVersionMapper.class,
              mapper ->
                  mapper.softDeleteFilesetVersionsByRetentionLine(
                      filesetCurVersion.getFilesetId(), versionRetentionLine, limit));
      totalDeletedCount += deletedCount;

      // log the deletion by current fileset version.
      LOG.info(
          "Soft delete filesetVersions count: {} which versions are older than or equal to"
              + " versionRetentionLine: {}, the current filesetId and version is: <{}, {}>.",
          deletedCount,
          versionRetentionLine,
          filesetCurVersion.getFilesetId(),
          filesetCurVersion.getVersion());
    }
    return totalDeletedCount;
  }

  private static void lockLiveSchema(FilesetRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      FilesetRecoveryMapper mapper,
      long filesetId,
      long schemaId,
      String filesetName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestFilesetDeletedAt(filesetId, schemaId, filesetName);
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
            && Entity.EntityType.FILESET.name().equals(actual.getEntityType())
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

  private static FilesetEntity loadIdempotentlyRestoredFileset(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    FilesetEntity liveFileset;
    try {
      liveFileset = getInstance().getFilesetByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveFileset.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Fileset ID %s is active under a different logical fileset",
          deletion.getEntityId());
    }
    return liveFileset;
  }

  private static void validateFilesetGeneration(
      EntityDeletionPO deletion, @Nullable FilesetPO generation) {
    if (generation == null
        || !Objects.equals(generation.getFilesetId(), deletion.getEntityId())
        || !Objects.equals(generation.getSchemaId(), deletion.getParentId())
        || !Objects.equals(generation.getFilesetName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static int restoreFilesetMeta(
      FilesetRecoveryMapper mapper,
      EntityDeletionPO deletion,
      FilesetPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreFilesetMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.getCurrentVersion(),
          generation.getLastVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.FILESET, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live fileset already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void purgeFilesetDeletionGeneration(
      FilesetRecoveryMapper mapper, EntityDeletionPO deletion) {
    FilesetPO generation =
        mapper.selectFilesetGeneration(
            deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    validateFilesetGeneration(deletion, generation);
    if (mapper.countCurrentVersionGeneration(
            deletion.getEntityId(),
            deletion.getEntityVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        < 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
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
    mapper.hardDeleteFilesetVersions(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    if (mapper.hardDeleteFilesetMeta(
            deletion.getEntityId(),
            deletion.getParentId(),
            deletion.getEntityName(),
            generation.getCurrentVersion(),
            generation.getLastVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private FilesetPO getFilesetPOByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkFileset(identifier);

    return filesetPOFetcher().apply(identifier);
  }

  private FilesetPO getFilesetPOBySchemaId(NameIdentifier identifier) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    return getFilesetPOBySchemaIdAndName(schemaId, identifier.name());
  }

  private FilesetPO getFilesetPOByFullQualifiedName(NameIdentifier identifier) {
    String[] namespaceLevels = identifier.namespace().levels();
    FilesetPO filesetPO =
        getFilesetByFullQualifiedName(
            namespaceLevels[0], namespaceLevels[1], namespaceLevels[2], identifier.name());

    if (filesetPO.getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          namespaceLevels[2]);
    }

    if (filesetPO.getFilesetId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FILESET.name().toLowerCase(),
          identifier.name());
    }

    return filesetPO;
  }

  private Function<Namespace, List<FilesetPO>> filesetListFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::listFilesetPOsBySchemaId
        : this::listFilesetPOsByFullQualifiedName;
  }

  private Function<NameIdentifier, FilesetPO> filesetPOFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::getFilesetPOBySchemaId
        : this::getFilesetPOByFullQualifiedName;
  }

  private void fillFilesetPOBuilderParentEntityId(FilesetPO.Builder builder, Namespace namespace) {
    NamespaceUtil.checkFileset(namespace);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.namespaceIds()[1]);
    builder.withSchemaId(namespacedEntityId.entityId());
  }

  private FilesetPO getFilesetByFullQualifiedName(
      String metalakeName, String catalogName, String schemaName, String filesetName) {
    FilesetPO filesetPO =
        SessionUtils.getWithoutCommit(
            FilesetMetaMapper.class,
            mapper ->
                mapper.selectFilesetByFullQualifiedName(
                    metalakeName, catalogName, schemaName, filesetName));
    if (filesetPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.FILESET.name().toLowerCase(),
          filesetName);
    }

    return filesetPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetFilesetByIdentifier")
  public List<FilesetEntity> batchGetFilesetByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    NameIdentifier schemaIdent = NameIdentifierUtil.getSchemaIdentifier(firstIdent);
    List<String> filesetNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        FilesetMetaMapper.class,
        mapper -> {
          List<FilesetPO> filesetPOs =
              mapper.batchSelectFilesetByIdentifier(
                  schemaIdent.namespace().level(0),
                  schemaIdent.namespace().level(1),
                  schemaIdent.name(),
                  filesetNames);
          return POConverters.fromFilesetPOs(filesetPOs, firstIdent.namespace());
        });
  }
}
