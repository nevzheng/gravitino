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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.TableMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TableRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.TableVersionMapper;
import org.apache.gravitino.storage.relational.po.ColumnPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/** The service class for table metadata. It provides the basic database operations for table. */
public class TableMetaService {

  /**
   * Message prefix of the {@link java.io.IOException} thrown by {@link #updateTable} when the
   * optimistic-lock CAS matches zero rows (the stored version advanced under a concurrent update).
   * Exposed so callers that retry the lost race (e.g. the Lance repair-on-load path) can recognize
   * the conflict without re-declaring the literal.
   */
  public static final String UPDATE_ENTITY_CONFLICT_MESSAGE_PREFIX =
      "Failed to update the entity: ";

  private static final TableMetaService INSTANCE = new TableMetaService();
  private BasePOStorageOps<TablePO, TableMetaMapper> ops;

  public static TableMetaService getInstance() {
    return INSTANCE;
  }

  private TableMetaService() {
    this.ops = new HierarchicalConversionPOStorageOps<>(new TablePOStorageOps());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTableIdBySchemaIdAndName")
  public Long getTableIdBySchemaIdAndName(Long schemaId, String tableName) {
    TablePO tablePO =
        SessionUtils.getWithoutCommit(
            TableMetaMapper.class, mapper -> ops.getPO(mapper, schemaId, tableName));

    if (tablePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TABLE.name().toLowerCase(),
          tableName);
    }
    return tablePO.getTableId();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTableByIdentifier")
  public TableEntity getTableByIdentifier(NameIdentifier identifier) {
    TablePO tablePO = getTablePOByIdentifier(identifier);

    List<ColumnPO> columnPOs =
        TableColumnMetaService.getInstance()
            .getColumnsByTableIdAndVersion(tablePO.getTableId(), tablePO.getCurrentVersion());

    return POConverters.fromTableAndColumnPOs(tablePO, columnPOs, identifier.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listTablesByNamespace")
  public List<TableEntity> listTablesByNamespace(Namespace namespace) {
    NamespaceUtil.checkTable(namespace);

    List<TablePO> tablePOs = listTablePOs(namespace);
    return POConverters.fromTablePOs(tablePOs, namespace);
  }

  /**
   * Lists tombstoned table rows under a live schema.
   *
   * @param namespace table namespace
   * @return tombstoned table rows ordered newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedTablesByNamespace")
  public List<TablePO> listDeletedTablesByNamespace(Namespace namespace) {
    NamespaceUtil.checkTable(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.doWithCommitAndFetchResult(
        TableMetaMapper.class, mapper -> mapper.listDeletedTablePOsBySchemaId(schemaId));
  }

  /**
   * Lists live table rows matching immutable IDs across every namespace.
   *
   * @param tableIds immutable table identifiers
   * @return matching live table rows
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listLiveTablesByIds")
  public List<TablePO> listLiveTablesByIds(List<Long> tableIds) {
    if (tableIds.isEmpty()) {
      return new ArrayList<>();
    }
    return SessionUtils.doWithCommitAndFetchResult(
        TableMetaMapper.class, mapper -> mapper.listTablePOsByTableIds(tableIds));
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertTable")
  public void insertTable(TableEntity tableEntity, boolean overwrite) throws IOException {
    try {
      NameIdentifierUtil.checkTable(tableEntity.nameIdentifier());

      TablePO.Builder builder = TablePO.builder();
      fillTablePOBuilderParentEntityId(builder, tableEntity.namespace());

      AtomicReference<TablePO> tablePORef = new AtomicReference<>();
      TablePO po = POConverters.initializeTablePOWithVersion(tableEntity, builder);
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  TableMetaMapper.class,
                  mapper -> {
                    tablePORef.set(po);
                    ops.insertPO(mapper, po, overwrite);
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  TableVersionMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertTableVersionOnDuplicateKeyUpdate(po);
                    } else {
                      mapper.insertTableVersion(po);
                    }
                  }),
          () -> {
            // We need to delete the columns first if we want to overwrite the table.
            if (overwrite) {
              TableColumnMetaService.getInstance()
                  .deleteColumnsByTableId(tablePORef.get().getTableId());
            }
          },
          () -> {
            if (tableEntity.columns() != null && !tableEntity.columns().isEmpty()) {
              TableColumnMetaService.getInstance()
                  .insertColumnPOs(tablePORef.get(), tableEntity.columns());
            }
          });

    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.TABLE, tableEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateTable")
  public <E extends Entity & HasIdentifier> TableEntity updateTable(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    TablePO oldTablePO = getTablePOByIdentifier(identifier);
    List<ColumnPO> oldTableColumns =
        TableColumnMetaService.getInstance()
            .getColumnsByTableIdAndVersion(oldTablePO.getTableId(), oldTablePO.getCurrentVersion());
    TableEntity oldTableEntity =
        POConverters.fromTableAndColumnPOs(oldTablePO, oldTableColumns, identifier.namespace());

    TableEntity newTableEntity = (TableEntity) updater.apply((E) oldTableEntity);
    Preconditions.checkArgument(
        Objects.equals(oldTableEntity.id(), newTableEntity.id()),
        "The updated table entity id: %s should be same with the table entity id before: %s",
        newTableEntity.id(),
        oldTableEntity.id());

    boolean isSchemaChanged = !newTableEntity.namespace().equals(oldTableEntity.namespace());
    Long newSchemaId =
        isSchemaChanged
            ? EntityIdService.getEntityId(
                NameIdentifier.of(newTableEntity.namespace().levels()), Entity.EntityType.SCHEMA)
            : oldTablePO.getSchemaId();

    TablePO newTablePO =
        POConverters.updateTablePOWithVersionAndSchemaId(oldTablePO, newTableEntity, newSchemaId);

    String metalakeName = identifier.namespace().level(0);
    String catalogName = identifier.namespace().level(1);
    String schemaName = identifier.namespace().level(2);
    String oldFullName =
        NameIdentifierUtil.ofTable(metalakeName, catalogName, schemaName, oldTableEntity.name())
            .toString();
    boolean isFullNameChanged =
        isSchemaChanged || !Objects.equals(oldTableEntity.name(), newTableEntity.name());

    final AtomicInteger updateResult = new AtomicInteger(0);
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              updateResult.set(
                  SessionUtils.getWithoutCommit(
                      TableMetaMapper.class,
                      mapper -> ops.updatePO(mapper, newTablePO, oldTablePO))),
          () ->
              SessionUtils.doWithoutCommit(
                  TableVersionMapper.class,
                  mapper -> {
                    mapper.softDeleteTableVersionByTableIdAndVersion(
                        oldTablePO.getTableId(), oldTablePO.getCurrentVersion());
                    mapper.insertTableVersionOnDuplicateKeyUpdate(newTablePO);
                  }),
          () -> {
            if (updateResult.get() > 0) {
              TableColumnMetaService.getInstance()
                  .updateColumnPOsFromTableDiff(oldTableEntity, newTableEntity, newTablePO);
            }
          },
          () -> {
            if (isFullNameChanged && updateResult.get() > 0) {
              SessionUtils.doWithoutCommit(
                  EntityChangeLogMapper.class,
                  mapper ->
                      mapper.insertEntityChange(
                          metalakeName,
                          Entity.EntityType.TABLE.name(),
                          oldFullName,
                          OperateType.ALTER));
            }
          });

    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.TABLE, newTableEntity.nameIdentifier().toString());
      throw re;
    }

    if (updateResult.get() > 0) {
      return newTableEntity;
    } else {
      throw new IOException(UPDATE_ENTITY_CONFLICT_MESSAGE_PREFIX + identifier);
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteTable")
  public boolean deleteTable(NameIdentifier identifier) {
    return deleteTable(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a table and records its durable deletion generation in the same transaction.
   *
   * @param identifier table identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live table row was deleted
   */
  public boolean deleteTable(NameIdentifier identifier, long retentionMs) {
    return deleteTable(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a table using one caller-supplied timestamp for every affected metadata row.
   *
   * @param identifier table identifier
   * @param deletedAt deletion-generation timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live table snapshot was deleted
   */
  public boolean deleteTable(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkTable(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);

    String metalakeName = identifier.namespace().level(0);
    String catalogName = identifier.namespace().level(1);
    String schemaName = identifier.namespace().level(2);
    String tableFullName =
        NameIdentifierUtil.ofTable(metalakeName, catalogName, schemaName, identifier.name())
            .toString();

    AtomicInteger deleteResult = new AtomicInteger(0);
    SessionUtils.doMultipleWithCommit(
        () -> {
          SessionUtils.doWithoutCommit(
              TableRecoveryMapper.class,
              mapper -> {
                lockLiveSchema(mapper, schemaId);
                TablePO tablePO = mapper.selectLiveTableForUpdate(schemaId, identifier.name());
                if (tablePO == null) {
                  throw new NoSuchEntityException(
                      NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                      Entity.EntityType.TABLE.name().toLowerCase(),
                      identifier.name());
                }
                long deletionTimestamp =
                    chooseDeletedAt(
                        mapper, tablePO.getTableId(), schemaId, tablePO.getTableName(), deletedAt);
                EntityDeletionPO deletion =
                    EntityDeletionService.getInstance()
                        .newDeletion(
                            Entity.EntityType.TABLE,
                            tablePO.getTableId(),
                            tablePO.getMetalakeId(),
                            tablePO.getCatalogId(),
                            tablePO.getSchemaId(),
                            tablePO.getTableName(),
                            tablePO.getCurrentVersion(),
                            deletionTimestamp,
                            retentionMs,
                            PrincipalUtils.getCurrentUserName());
                int tableMeta =
                    mapper.softDeleteTableMeta(
                        tablePO.getTableId(),
                        tablePO.getSchemaId(),
                        tablePO.getTableName(),
                        tablePO.getCurrentVersion(),
                        deletionTimestamp,
                        deletion.getDeletionId());
                deleteResult.set(tableMeta);
                if (tableMeta == 0) {
                  return;
                }

                mapper.softDeleteOwnerRelations(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                mapper.softDeleteColumns(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                mapper.softDeleteSecurableObjects(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                mapper.softDeleteTagRelations(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                mapper.softDeleteStatistics(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                mapper.softDeletePolicyRelations(
                    tablePO.getTableId(), deletionTimestamp, deletion.getDeletionId());
                int tableVersion =
                    mapper.softDeleteTableVersion(
                        tablePO.getTableId(),
                        tablePO.getCurrentVersion(),
                        deletionTimestamp,
                        deletion.getDeletionId());

                if (tableVersion != 1) {
                  throw new TombstoneChangedException(
                      "The current table version changed while deleting %s", identifier);
                }
                EntityDeletionService.getInstance().insert(deletion);
                SessionUtils.doWithoutCommit(
                    EntityChangeLogMapper.class,
                    changeLogMapper ->
                        changeLogMapper.insertEntityChange(
                            metalakeName,
                            Entity.EntityType.TABLE.name(),
                            tableFullName,
                            OperateType.DROP));
              });
        });

    return deleteResult.get() > 0;
  }

  /**
   * Restores one exact table deletion generation transactionally.
   *
   * @param identifier original table identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry derived from the current global retention configuration
   * @return rebuilt table entity after its affected rows and receipt are restored
   * @throws TombstoneChangedException if the deletion snapshot or base-row CAS changed
   * @throws TombstoneExpiredException if current-global retention expires inside the transaction
   * @throws RecoveryConflictException if restoring the exact deletion would violate an invariant
   */
  public TableEntity restoreTable(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkTable(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<TableEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          Long lockedSchemaId =
              SessionUtils.getWithoutCommit(
                  TableRecoveryMapper.class, mapper -> mapper.lockLiveSchema(schemaId));
          if (!Objects.equals(schemaId, lockedSchemaId)) {
            throw new RecoveryConflictException(
                RecoveryConflictReason.PARENT_CHANGED,
                "The parent schema changed while restoring %s",
                identifier);
          }
          EntityDeletionPO latest =
              SessionUtils.getWithoutCommit(
                  EntityDeletionMapper.class,
                  mapper ->
                      mapper.selectLatestEntityDeletion(
                          Entity.EntityType.TABLE.name(), schemaId, identifier.name()));
          if (latest == null || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
            throw new RecoveryConflictException(
                RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                "Deletion generation %s is no longer latest for table %s",
                observed.getDeletionId(),
                identifier);
          }
          EntityDeletionPO actual =
              SessionUtils.getWithoutCommit(
                  EntityDeletionMapper.class,
                  mapper -> mapper.selectEntityDeletion(observed.getDeletionId()));
          if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
            restored.set(loadIdempotentlyRestoredTable(identifier, schemaId, actual));
            return;
          }
          validateDeletionSnapshot(identifier, schemaId, observed, actual);
          if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
            throw new TombstoneExpiredException(
                "Deletion generation %s expired at %s",
                observed.getDeletionId(), effectiveExpiresAt);
          }

          SessionUtils.doWithoutCommit(
              TableRecoveryMapper.class,
              mapper -> {
                TablePO tableGeneration =
                    mapper.selectTableGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateTableGeneration(actual, tableGeneration);

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

                mapper.restoreOwnerRelations(
                    actual.getEntityId(),
                    actual.getDeletedAt(),
                    actual.getDeletionId(),
                    restoredAt);
                mapper.restoreColumns(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreSecurableObjects(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreTagRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreStatistics(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restorePolicyRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (mapper.restoreTableVersion(
                        actual.getEntityId(),
                        actual.getEntityVersion(),
                        actual.getDeletedAt(),
                        actual.getDeletionId())
                    != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }
                if (restoreTableMeta(mapper, actual, identifier) != 1) {
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
                            Entity.EntityType.TABLE.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getTableByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored table must not be null");
  }

  private static void lockLiveSchema(TableRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      TableRecoveryMapper mapper,
      long tableId,
      long schemaId,
      String tableName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestTableDeletedAt(tableId, schemaId, tableName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static int restoreTableMeta(
      TableRecoveryMapper mapper, EntityDeletionPO deletion, NameIdentifier identifier) {
    try {
      return mapper.restoreTableMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          deletion.getEntityVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.TABLE, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live table already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
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
            && Entity.EntityType.TABLE.name().equals(actual.getEntityType())
            && Objects.equals(actual.getParentId(), schemaId)
            && actual.getEntityName().equals(identifier.name());
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

  private static TableEntity loadIdempotentlyRestoredTable(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    TableEntity liveTable;
    try {
      liveTable = getInstance().getTableByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveTable.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Table ID %s is active under a different logical table",
          deletion.getEntityId());
    }
    return liveTable;
  }

  private static void validateTableGeneration(
      EntityDeletionPO deletion, @Nullable TablePO tableGeneration) {
    if (tableGeneration == null
        || !Objects.equals(tableGeneration.getTableId(), deletion.getEntityId())
        || !Objects.equals(tableGeneration.getSchemaId(), deletion.getParentId())
        || !Objects.equals(tableGeneration.getTableName(), deletion.getEntityName())
        || !Objects.equals(tableGeneration.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(tableGeneration.getDeletedAt(), deletion.getDeletedAt())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded table deletion generations.
   *
   * <p>The deletion-record state claim, exact-generation deletion, and {@code PURGED} receipt are
   * committed in one transaction. A concurrent restore uses the same state compare-and-set, so
   * exactly one operation can mutate the rows affected by the deletion.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredTableDeletions")
  public int purgeExpiredTableDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.TABLE, legacyTimeline, limit);
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
                TableRecoveryMapper.class, mapper -> purgeTableDeletionGeneration(mapper, actual));
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

  private static void purgeTableDeletionGeneration(
      TableRecoveryMapper mapper, EntityDeletionPO deletion) {
    mapper.hardDeleteOwnerRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteSecurableObjects(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    // Tag selection needs the deleted column rows, so tag relations must be removed first.
    mapper.hardDeleteTagRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteStatistics(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeletePolicyRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteColumns(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteTableVersion(
        deletion.getEntityId(),
        deletion.getEntityVersion(),
        deletion.getDeletedAt(),
        deletion.getDeletionId());
    mapper.hardDeleteTableMeta(
        deletion.getEntityId(),
        deletion.getParentId(),
        deletion.getEntityName(),
        deletion.getEntityVersion(),
        deletion.getDeletedAt(),
        deletion.getDeletionId());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteTableMetasByLegacyTimeline")
  public int deleteTableMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
            TableMetaMapper.class,
            mapper -> mapper.deleteTableMetasByLegacyTimeline(legacyTimeline, limit))
        + deleteTableVersionByLegacyTimeline(legacyTimeline, limit);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteTableVersionByLegacyTimeline")
  public int deleteTableVersionByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        TableVersionMapper.class,
        mapper -> mapper.deleteTableVersionByLegacyTimeline(legacyTimeline, limit));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetTableByIdentifier")
  public List<TableEntity> batchGetTableByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    NameIdentifier schemaIdent = NameIdentifierUtil.getSchemaIdentifier(firstIdent);
    List<String> tableNames = new ArrayList<>(identifiers.size());
    tableNames.add(identifiers.get(0).name());
    for (int i = 1; i < identifiers.size(); i++) {
      NameIdentifier ident = identifiers.get(i);
      Preconditions.checkArgument(
          Objects.equals(schemaIdent, NameIdentifierUtil.getSchemaIdentifier(ident)));
      tableNames.add(ident.name());
    }
    return SessionUtils.doWithCommitAndFetchResult(
        TableMetaMapper.class,
        mapper -> {
          List<TablePO> tableList = ops.listPOs(mapper, firstIdent.namespace(), tableNames);
          return POConverters.fromTablePOs(tableList, firstIdent.namespace());
        });
  }

  public BasePOStorageOps<TablePO, TableMetaMapper> ops() {
    return ops;
  }

  private TablePO getTablePOByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkTable(identifier);
    TablePO tablePO =
        SessionUtils.getWithoutCommit(
            TableMetaMapper.class,
            mapper -> POStorageReadRouting.getPO(mapper, identifier, ops, Entity.EntityType.TABLE));
    if (tablePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TABLE.name().toLowerCase(),
          identifier.name());
    }

    return tablePO;
  }

  private List<TablePO> listTablePOs(Namespace namespace) {
    return SessionUtils.getWithoutCommit(
        TableMetaMapper.class,
        mapper -> POStorageReadRouting.listPOs(mapper, namespace, ops, Entity.EntityType.TABLE));
  }

  private void fillTablePOBuilderParentEntityId(TablePO.Builder builder, Namespace namespace) {
    NamespaceUtil.checkTable(namespace);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.namespaceIds()[1]);
    builder.withSchemaId(namespacedEntityId.entityId());
  }
}
