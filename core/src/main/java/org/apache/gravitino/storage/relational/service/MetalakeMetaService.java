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
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NonEmptyEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeAggregateTable;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/** Service for live and recoverably deleted metalake metadata. */
public class MetalakeMetaService {

  private static final MetalakeMetaService INSTANCE = new MetalakeMetaService();

  /** Returns the singleton metalake metadata service. */
  public static MetalakeMetaService getInstance() {
    return INSTANCE;
  }

  private MetalakeMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listMetalakes")
  public List<BaseMetalake> listMetalakes() {
    List<MetalakePO> metalakePOS =
        SessionUtils.getWithoutCommit(
            MetalakeMetaMapper.class, MetalakeMetaMapper::listMetalakePOs);
    return POConverters.fromMetalakePOs(metalakePOS);
  }

  /**
   * Lists independently deleted metalake roots.
   *
   * <p>Legacy tombstones remain visible for audit but have no deletion receipt and therefore are
   * not restorable through the exact-generation protocol.
   */
  public List<MetalakePO> listDeletedMetalakes() {
    return SessionUtils.getWithoutCommit(
        MetalakeRecoveryMapper.class, MetalakeRecoveryMapper::listDeletedRootMetalakes);
  }

  /** Lists globally live metalakes matching candidate immutable IDs. */
  public List<MetalakePO> listLiveMetalakesByIds(List<Long> metalakeIds) {
    if (metalakeIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        MetalakeRecoveryMapper.class, mapper -> mapper.listLiveMetalakesByIds(metalakeIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getMetalakeIdByName")
  public Long getMetalakeIdByName(String metalakeName) {
    Long metalakeId =
        SessionUtils.getWithoutCommit(
            MetalakeMetaMapper.class, mapper -> mapper.selectMetalakeIdMetaByName(metalakeName));
    if (metalakeId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          metalakeName);
    }
    return metalakeId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getMetalakeByIdentifier")
  public BaseMetalake getMetalakeByIdentifier(NameIdentifier ident) {
    NameIdentifierUtil.checkMetalake(ident);
    MetalakePO metalakePO =
        SessionUtils.getWithoutCommit(
            MetalakeMetaMapper.class, mapper -> mapper.selectMetalakeMetaByName(ident.name()));
    if (metalakePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          ident.toString());
    }
    return POConverters.fromMetalakePO(metalakePO);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertMetalake")
  public void insertMetalake(BaseMetalake baseMetalake, boolean overwrite) throws IOException {
    try {
      NameIdentifierUtil.checkMetalake(baseMetalake.nameIdentifier());
      MetalakePO po = POConverters.initializeMetalakePOWithVersion(baseMetalake);
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  MetalakeRecoveryMapper.class,
                  mapper -> {
                    // Lock a same-ID live row before an overwrite so it cannot cross a root
                    // deletion transaction. A tokenized tombstone is never revived by put().
                    mapper.lockLiveMetalake(po.getMetalakeId());
                    if (!mapper
                        .selectRecordedDeletedMetalakesForUpdate(
                            Collections.singletonList(po.getMetalakeId()))
                        .isEmpty()) {
                      throw new TombstoneChangedException(
                          "Metalake ID %s belongs to a recoverable deletion generation",
                          po.getMetalakeId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  MetalakeMetaMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertMetalakeMetaOnDuplicateKeyUpdate(po);
                    } else {
                      mapper.insertMetalakeMeta(po);
                    }
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.METALAKE, baseMetalake.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateMetalake")
  public <E extends Entity & HasIdentifier> BaseMetalake updateMetalake(
      NameIdentifier ident, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkMetalake(ident);
    MetalakePO oldMetalakePO =
        SessionUtils.getWithoutCommit(
            MetalakeMetaMapper.class, mapper -> mapper.selectMetalakeMetaByName(ident.name()));
    if (oldMetalakePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          ident.toString());
    }

    BaseMetalake oldMetalakeEntity = POConverters.fromMetalakePO(oldMetalakePO);
    BaseMetalake newMetalakeEntity = (BaseMetalake) updater.apply((E) oldMetalakeEntity);
    Preconditions.checkArgument(
        Objects.equals(oldMetalakeEntity.id(), newMetalakeEntity.id()),
        "The updated metalake entity id: %s should be same with the metalake entity id before: %s",
        newMetalakeEntity.id(),
        oldMetalakeEntity.id());
    MetalakePO newMetalakePO =
        POConverters.updateMetalakePOWithVersion(oldMetalakePO, newMetalakeEntity);

    String oldFullName = oldMetalakeEntity.name();
    boolean isRenamed = !Objects.equals(oldMetalakeEntity.name(), newMetalakeEntity.name());
    AtomicInteger updateResult = new AtomicInteger();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  MetalakeRecoveryMapper.class,
                  mapper -> lockLiveMetalake(mapper, oldMetalakePO.getMetalakeId())),
          () ->
              updateResult.set(
                  SessionUtils.getWithoutCommit(
                      MetalakeMetaMapper.class,
                      mapper -> mapper.updateMetalakeMeta(newMetalakePO, oldMetalakePO))),
          () -> {
            if (isRenamed && updateResult.get() > 0) {
              SessionUtils.doWithoutCommit(
                  EntityChangeLogMapper.class,
                  mapper ->
                      mapper.insertEntityChange(
                          oldMetalakeEntity.name(),
                          Entity.EntityType.METALAKE.name(),
                          oldFullName,
                          OperateType.ALTER));
            }
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.METALAKE, newMetalakeEntity.nameIdentifier().toString());
      throw re;
    }

    if (updateResult.get() > 0) {
      return newMetalakeEntity;
    }
    throw new IOException("Failed to update the entity: " + ident);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteMetalake")
  public boolean deleteMetalake(NameIdentifier ident, boolean cascade) {
    return deleteMetalake(ident, cascade, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one metalake metadata root and its exact live metadata tree. */
  public boolean deleteMetalake(NameIdentifier ident, boolean cascade, long retentionMs) {
    return deleteMetalake(ident, cascade, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes one exact metalake metadata-tree snapshot with one timestamp and generation token.
   *
   * <p>The operation changes Gravitino metadata only. Connector-owned objects and external
   * authorization systems are neither inspected nor mutated.
   */
  public boolean deleteMetalake(
      NameIdentifier ident, boolean cascade, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkMetalake(ident);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    MetalakePO observedRoot =
        SessionUtils.getWithoutCommit(
            MetalakeMetaMapper.class, mapper -> mapper.selectMetalakeMetaByName(ident.name()));
    if (observedRoot == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          ident.toString());
    }

    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                MetalakeRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalake(mapper, observedRoot.getMetalakeId());
                  MetalakePO root =
                      SessionUtils.getWithoutCommit(
                          MetalakeMetaMapper.class,
                          metalakeMapper ->
                              metalakeMapper.selectMetalakeMetaById(observedRoot.getMetalakeId()));
                  if (root == null || !Objects.equals(root.getMetalakeName(), ident.name())) {
                    throw new TombstoneChangedException(
                        "Metalake %s changed while being locked", ident);
                  }

                  List<Long> catalogIds = mapper.lockLiveCatalogs(root.getMetalakeId());
                  validateLockedIds(catalogIds, "catalog", ident);
                  List<Long> schemaIds = mapper.lockLiveSchemas(root.getMetalakeId());
                  validateLockedIds(schemaIds, "schema", ident);
                  if (!cascade && !catalogIds.isEmpty()) {
                    throw new NonEmptyEntityException(
                        "Entity %s has sub-entities, you should remove sub-entities first", ident);
                  }

                  long deletedAt =
                      chooseAggregateDeletedAt(mapper, root.getMetalakeId(), requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.METALAKE,
                              root.getMetalakeId(),
                              root.getMetalakeId(),
                              null,
                              null,
                              root.getMetalakeName(),
                              root.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  for (MetalakeAggregateTable aggregateTable : MetalakeAggregateTable.values()) {
                    int changed =
                        mapper.softDeleteAggregateRows(
                            aggregateTable,
                            root.getMetalakeId(),
                            deletedAt,
                            deletion.getDeletionId());
                    if (aggregateTable == MetalakeAggregateTable.METALAKE) {
                      deleted.set(changed);
                      if (changed != 1) {
                        throw tombstoneChanged(deletion.getDeletionId());
                      }
                    }
                  }
                  validateGenerationIntegrity(mapper, deletedAt, deletion.getDeletionId());

                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              ident.name(),
                              Entity.EntityType.METALAKE.name(),
                              ident.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /**
   * Restores one exact metalake metadata-tree deletion generation transactionally.
   *
   * <p>The operation restores only relational Gravitino metadata. It does not invoke connectors,
   * recreate downstream resources, or replay external authorization state.
   */
  public BaseMetalake restoreMetalake(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkMetalake(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<BaseMetalake> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  MetalakeRecoveryMapper.class,
                  mapper -> {
                    validateLatestMetalakeDeletion(identifier, observed);

                    EntityDeletionPO actual =
                        SessionUtils.getWithoutCommit(
                            EntityDeletionMapper.class,
                            deletionMapper ->
                                deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                    if (isCompletedRestoreReplay(identifier, observed, actual, restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredMetalake(identifier, actual));
                      return;
                    }
                    validateDeletionSnapshot(identifier, observed, actual);
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

                    MetalakePO root =
                        mapper.selectMetalakeGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateRootGeneration(identifier, actual, root);
                    List<CatalogPO> catalogTree =
                        mapper.listMetalakeCatalogGenerationForUpdate(
                            actual.getDeletedAt(), actual.getDeletionId());
                    List<SchemaPO> schemaTree =
                        mapper.listMetalakeSchemaGenerationForUpdate(
                            actual.getDeletedAt(), actual.getDeletionId());
                    validateContainerTree(actual, catalogTree, schemaTree);

                    Map<MetalakeAggregateTable, Integer> expectedCounts =
                        generationCounts(mapper, actual);
                    if (expectedCounts.get(MetalakeAggregateTable.METALAKE) != 1
                        || expectedCounts.get(MetalakeAggregateTable.CATALOG) != catalogTree.size()
                        || expectedCounts.get(MetalakeAggregateTable.SCHEMA) != schemaTree.size()) {
                      throw incompleteGeneration(actual.getDeletionId());
                    }
                    validateGenerationIntegrity(
                        mapper, actual.getDeletedAt(), actual.getDeletionId());

                    for (MetalakeAggregateTable aggregateTable : MetalakeAggregateTable.values()) {
                      int changed =
                          restoreGenerationRows(
                              mapper, aggregateTable, actual, restoredAt, identifier);
                      if (changed != expectedCounts.get(aggregateTable)) {
                        throw incompleteGeneration(actual.getDeletionId());
                      }
                    }

                    // A root metalake has no live parent row that can serialize same-name
                    // generations across servers. Re-check after activating the root (which is
                    // restored last): while this transaction holds the live-name key, no newer
                    // same-name generation can now be created and deleted successfully.
                    validateLatestMetalakeDeletionForUpdate(identifier, actual);

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
                                identifier.name(),
                                Entity.EntityType.METALAKE.name(),
                                identifier.toString(),
                                OperateType.RESTORE));
                    restored.set(getMetalakeByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(identifier, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredMetalake(identifier, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored metalake must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded metalake deletion generations. */
  public int purgeExpiredMetalakeDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.METALAKE, legacyTimeline, limit);
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
                MetalakeRecoveryMapper.class,
                mapper -> {
                  MetalakePO root =
                      mapper.selectMetalakeGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  if (root == null) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  Map<MetalakeAggregateTable, Integer> expectedCounts =
                      generationCounts(mapper, actual);
                  if (expectedCounts.get(MetalakeAggregateTable.METALAKE) != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  validateGenerationIntegrity(
                      mapper, actual.getDeletedAt(), actual.getDeletionId());
                  for (MetalakeAggregateTable aggregateTable : MetalakeAggregateTable.values()) {
                    int changed =
                        mapper.hardDeleteGenerationRows(
                            aggregateTable, actual.getDeletedAt(), actual.getDeletionId());
                    if (changed != expectedCounts.get(aggregateTable)) {
                      throw incompleteGeneration(actual.getDeletionId());
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
      baseMetricName = "deleteMetalakeMetasByLegacyTimeline")
  public int deleteMetalakeMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    int[] metalakeDeleteCount = new int[1];
    int[] ownerRelDeleteCount = new int[1];
    SessionUtils.doMultipleWithCommit(
        () ->
            metalakeDeleteCount[0] =
                SessionUtils.getWithoutCommit(
                    MetalakeMetaMapper.class,
                    mapper -> mapper.deleteMetalakeMetasByLegacyTimeline(legacyTimeline, limit)),
        () ->
            ownerRelDeleteCount[0] =
                SessionUtils.getWithoutCommit(
                    OwnerMetaMapper.class,
                    mapper -> mapper.deleteOwnerMetasByLegacyTimeline(legacyTimeline, limit)));
    return metalakeDeleteCount[0] + ownerRelDeleteCount[0];
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetMetalakeByIdentifier")
  public List<BaseMetalake> batchGetMetalakeByIdentifier(List<NameIdentifier> identifiers) {
    List<String> metalakeNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());
    return SessionUtils.doWithCommitAndFetchResult(
        MetalakeMetaMapper.class,
        mapper -> POConverters.fromMetalakePOs(mapper.batchSelectMetalakeByName(metalakeNames)));
  }

  private static void lockLiveMetalake(MetalakeRecoveryMapper mapper, long metalakeId) {
    Long lockedMetalakeId = mapper.lockLiveMetalake(metalakeId);
    if (!Objects.equals(metalakeId, lockedMetalakeId)) {
      throw new TombstoneChangedException("Metalake %s changed while being locked", metalakeId);
    }
  }

  private static void validateLockedIds(
      List<Long> lockedIds, String childType, NameIdentifier root) {
    List<Long> sorted = lockedIds.stream().distinct().sorted().collect(Collectors.toList());
    if (!lockedIds.equals(sorted)) {
      throw new TombstoneChangedException(
          "The %s membership of metalake %s changed while being locked", childType, root);
    }
  }

  private static long chooseAggregateDeletedAt(
      MetalakeRecoveryMapper mapper, long metalakeId, long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestAggregateDeletedAt(metalakeId);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static Map<MetalakeAggregateTable, Integer> generationCounts(
      MetalakeRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<MetalakeAggregateTable, Integer> counts = new EnumMap<>(MetalakeAggregateTable.class);
    for (MetalakeAggregateTable aggregateTable : MetalakeAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static void validateGenerationIntegrity(
      MetalakeRecoveryMapper mapper, long deletedAt, String deletionId) {
    if (mapper.countMissingRequiredDetails(deletedAt, deletionId) != 0
        || mapper.countBrokenGenerationReferences(deletedAt, deletionId) != 0) {
      throw incompleteGeneration(deletionId);
    }
  }

  private static int restoreGenerationRows(
      MetalakeRecoveryMapper mapper,
      MetalakeAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.METALAKE, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live metadata row already occupies part of metalake tree %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier, EntityDeletionPO observed, @Nullable EntityDeletionPO actual) {
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
            && Entity.EntityType.METALAKE.name().equals(actual.getEntityType())
            && Objects.equals(actual.getEntityId(), actual.getMetalakeId())
            && actual.getCatalogId() == null
            && actual.getParentId() == null
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual,
      String restoreEtag) {
    return actual != null
        && observed.getState() == DeletionState.DELETED
        && actual.getState() == DeletionState.RESTORED
        && Objects.equals(actual.getDeletionId(), observed.getDeletionId())
        && Objects.equals(actual.getEntityType(), observed.getEntityType())
        && Objects.equals(actual.getEntityId(), observed.getEntityId())
        && Objects.equals(actual.getEntityId(), actual.getMetalakeId())
        && actual.getCatalogId() == null
        && actual.getParentId() == null
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
      NameIdentifier identifier, EntityDeletionPO deletion, @Nullable MetalakePO root) {
    if (root == null
        || !Objects.equals(root.getMetalakeId(), deletion.getEntityId())
        || !Objects.equals(root.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(root.getMetalakeName(), identifier.name())
        || !Objects.equals(root.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(root.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(root.getDeletionId(), deletion.getDeletionId())) {
      throw incompleteGeneration(deletion.getDeletionId());
    }
  }

  private static void validateContainerTree(
      EntityDeletionPO deletion, List<CatalogPO> catalogs, List<SchemaPO> schemas) {
    Set<Long> catalogIds = new HashSet<>();
    Set<String> catalogNames = new HashSet<>();
    for (CatalogPO catalog : catalogs) {
      if (!Objects.equals(catalog.getMetalakeId(), deletion.getEntityId())
          || !Objects.equals(catalog.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(catalog.getDeletionId(), deletion.getDeletionId())
          || !catalogIds.add(catalog.getCatalogId())
          || !catalogNames.add(catalog.getCatalogName())) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }

    Map<Long, Set<String>> schemaNamesByCatalog = new HashMap<>();
    for (SchemaPO schema : schemas) {
      if (!Objects.equals(schema.getMetalakeId(), deletion.getEntityId())
          || !catalogIds.contains(schema.getCatalogId())
          || !Objects.equals(schema.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(schema.getDeletionId(), deletion.getDeletionId())
          || !schemaNamesByCatalog
              .computeIfAbsent(schema.getCatalogId(), ignored -> new HashSet<>())
              .add(schema.getSchemaName())) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }
    for (SchemaPO schema : schemas) {
      String parent = immediatePhysicalParentName(schema.getSchemaName());
      if (parent != null && !schemaNamesByCatalog.get(schema.getCatalogId()).contains(parent)) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }
  }

  @Nullable
  private static String immediatePhysicalParentName(String schemaName) {
    int split = schemaName.lastIndexOf(HierarchicalSchemaUtil.physicalSeparator());
    return split < 0 ? null : schemaName.substring(0, split);
  }

  private static BaseMetalake loadIdempotentlyRestoredMetalake(
      NameIdentifier identifier, EntityDeletionPO deletion) {
    BaseMetalake live;
    try {
      live = getInstance().getMetalakeByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Metalake ID %s is active under a different logical metalake",
          deletion.getEntityId());
    }
    return live;
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private static RecoveryConflictException incompleteGeneration(String deletionId) {
    return new RecoveryConflictException(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        "Deletion generation %s is incomplete and requires manual metadata repair",
        deletionId);
  }

  private static void validateLatestMetalakeDeletion(
      NameIdentifier identifier, EntityDeletionPO expected) {
    EntityDeletionPO latest =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.selectLatestEntityDeletion(
                    Entity.EntityType.METALAKE.name(), null, identifier.name()));
    validateLatestMetalakeDeletion(identifier, expected, latest);
  }

  private static void validateLatestMetalakeDeletionForUpdate(
      NameIdentifier identifier, EntityDeletionPO expected) {
    EntityDeletionPO latest =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.selectLatestEntityDeletionForUpdate(
                    Entity.EntityType.METALAKE.name(), null, identifier.name()));
    validateLatestMetalakeDeletion(identifier, expected, latest);
  }

  private static void validateLatestMetalakeDeletion(
      NameIdentifier identifier, EntityDeletionPO expected, @Nullable EntityDeletionPO latest) {
    if (latest == null || !Objects.equals(latest.getDeletionId(), expected.getDeletionId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
          "Deletion generation %s is no longer latest for metalake %s",
          expected.getDeletionId(),
          identifier);
    }
  }
}
