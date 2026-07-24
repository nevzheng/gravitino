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
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Pattern;
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
import org.apache.gravitino.exceptions.NonEmptyEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.helper.SchemaIds;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.SchemaAggregateTable;
import org.apache.gravitino.storage.relational.mapper.SchemaMetaMapper;
import org.apache.gravitino.storage.relational.mapper.SchemaRecoveryMapper;
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

/** The service class for schema metadata. It provides the basic database operations for schema. */
public class SchemaMetaService {
  private static final SchemaMetaService INSTANCE = new SchemaMetaService();
  private BasePOStorageOps<SchemaPO, SchemaMetaMapper> ops;

  public static SchemaMetaService getInstance() {
    return INSTANCE;
  }

  private SchemaMetaService() {
    this.ops =
        new HierarchicalConversionPOStorageOps<>(
            new SchemaPOStorageOps(),
            SchemaMetaService::physicalToLogicalSchemaPO,
            SchemaMetaService::logicalToPhysicalSchemaPO);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getSchemaIdByMetalakeNameAndCatalogNameAndSchemaName")
  public SchemaIds getSchemaIdByMetalakeNameAndCatalogNameAndSchemaName(
      String metalakeName, String catalogName, String schemaName) {
    NameIdentifier identifier = NameIdentifier.of(metalakeName, catalogName, schemaName);
    SchemaPO schemaPO =
        SessionUtils.getWithoutCommit(
            SchemaMetaMapper.class,
            mapper ->
                POStorageReadRouting.getPO(mapper, identifier, ops, Entity.EntityType.SCHEMA));

    if (schemaPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          schemaName);
    }

    return new SchemaIds(schemaPO.getMetalakeId(), schemaPO.getCatalogId(), schemaPO.getSchemaId());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getSchemaByIdentifier")
  public SchemaEntity getSchemaByIdentifier(NameIdentifier identifier) {
    SchemaPO schemaPO = getSchemaPOByIdentifier(identifier);
    return POConverters.fromSchemaPO(schemaPO, identifier.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listSchemasByNamespace")
  public List<SchemaEntity> listSchemasByNamespace(Namespace namespace) {
    NamespaceUtil.checkSchema(namespace);

    List<SchemaPO> schemaPOs = listSchemaPOs(namespace);
    return POConverters.fromSchemaPOs(schemaPOs, namespace);
  }

  /**
   * Lists deleted schema roots immediately below one live catalog or parent schema.
   *
   * <p>Recorded cascade descendants are excluded because they do not own a deletion receipt. Legacy
   * tombstones remain visible and are scoped by their logical name because no historic parent
   * receipt exists for them.
   *
   * @param catalogNamespace catalog namespace
   * @param parentSchema optional full logical parent schema name
   * @return matching deleted schema base rows, newest first
   */
  public List<SchemaPO> listDeletedSchemasByParent(
      Namespace catalogNamespace, @Nullable String parentSchema) {
    NamespaceUtil.checkSchema(catalogNamespace);
    NamespacedEntityId catalogIds =
        EntityIdService.getEntityIds(
            NameIdentifier.of(catalogNamespace.levels()), Entity.EntityType.CATALOG);
    long parentId =
        parentSchema == null
            ? catalogIds.entityId()
            : getSchemaPOByIdentifier(NameIdentifier.of(catalogNamespace, parentSchema))
                .getSchemaId();
    return SessionUtils.getWithoutCommit(
            SchemaRecoveryMapper.class,
            mapper -> mapper.listDeletedRootSchemas(catalogIds.entityId(), parentId))
        .stream()
        .map(SchemaMetaService::physicalToLogicalSchemaPO)
        .filter(schema -> Objects.equals(parentSchema, immediateParentName(schema.getSchemaName())))
        .collect(Collectors.toList());
  }

  /**
   * Lists live schemas immediately below one catalog or parent schema.
   *
   * @param catalogNamespace catalog namespace
   * @param parentSchema optional full logical parent schema name
   * @return matching live schema rows
   */
  public List<SchemaPO> listLiveSchemasByParent(
      Namespace catalogNamespace, @Nullable String parentSchema) {
    NamespaceUtil.checkSchema(catalogNamespace);
    long catalogId =
        EntityIdService.getEntityId(
            NameIdentifier.of(catalogNamespace.levels()), Entity.EntityType.CATALOG);
    return SessionUtils.getWithoutCommit(
            SchemaRecoveryMapper.class, mapper -> mapper.listLiveSchemas(catalogId))
        .stream()
        .map(SchemaMetaService::physicalToLogicalSchemaPO)
        .filter(schema -> Objects.equals(parentSchema, immediateParentName(schema.getSchemaName())))
        .collect(Collectors.toList());
  }

  /**
   * Lists globally live schema rows matching candidate immutable IDs.
   *
   * @param schemaIds candidate schema IDs
   * @return matching live schema rows
   */
  public List<SchemaPO> listLiveSchemasByIds(List<Long> schemaIds) {
    if (schemaIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
            SchemaRecoveryMapper.class, mapper -> mapper.listLiveSchemasByIds(schemaIds))
        .stream()
        .map(SchemaMetaService::physicalToLogicalSchemaPO)
        .collect(Collectors.toList());
  }

  /**
   * Resolves the immutable immediate-parent ID of a live logical schema name.
   *
   * @param catalogId immutable catalog identifier
   * @param logicalSchemaName full logical schema name
   * @return catalog ID for a top-level schema, or the live parent schema ID for a nested schema
   */
  public long resolveLiveSchemaParentId(long catalogId, String logicalSchemaName) {
    return resolveImmediateParentId(catalogId, logicalSchemaName);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertSchema")
  public void insertSchema(SchemaEntity schemaEntity, boolean overwrite) throws IOException {
    try {
      NameIdentifierUtil.checkSchema(schemaEntity.nameIdentifier());
      // SchemaEntity arrives in API/logical form (separator = HierarchicalSchemaUtil
      // .schemaSeparator()). We split here on the logical separator and build ancestor rows in
      // logical form. HierarchicalConversionPOStorageOps.batchInsertPOs applies its write
      // rewriter to translate each PO's name to storage form before SQL execution.
      String logicalSep = HierarchicalSchemaUtil.schemaSeparator();
      String schemaName = schemaEntity.name();
      List<SchemaEntity> rowsToInsert = new ArrayList<>();
      if (schemaName == null || !schemaName.contains(logicalSep)) {
        rowsToInsert.add(schemaEntity);
      } else {
        // Segments of the logical name; e.g. "A:B:C" -> ancestor rows "A", "A:B", then leaf.
        String[] parts = schemaName.split(Pattern.quote(logicalSep), -1);
        for (int nSeg = 1; nSeg < parts.length; nSeg++) {
          String ancestorLogical = String.join(logicalSep, Arrays.copyOf(parts, nSeg));
          SchemaEntity ancestor =
              SchemaEntity.builder()
                  .withId(nextIdForNestedAncestor())
                  .withName(ancestorLogical)
                  .withNamespace(schemaEntity.namespace())
                  .withComment(null)
                  .withProperties(Collections.emptyMap())
                  .withAuditInfo(schemaEntity.auditInfo())
                  .build();
          rowsToInsert.add(ancestor);
        }
        rowsToInsert.add(schemaEntity);
      }

      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  SchemaRecoveryMapper.class,
                  recoveryMapper -> {
                    int n = rowsToInsert.size();
                    SchemaEntity leafRow = rowsToInsert.get(n - 1);
                    SchemaPO.Builder leafBuilder = SchemaPO.builder();
                    fillSchemaPOBuilderParentEntityId(leafBuilder, leafRow.namespace());
                    SchemaPO leafPO =
                        POConverters.initializeSchemaPOWithVersion(leafRow, leafBuilder);
                    lockLiveCatalog(recoveryMapper, leafPO.getCatalogId());

                    List<SchemaPO> missingAncestorPOs = new ArrayList<>();
                    if (n > 1) {
                      SchemaEntity firstAncestor = rowsToInsert.get(0);
                      Namespace ancestorNs = firstAncestor.namespace();
                      List<String> ancestorNames =
                          rowsToInsert.subList(0, n - 1).stream()
                              .map(SchemaEntity::name)
                              .collect(Collectors.toList());
                      Set<String> existingLogicalNames =
                          SessionUtils.getWithoutCommit(
                                  SchemaMetaMapper.class,
                                  mapper -> ops.listPOs(mapper, ancestorNs, ancestorNames))
                              .stream()
                              .map(SchemaPO::getSchemaName)
                              .collect(Collectors.toSet());
                      for (SchemaEntity row : rowsToInsert.subList(0, n - 1)) {
                        if (existingLogicalNames.contains(row.name())) {
                          continue;
                        }
                        SchemaPO.Builder builder = SchemaPO.builder();
                        fillSchemaPOBuilderParentEntityId(builder, row.namespace());
                        missingAncestorPOs.add(
                            POConverters.initializeSchemaPOWithVersion(row, builder));
                      }
                    }

                    List<SchemaPO> schemaPOsToInsert = new ArrayList<>(missingAncestorPOs);
                    schemaPOsToInsert.add(leafPO);
                    if (overwrite
                        && !recoveryMapper
                            .selectRecordedDeletedSchemasForUpdate(
                                schemaPOsToInsert.stream()
                                    .map(SchemaPO::getSchemaId)
                                    .collect(Collectors.toList()))
                            .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "A schema ID belongs to a recoverable deletion; use metadata restore");
                    }
                    SessionUtils.doWithoutCommit(
                        SchemaMetaMapper.class,
                        mapper -> ops.batchInsertPOs(mapper, schemaPOsToInsert, overwrite));
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.SCHEMA, schemaEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateSchema")
  public <E extends Entity & HasIdentifier> SchemaEntity updateSchema(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    SchemaPO oldSchemaPO = getSchemaPOByIdentifier(identifier);
    SchemaEntity oldSchemaEntity = POConverters.fromSchemaPO(oldSchemaPO, identifier.namespace());
    SchemaEntity newEntity = (SchemaEntity) updater.apply((E) oldSchemaEntity);
    Preconditions.checkArgument(
        Objects.equals(oldSchemaEntity.id(), newEntity.id()),
        "The updated schema entity id: %s should be same with the schema entity id before: %s",
        newEntity.id(),
        oldSchemaEntity.id());

    String metalakeName = identifier.namespace().level(0);
    String catalogName = identifier.namespace().level(1);
    String oldFullName =
        NameIdentifierUtil.ofSchema(metalakeName, catalogName, oldSchemaEntity.name()).toString();
    boolean isRenamed = !Objects.equals(oldSchemaEntity.name(), newEntity.name());

    AtomicInteger updateResult = new AtomicInteger(0);
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              updateResult.set(
                  SessionUtils.getWithoutCommit(
                      SchemaRecoveryMapper.class,
                      recoveryMapper -> {
                        lockLiveCatalog(recoveryMapper, oldSchemaPO.getCatalogId());
                        lockLiveSchemas(
                            recoveryMapper, Collections.singletonList(oldSchemaPO.getSchemaId()));
                        return SessionUtils.getWithoutCommit(
                            SchemaMetaMapper.class,
                            mapper ->
                                ops.updatePO(
                                    mapper,
                                    POConverters.updateSchemaPOWithVersion(oldSchemaPO, newEntity),
                                    oldSchemaPO));
                      })),
          () -> {
            if (isRenamed && updateResult.get() > 0) {
              SessionUtils.doWithoutCommit(
                  EntityChangeLogMapper.class,
                  mapper ->
                      mapper.insertEntityChange(
                          metalakeName,
                          Entity.EntityType.SCHEMA.name(),
                          oldFullName,
                          OperateType.ALTER));
            }
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.SCHEMA, newEntity.nameIdentifier().toString());
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
      baseMetricName = "deleteSchema")
  public boolean deleteSchema(NameIdentifier identifier, boolean cascade) {
    return deleteSchema(identifier, cascade, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes one schema metadata root and, when requested, its live descendant tree.
   *
   * @param identifier schema identifier in logical form
   * @param cascade whether to include live descendants and leaf aggregates
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live schema root was deleted
   */
  public boolean deleteSchema(NameIdentifier identifier, boolean cascade, long retentionMs) {
    return deleteSchema(identifier, cascade, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes one exact schema-tree snapshot with a shared timestamp and generation token.
   *
   * @param identifier schema identifier in logical form
   * @param cascade whether to include live descendants and leaf aggregates
   * @param requestedDeletedAt requested deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live schema root was deleted
   */
  public boolean deleteSchema(
      NameIdentifier identifier, boolean cascade, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkSchema(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    SchemaPO observedRoot = getSchemaPOByIdentifier(identifier);
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                SchemaRecoveryMapper.class,
                recoveryMapper -> {
                  lockLiveCatalog(recoveryMapper, observedRoot.getCatalogId());
                  List<SchemaPO> tree = listLiveSchemaTree(observedRoot);
                  SchemaPO root = findExactRoot(tree, observedRoot, identifier);
                  long parentId = resolveImmediateParentId(root);

                  List<Long> schemaIds =
                      tree.stream()
                          .map(SchemaPO::getSchemaId)
                          .sorted()
                          .collect(Collectors.toList());
                  List<Long> rowsToLock = new ArrayList<>(schemaIds);
                  if (parentId != root.getCatalogId() && !rowsToLock.contains(parentId)) {
                    rowsToLock.add(parentId);
                    Collections.sort(rowsToLock);
                  }
                  lockLiveSchemas(recoveryMapper, rowsToLock);

                  if (!cascade
                      && (tree.size() != 1
                          || recoveryMapper.countLiveLeafEntities(schemaIds) != 0)) {
                    throw new NonEmptyEntityException(
                        "Entity %s has sub-entities, you should remove sub-entities first",
                        identifier);
                  }

                  long deletedAt =
                      chooseAggregateDeletedAt(
                          recoveryMapper, root.getCatalogId(), schemaIds, requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.SCHEMA,
                              root.getSchemaId(),
                              root.getMetalakeId(),
                              root.getCatalogId(),
                              parentId,
                              root.getSchemaName(),
                              root.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  for (SchemaAggregateTable aggregateTable : SchemaAggregateTable.values()) {
                    int changed =
                        recoveryMapper.softDeleteAggregateRows(
                            aggregateTable, schemaIds, deletedAt, deletion.getDeletionId());
                    if (aggregateTable == SchemaAggregateTable.SCHEMA) {
                      deleted.set(changed);
                      if (changed != tree.size()) {
                        throw tombstoneChanged(deletion.getDeletionId());
                      }
                    }
                  }
                  if (recoveryMapper.countMissingRequiredDetails(
                              deletedAt, deletion.getDeletionId())
                          != 0
                      || recoveryMapper.countBrokenExternalReferences(
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
                              Entity.EntityType.SCHEMA.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() > 0;
  }

  /**
   * Restores one exact schema metadata deletion generation transactionally.
   *
   * <p>A cascade restore revives every metadata row carrying the root deletion token. It never
   * invokes or validates a catalog connector or downstream system.
   *
   * @param identifier original full logical schema identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored root schema entity
   */
  public SchemaEntity restoreSchema(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkSchema(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    long catalogId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.CATALOG);
    AtomicReference<SchemaEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  SchemaRecoveryMapper.class,
                  mapper -> {
                    lockLiveCatalog(mapper, catalogId);
                    long parentId = resolveImmediateParentId(catalogId, identifier.name());
                    if (parentId != catalogId) {
                      lockLiveSchemas(mapper, Collections.singletonList(parentId));
                    }
                    if (!Objects.equals(observed.getParentId(), parentId)) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.PARENT_CHANGED,
                          "The original parent changed while restoring %s",
                          identifier);
                    }

                    EntityDeletionPO latest =
                        SessionUtils.getWithoutCommit(
                            EntityDeletionMapper.class,
                            deletionMapper ->
                                deletionMapper.selectLatestEntityDeletion(
                                    Entity.EntityType.SCHEMA.name(), parentId, identifier.name()));
                    if (latest == null
                        || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                          "Deletion generation %s is no longer latest for schema %s",
                          observed.getDeletionId(),
                          identifier);
                    }

                    EntityDeletionPO actual =
                        SessionUtils.getWithoutCommit(
                            EntityDeletionMapper.class,
                            deletionMapper ->
                                deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                    if (isCompletedRestoreReplay(
                        identifier, catalogId, parentId, observed, actual, restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredSchema(identifier, actual));
                      return;
                    }
                    validateDeletionSnapshot(identifier, catalogId, parentId, observed, actual);
                    if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
                      throw new TombstoneExpiredException(
                          "Deletion generation %s expired at %s",
                          observed.getDeletionId(), effectiveExpiresAt);
                    }

                    // Claim the receipt before locking generation rows. GC uses the same
                    // receipt-then-generation order, which avoids a restore/GC deadlock at the
                    // retention boundary. Any later validation failure rolls this transition back
                    // with the rest of the transaction.
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

                    SchemaPO root =
                        mapper.selectSchemaGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateRootGeneration(identifier, actual, root);
                    List<SchemaPO> schemaTree =
                        mapper.listSchemaGenerationForUpdate(
                            actual.getDeletedAt(), actual.getDeletionId());
                    validateSchemaTree(identifier, actual, schemaTree);

                    Map<SchemaAggregateTable, Integer> expectedCounts =
                        generationCounts(mapper, actual);
                    if (expectedCounts.get(SchemaAggregateTable.SCHEMA) != schemaTree.size()
                        || mapper.countMissingRequiredDetails(
                                actual.getDeletedAt(), actual.getDeletionId())
                            != 0
                        || mapper.countBrokenExternalReferences(
                                actual.getDeletedAt(), actual.getDeletionId())
                            != 0) {
                      throw tombstoneChanged(actual.getDeletionId());
                    }

                    for (SchemaAggregateTable aggregateTable : SchemaAggregateTable.values()) {
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
                                Entity.EntityType.SCHEMA.name(),
                                identifier.toString(),
                                OperateType.RESTORE));
                    restored.set(getSchemaByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(
          identifier, catalogId, observed.getParentId(), observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredSchema(identifier, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored schema must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded schema deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  public int purgeExpiredSchemaDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.SCHEMA, legacyTimeline, limit);
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
                SchemaRecoveryMapper.class,
                mapper -> {
                  SchemaPO root =
                      mapper.selectSchemaGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  if (root == null) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }
                  Map<SchemaAggregateTable, Integer> expectedCounts =
                      generationCounts(mapper, actual);
                  if (mapper.countMissingRequiredDetails(
                              actual.getDeletedAt(), actual.getDeletionId())
                          != 0
                      || mapper.countBrokenExternalReferences(
                              actual.getDeletedAt(), actual.getDeletionId())
                          != 0) {
                    throw tombstoneChanged(actual.getDeletionId());
                  }
                  for (SchemaAggregateTable aggregateTable : SchemaAggregateTable.values()) {
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
      baseMetricName = "deleteSchemaMetasByLegacyTimeline")
  public int deleteSchemaMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        SchemaMetaMapper.class,
        mapper -> {
          return mapper.deleteSchemaMetasByLegacyTimeline(legacyTimeline, limit);
        });
  }

  private SchemaPO getSchemaPOByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkSchema(identifier);
    SchemaPO schemaPO =
        SessionUtils.getWithoutCommit(
            SchemaMetaMapper.class,
            mapper ->
                POStorageReadRouting.getPO(mapper, identifier, ops, Entity.EntityType.SCHEMA));
    if (schemaPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          identifier.name());
    }
    return schemaPO;
  }

  private List<SchemaPO> listSchemaPOs(Namespace namespace) {
    return SessionUtils.getWithoutCommit(
        SchemaMetaMapper.class,
        mapper -> POStorageReadRouting.listPOs(mapper, namespace, ops, Entity.EntityType.SCHEMA));
  }

  /**
   * Collects the schema ids that participate in a cascade delete: the target schema itself plus
   * every HierarchicalSchema descendant. The {@link SchemaPO} arrives in logical form (e.g. {@code
   * A:B}); {@link HierarchicalConversionPOStorageOps} translates to storage form before running the
   * SQL prefix match, so this method only deals in logical names.
   */
  private List<Long> listSchemaIdsForCascade(SchemaPO schemaPO) {
    List<SchemaPO> matched =
        SessionUtils.getWithoutCommit(
            SchemaMetaMapper.class,
            mapper ->
                ops.listPOsByNamePrefix(mapper, schemaPO.getCatalogId(), schemaPO.getSchemaName()));
    if (matched == null || matched.isEmpty()) {
      return Collections.emptyList();
    }
    return matched.stream().map(SchemaPO::getSchemaId).collect(Collectors.toList());
  }

  private void fillSchemaPOBuilderParentEntityId(SchemaPO.Builder builder, Namespace namespace) {
    NamespaceUtil.checkSchema(namespace);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.CATALOG);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.entityId());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetSchemaByIdentifier")
  public List<SchemaEntity> batchGetSchemaByIdentifier(List<NameIdentifier> identifiers) {

    NameIdentifier firstIdent = identifiers.get(0);
    NameIdentifier catalogIdent = NameIdentifierUtil.getCatalogIdentifier(firstIdent);
    List<String> schemaNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.getWithoutCommit(
        SchemaMetaMapper.class,
        mapper -> {
          List<SchemaPO> schemaPOs =
              ops.listPOs(
                  mapper,
                  Namespace.of(catalogIdent.namespace().levels()[0], catalogIdent.name()),
                  schemaNames);
          return POConverters.fromSchemaPOs(schemaPOs, firstIdent.namespace());
        });
  }

  public BasePOStorageOps<SchemaPO, SchemaMetaMapper> ops() {
    return ops;
  }

  private static void lockLiveCatalog(SchemaRecoveryMapper mapper, long catalogId) {
    Long lockedCatalogId = mapper.lockLiveCatalog(catalogId);
    if (!Objects.equals(catalogId, lockedCatalogId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.CATALOG.name().toLowerCase(Locale.ROOT),
          catalogId);
    }
  }

  private static void lockLiveSchemas(SchemaRecoveryMapper mapper, List<Long> schemaIds) {
    List<Long> distinct = schemaIds.stream().distinct().sorted().collect(Collectors.toList());
    List<Long> locked = mapper.lockLiveSchemas(distinct);
    if (locked.size() != distinct.size() || !locked.equals(distinct)) {
      throw new TombstoneChangedException("A schema tree changed while being locked");
    }
  }

  private List<SchemaPO> listLiveSchemaTree(SchemaPO root) {
    return SessionUtils.getWithoutCommit(
        SchemaMetaMapper.class,
        mapper -> ops.listPOsByNamePrefix(mapper, root.getCatalogId(), root.getSchemaName()));
  }

  private static SchemaPO findExactRoot(
      List<SchemaPO> tree, SchemaPO observedRoot, NameIdentifier identifier) {
    return tree.stream()
        .filter(
            schema ->
                Objects.equals(schema.getSchemaId(), observedRoot.getSchemaId())
                    && Objects.equals(schema.getSchemaName(), observedRoot.getSchemaName()))
        .findFirst()
        .orElseThrow(
            () -> new TombstoneChangedException("Schema changed while deleting %s", identifier));
  }

  private long resolveImmediateParentId(SchemaPO root) {
    return resolveImmediateParentId(root.getCatalogId(), root.getSchemaName());
  }

  private long resolveImmediateParentId(long catalogId, String logicalSchemaName) {
    String parentName = immediateParentName(logicalSchemaName);
    if (parentName == null) {
      return catalogId;
    }
    SchemaPO parent =
        SessionUtils.getWithoutCommit(
            SchemaMetaMapper.class, mapper -> ops.getPO(mapper, catalogId, parentName));
    if (parent == null) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.PARENT_CHANGED,
          "The immediate parent of schema %s no longer exists",
          logicalSchemaName);
    }
    return parent.getSchemaId();
  }

  private static long chooseAggregateDeletedAt(
      SchemaRecoveryMapper mapper, long catalogId, List<Long> schemaIds, long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestAggregateDeletedAt(catalogId, schemaIds);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  @Nullable
  private static String immediateParentName(String logicalSchemaName) {
    String separator = HierarchicalSchemaUtil.schemaSeparator();
    int split = logicalSchemaName.lastIndexOf(separator);
    return split < 0 ? null : logicalSchemaName.substring(0, split);
  }

  private static Map<SchemaAggregateTable, Integer> generationCounts(
      SchemaRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<SchemaAggregateTable, Integer> counts = new EnumMap<>(SchemaAggregateTable.class);
    for (SchemaAggregateTable aggregateTable : SchemaAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static int restoreGenerationRows(
      SchemaRecoveryMapper mapper,
      SchemaAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.SCHEMA, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live metadata row already occupies part of schema tree %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier,
      long catalogId,
      long parentId,
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
            && Entity.EntityType.SCHEMA.name().equals(actual.getEntityType())
            && Objects.equals(actual.getCatalogId(), catalogId)
            && Objects.equals(actual.getParentId(), parentId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      long catalogId,
      long parentId,
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
        && Objects.equals(actual.getCatalogId(), catalogId)
        && Objects.equals(actual.getParentId(), parentId)
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
      NameIdentifier identifier, EntityDeletionPO deletion, @Nullable SchemaPO physicalRoot) {
    SchemaPO root = physicalRoot == null ? null : physicalToLogicalSchemaPO(physicalRoot);
    if (root == null
        || !Objects.equals(root.getSchemaId(), deletion.getEntityId())
        || !Objects.equals(root.getCatalogId(), deletion.getCatalogId())
        || !Objects.equals(root.getSchemaName(), identifier.name())
        || !Objects.equals(root.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(root.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(root.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static void validateSchemaTree(
      NameIdentifier identifier, EntityDeletionPO deletion, List<SchemaPO> physicalTree) {
    String rootName = identifier.name();
    String descendantPrefix = rootName + HierarchicalSchemaUtil.schemaSeparator();
    List<SchemaPO> schemaTree =
        physicalTree.stream()
            .map(SchemaMetaService::physicalToLogicalSchemaPO)
            .collect(Collectors.toList());
    Set<String> schemaNames =
        schemaTree.stream().map(SchemaPO::getSchemaName).collect(Collectors.toSet());
    if (schemaNames.size() != schemaTree.size()) {
      throw tombstoneChanged(deletion.getDeletionId());
    }

    long rootCount = 0;
    for (SchemaPO schema : schemaTree) {
      if (!Objects.equals(schema.getMetalakeId(), deletion.getMetalakeId())
          || !Objects.equals(schema.getCatalogId(), deletion.getCatalogId())
          || !Objects.equals(schema.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(schema.getDeletionId(), deletion.getDeletionId())
          || (!schema.getSchemaName().equals(rootName)
              && !schema.getSchemaName().startsWith(descendantPrefix))) {
        throw tombstoneChanged(deletion.getDeletionId());
      }
      if (schema.getSchemaName().equals(rootName)
          && Objects.equals(schema.getSchemaId(), deletion.getEntityId())) {
        rootCount++;
      } else if (!schemaNames.contains(immediateParentName(schema.getSchemaName()))) {
        throw tombstoneChanged(deletion.getDeletionId());
      }
    }
    if (rootCount != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static SchemaEntity loadIdempotentlyRestoredSchema(
      NameIdentifier identifier, EntityDeletionPO deletion) {
    SchemaEntity live;
    try {
      live = getInstance().getSchemaByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Schema ID %s is active under a different logical schema",
          deletion.getEntityId());
    }
    return live;
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private static long nextIdForNestedAncestor() {
    IdGenerator generator = GravitinoEnv.getInstance().idGenerator();
    if (generator == null) {
      throw new IllegalStateException(
          "IdGenerator is not initialized in GravitinoEnv; ensure it is set up before inserting nested schemas");
    }
    return generator.nextId();
  }

  private static SchemaPO physicalToLogicalSchemaPO(SchemaPO po) {
    String name = po.getSchemaName();
    if (name == null || !name.contains(HierarchicalSchemaUtil.physicalSeparator())) {
      return po;
    }
    return copySchemaPOWithName(
        po,
        HierarchicalSchemaUtil.physicalToLogical(name, HierarchicalSchemaUtil.schemaSeparator()));
  }

  private static SchemaPO logicalToPhysicalSchemaPO(SchemaPO po) {
    String name = po.getSchemaName();
    if (name == null || !name.contains(HierarchicalSchemaUtil.schemaSeparator())) {
      return po;
    }
    return copySchemaPOWithName(
        po,
        HierarchicalSchemaUtil.logicalToPhysical(name, HierarchicalSchemaUtil.schemaSeparator()));
  }

  private static SchemaPO copySchemaPOWithName(SchemaPO po, String name) {
    return SchemaPO.builder()
        .withSchemaId(po.getSchemaId())
        .withSchemaName(name)
        .withMetalakeId(po.getMetalakeId())
        .withCatalogId(po.getCatalogId())
        .withSchemaComment(po.getSchemaComment())
        .withProperties(po.getProperties())
        .withAuditInfo(po.getAuditInfo())
        .withCurrentVersion(po.getCurrentVersion())
        .withLastVersion(po.getLastVersion())
        .withDeletedAt(po.getDeletedAt())
        .withDeletionId(po.getDeletionId())
        .build();
  }
}
