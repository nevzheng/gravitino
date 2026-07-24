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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NonEmptyEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.helper.CatalogIds;
import org.apache.gravitino.storage.relational.mapper.CatalogAggregateTable;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.mapper.CatalogRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * The service class for catalog metadata. It provides the basic database operations for catalog.
 */
public class CatalogMetaService {
  private static final CatalogMetaService INSTANCE = new CatalogMetaService();

  public static CatalogMetaService getInstance() {
    return INSTANCE;
  }

  private CatalogMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getCatalogPOByName")
  public CatalogPO getCatalogPOByName(String metalakeName, String catalogName) {
    CatalogPO catalogPO =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class,
            mapper -> mapper.selectCatalogMetaByName(metalakeName, catalogName));

    if (catalogPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.CATALOG.name().toLowerCase(),
          catalogName);
    }
    return catalogPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getCatalogIdByMetalakeAndCatalogName")
  public CatalogIds getCatalogIdByMetalakeAndCatalogName(String metalakeName, String catalogName) {
    CatalogIds catalogIds =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class,
            mapper ->
                mapper.selectCatalogIdByMetalakeNameAndCatalogName(metalakeName, catalogName));
    if (catalogIds == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.CATALOG.name().toLowerCase(),
          catalogName);
    }
    return catalogIds;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getCatalogIdByMetalakeIdAndName")
  public Long getCatalogIdByMetalakeIdAndName(Long metalakeId, String catalogName) {
    Long catalogId =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class,
            mapper -> mapper.selectCatalogIdByMetalakeIdAndName(metalakeId, catalogName));

    if (catalogId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.CATALOG.name().toLowerCase(),
          catalogName);
    }
    return catalogId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getCatalogIdByName")
  public Long getCatalogIdByName(String metalakeName, String catalogName) {
    Long catalogId =
        SessionUtils.doWithCommitAndFetchResult(
            CatalogMetaMapper.class,
            mapper -> mapper.selectCatalogIdByName(metalakeName, catalogName));

    if (catalogId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.CATALOG.name().toLowerCase(),
          catalogName);
    }
    return catalogId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getCatalogByIdentifier")
  public CatalogEntity getCatalogByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkCatalog(identifier);
    String catalogName = identifier.name();

    CatalogPO catalogPO = getCatalogPOByName(identifier.namespace().level(0), catalogName);

    return POConverters.fromCatalogPO(catalogPO, identifier.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listCatalogsByNamespace")
  public List<CatalogEntity> listCatalogsByNamespace(Namespace namespace) {
    NamespaceUtil.checkCatalog(namespace);
    List<CatalogPO> catalogPOS =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class,
            mapper -> mapper.listCatalogPOsByMetalakeName(namespace.level(0)));

    return POConverters.fromCatalogPOs(catalogPOS, namespace);
  }

  /**
   * Lists independently deleted catalog roots immediately below one live metalake.
   *
   * <p>Catalog rows deleted as part of a future metalake aggregate are not independently
   * discoverable because they do not own a catalog deletion receipt. Legacy tombstones remain
   * visible for audit, but cannot be restored by the receipt-based protocol.
   *
   * @param namespace metalake namespace
   * @return deleted catalog base rows, newest first
   */
  public List<CatalogPO> listDeletedCatalogsByNamespace(Namespace namespace) {
    NamespaceUtil.checkCatalog(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        CatalogRecoveryMapper.class, mapper -> mapper.listDeletedRootCatalogs(metalakeId));
  }

  /**
   * Lists globally live catalog rows matching candidate immutable IDs.
   *
   * @param catalogIds candidate catalog IDs
   * @return matching live catalog rows
   */
  public List<CatalogPO> listLiveCatalogsByIds(List<Long> catalogIds) {
    if (catalogIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        CatalogRecoveryMapper.class, mapper -> mapper.listLiveCatalogsByIds(catalogIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertCatalog")
  public void insertCatalog(CatalogEntity catalogEntity, boolean overwrite) throws IOException {
    try {
      NameIdentifierUtil.checkCatalog(catalogEntity.nameIdentifier());

      String metalake = NameIdentifierUtil.getMetalake(catalogEntity.nameIdentifier());
      Long metalakeId =
          EntityIdService.getEntityId(NameIdentifier.of(metalake), Entity.EntityType.METALAKE);

      CatalogPO po = POConverters.initializeCatalogPOWithVersion(catalogEntity, metalakeId);
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  CatalogRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalake(mapper, metalakeId);
                    if (!mapper
                        .selectRecordedDeletedCatalogsForUpdate(
                            Collections.singletonList(po.getCatalogId()))
                        .isEmpty()) {
                      throw new TombstoneChangedException(
                          "Catalog ID %s belongs to a recoverable deletion generation",
                          po.getCatalogId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  CatalogMetaMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertCatalogMetaOnDuplicateKeyUpdate(po);
                    } else {
                      mapper.insertCatalogMeta(po);
                    }
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.CATALOG, catalogEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateCatalog")
  public <E extends Entity & HasIdentifier> CatalogEntity updateCatalog(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkCatalog(identifier);

    String catalogName = identifier.name();

    CatalogPO oldCatalogPO = getCatalogPOByName(identifier.namespace().level(0), catalogName);

    CatalogEntity oldCatalogEntity =
        POConverters.fromCatalogPO(oldCatalogPO, identifier.namespace());
    CatalogEntity newEntity = (CatalogEntity) updater.apply((E) oldCatalogEntity);
    Preconditions.checkArgument(
        Objects.equals(oldCatalogEntity.id(), newEntity.id()),
        "The updated catalog entity id: %s should be same with the catalog entity id before: %s",
        newEntity.id(),
        oldCatalogEntity.id());

    String metalakeName = identifier.namespace().level(0);
    String oldFullName =
        NameIdentifierUtil.ofCatalog(metalakeName, oldCatalogEntity.name()).toString();

    AtomicInteger updateResult = new AtomicInteger(0);
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  CatalogRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalake(mapper, oldCatalogPO.getMetalakeId());
                    lockLiveCatalog(mapper, oldCatalogPO.getCatalogId());
                  }),
          () ->
              updateResult.set(
                  SessionUtils.getWithoutCommit(
                      CatalogMetaMapper.class,
                      mapper ->
                          mapper.updateCatalogMeta(
                              POConverters.updateCatalogPOWithVersion(
                                  oldCatalogPO, newEntity, oldCatalogPO.getMetalakeId()),
                              oldCatalogPO))),
          () -> {
            if (updateResult.get() > 0) {
              SessionUtils.doWithoutCommit(
                  EntityChangeLogMapper.class,
                  mapper ->
                      mapper.insertEntityChange(
                          metalakeName,
                          Entity.EntityType.CATALOG.name(),
                          oldFullName,
                          OperateType.ALTER));
            }
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.CATALOG, newEntity.nameIdentifier().toString());
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
      baseMetricName = "deleteCatalog")
  public boolean deleteCatalog(NameIdentifier identifier, boolean cascade) {
    return deleteCatalog(identifier, cascade, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes one catalog metadata root and, when requested, its live descendant tree.
   *
   * @param identifier catalog identifier
   * @param cascade whether to include every live descendant metadata row
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live catalog root was deleted
   */
  public boolean deleteCatalog(NameIdentifier identifier, boolean cascade, long retentionMs) {
    return deleteCatalog(identifier, cascade, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes one exact catalog-tree snapshot with a shared timestamp and generation token.
   *
   * <p>The operation changes Gravitino metadata only. Connector-owned objects are not inspected or
   * restored by this transaction.
   *
   * @param identifier catalog identifier
   * @param cascade whether to include every live descendant metadata row
   * @param requestedDeletedAt requested deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live catalog root was deleted
   */
  public boolean deleteCatalog(
      NameIdentifier identifier, boolean cascade, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkCatalog(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    CatalogPO observedRoot = getCatalogPOByName(identifier.namespace().level(0), identifier.name());
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                CatalogRecoveryMapper.class,
                recoveryMapper -> {
                  lockLiveMetalake(recoveryMapper, observedRoot.getMetalakeId());
                  lockLiveCatalog(recoveryMapper, observedRoot.getCatalogId());
                  CatalogPO root =
                      SessionUtils.getWithoutCommit(
                          CatalogMetaMapper.class,
                          mapper -> mapper.selectCatalogMetaById(observedRoot.getCatalogId()));
                  if (root == null
                      || !Objects.equals(root.getMetalakeId(), observedRoot.getMetalakeId())
                      || !Objects.equals(root.getCatalogName(), identifier.name())) {
                    throw new TombstoneChangedException(
                        "Catalog %s changed while being locked", identifier);
                  }
                  List<Long> schemaIds = recoveryMapper.lockLiveSchemas(root.getCatalogId());
                  List<Long> sortedSchemaIds =
                      schemaIds.stream().distinct().sorted().collect(Collectors.toList());
                  if (!schemaIds.equals(sortedSchemaIds)) {
                    throw new TombstoneChangedException(
                        "The schema membership of catalog %s changed while being locked",
                        identifier);
                  }
                  if (!cascade && recoveryMapper.countLiveDescendants(root.getCatalogId()) != 0) {
                    throw new NonEmptyEntityException(
                        "Entity %s has sub-entities, you should remove sub-entities first",
                        identifier);
                  }

                  long deletedAt =
                      chooseAggregateDeletedAt(
                          recoveryMapper, root.getCatalogId(), requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.CATALOG,
                              root.getCatalogId(),
                              root.getMetalakeId(),
                              null,
                              root.getMetalakeId(),
                              root.getCatalogName(),
                              root.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  for (CatalogAggregateTable aggregateTable : CatalogAggregateTable.values()) {
                    int changed =
                        recoveryMapper.softDeleteAggregateRows(
                            aggregateTable,
                            root.getCatalogId(),
                            deletedAt,
                            deletion.getDeletionId());
                    if (aggregateTable == CatalogAggregateTable.CATALOG) {
                      deleted.set(changed);
                      if (changed != 1) {
                        throw tombstoneChanged(deletion.getDeletionId());
                      }
                    }
                  }
                  if (recoveryMapper.countMissingRequiredDetails(
                              deletedAt, deletion.getDeletionId())
                          != 0
                      || recoveryMapper.countBrokenGenerationReferences(
                              deletedAt, deletion.getDeletionId())
                          != 0) {
                    throw tombstoneChanged(deletion.getDeletionId());
                  }

                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      mapper ->
                          mapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.CATALOG.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /**
   * Restores one exact catalog metadata-tree deletion generation transactionally.
   *
   * <p>The operation restores only relational Gravitino metadata. It does not invoke catalog
   * connectors, recreate downstream objects, or validate their existence.
   *
   * @param identifier original catalog identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored root catalog entity
   */
  public CatalogEntity restoreCatalog(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkCatalog(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
    AtomicReference<CatalogEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  CatalogRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalake(mapper, metalakeId);
                    if (!Objects.equals(observed.getParentId(), metalakeId)) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.PARENT_CHANGED,
                          "The original metalake changed while restoring %s",
                          identifier);
                    }

                    EntityDeletionPO latest =
                        SessionUtils.getWithoutCommit(
                            EntityDeletionMapper.class,
                            deletionMapper ->
                                deletionMapper.selectLatestEntityDeletion(
                                    Entity.EntityType.CATALOG.name(),
                                    metalakeId,
                                    identifier.name()));
                    if (latest == null
                        || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                          "Deletion generation %s is no longer latest for catalog %s",
                          observed.getDeletionId(),
                          identifier);
                    }

                    EntityDeletionPO actual =
                        SessionUtils.getWithoutCommit(
                            EntityDeletionMapper.class,
                            deletionMapper ->
                                deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                    if (isCompletedRestoreReplay(
                        identifier, metalakeId, observed, actual, restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredCatalog(identifier, actual));
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

                    CatalogPO root =
                        mapper.selectCatalogGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateRootGeneration(identifier, actual, root);
                    List<SchemaPO> schemaTree =
                        mapper.listCatalogSchemaGenerationForUpdate(
                            actual.getDeletedAt(), actual.getDeletionId());
                    validateSchemaTree(actual, schemaTree);

                    Map<CatalogAggregateTable, Integer> expectedCounts =
                        generationCounts(mapper, actual);
                    if (expectedCounts.get(CatalogAggregateTable.CATALOG) != 1
                        || expectedCounts.get(CatalogAggregateTable.SCHEMA) != schemaTree.size()
                        || mapper.countMissingRequiredDetails(
                                actual.getDeletedAt(), actual.getDeletionId())
                            != 0
                        || mapper.countBrokenGenerationReferences(
                                actual.getDeletedAt(), actual.getDeletionId())
                            != 0) {
                      throw tombstoneChanged(actual.getDeletionId());
                    }

                    for (CatalogAggregateTable aggregateTable : CatalogAggregateTable.values()) {
                      int changed =
                          restoreGenerationRows(
                              mapper, aggregateTable, actual, restoredAt, identifier);
                      if (changed != expectedCounts.get(aggregateTable)) {
                        throw tombstoneChanged(actual.getDeletionId());
                      }
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
                                Entity.EntityType.CATALOG.name(),
                                identifier.toString(),
                                OperateType.RESTORE));
                    restored.set(getCatalogByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(identifier, metalakeId, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredCatalog(identifier, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored catalog must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded catalog deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  public int purgeExpiredCatalogDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.CATALOG, legacyTimeline, limit);
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
                CatalogRecoveryMapper.class,
                mapper -> {
                  CatalogPO root =
                      mapper.selectCatalogGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  if (root == null) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }
                  Map<CatalogAggregateTable, Integer> expectedCounts =
                      generationCounts(mapper, actual);
                  if (expectedCounts.get(CatalogAggregateTable.CATALOG) != 1
                      || mapper.countMissingRequiredDetails(
                              actual.getDeletedAt(), actual.getDeletionId())
                          != 0
                      || mapper.countBrokenGenerationReferences(
                              actual.getDeletedAt(), actual.getDeletionId())
                          != 0) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }
                  for (CatalogAggregateTable aggregateTable : CatalogAggregateTable.values()) {
                    int changed =
                        mapper.hardDeleteGenerationRows(
                            aggregateTable, actual.getDeletedAt(), actual.getDeletionId());
                    if (changed != expectedCounts.get(aggregateTable)) {
                      throw tombstoneChanged(actual.getDeletionId());
                    }
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
      baseMetricName = "deleteCatalogMetasByLegacyTimeline")
  public int deleteCatalogMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        CatalogMetaMapper.class,
        mapper -> {
          return mapper.deleteCatalogMetasByLegacyTimeline(legacyTimeline, limit);
        });
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetCatalogByIdentifier")
  public List<CatalogEntity> batchGetCatalogByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    String metalakeName = firstIdent.namespace().level(0);
    List<String> catalogNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        CatalogMetaMapper.class,
        mapper -> {
          List<CatalogPO> catalogPOs =
              mapper.batchSelectCatalogByIdentifier(metalakeName, catalogNames);
          return POConverters.fromCatalogPOs(catalogPOs, firstIdent.namespace());
        });
  }

  private static void lockLiveMetalake(CatalogRecoveryMapper mapper, long metalakeId) {
    Long lockedMetalakeId = mapper.lockLiveMetalake(metalakeId);
    if (!Objects.equals(metalakeId, lockedMetalakeId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          metalakeId);
    }
  }

  private static void lockLiveCatalog(CatalogRecoveryMapper mapper, long catalogId) {
    Long lockedCatalogId = mapper.lockLiveCatalog(catalogId);
    if (!Objects.equals(catalogId, lockedCatalogId)) {
      throw new TombstoneChangedException("Catalog %s changed while being locked", catalogId);
    }
  }

  private static long chooseAggregateDeletedAt(
      CatalogRecoveryMapper mapper, long catalogId, long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestAggregateDeletedAt(catalogId);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static Map<CatalogAggregateTable, Integer> generationCounts(
      CatalogRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<CatalogAggregateTable, Integer> counts = new EnumMap<>(CatalogAggregateTable.class);
    for (CatalogAggregateTable aggregateTable : CatalogAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static int restoreGenerationRows(
      CatalogRecoveryMapper mapper,
      CatalogAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.CATALOG, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live metadata row already occupies part of catalog tree %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
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
            && Objects.equals(actual.getState(), observed.getState())
            && Objects.equals(actual.getRevision(), observed.getRevision())
            && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
            && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
            && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt())
            && actual.getState() == DeletionState.DELETED
            && Entity.EntityType.CATALOG.name().equals(actual.getEntityType())
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
        && Objects.equals(actual.getMetalakeId(), metalakeId)
        && actual.getCatalogId() == null
        && Objects.equals(actual.getParentId(), metalakeId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
        && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
        && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
        && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  private static void validateRootGeneration(
      NameIdentifier identifier, EntityDeletionPO deletion, @Nullable CatalogPO root) {
    if (root == null
        || !Objects.equals(root.getCatalogId(), deletion.getEntityId())
        || !Objects.equals(root.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(root.getCatalogName(), identifier.name())
        || !Objects.equals(root.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(root.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(root.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static void validateSchemaTree(EntityDeletionPO deletion, List<SchemaPO> schemaTree) {
    Set<String> schemaNames =
        schemaTree.stream().map(SchemaPO::getSchemaName).collect(Collectors.toSet());
    if (schemaNames.size() != schemaTree.size()) {
      throw tombstoneChanged(deletion.getDeletionId());
    }

    for (SchemaPO schema : schemaTree) {
      if (!Objects.equals(schema.getMetalakeId(), deletion.getMetalakeId())
          || !Objects.equals(schema.getCatalogId(), deletion.getEntityId())
          || !Objects.equals(schema.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(schema.getDeletionId(), deletion.getDeletionId())) {
        throw tombstoneChanged(deletion.getDeletionId());
      }
      String parent = immediatePhysicalParentName(schema.getSchemaName());
      if (parent != null && !schemaNames.contains(parent)) {
        throw tombstoneChanged(deletion.getDeletionId());
      }
    }
  }

  @Nullable
  private static String immediatePhysicalParentName(String schemaName) {
    int split = schemaName.lastIndexOf(HierarchicalSchemaUtil.physicalSeparator());
    return split < 0 ? null : schemaName.substring(0, split);
  }

  private static CatalogEntity loadIdempotentlyRestoredCatalog(
      NameIdentifier identifier, EntityDeletionPO deletion) {
    CatalogEntity live;
    try {
      live = getInstance().getCatalogByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Catalog ID %s is active under a different logical catalog",
          deletion.getEntityId());
    }
    return live;
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }
}
