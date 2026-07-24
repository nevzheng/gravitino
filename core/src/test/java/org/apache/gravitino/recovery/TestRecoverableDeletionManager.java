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
package org.apache.gravitino.recovery;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.mapper.TableMetaMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.SchemaMetaService;
import org.apache.gravitino.storage.relational.service.TableMetaService;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

public class TestRecoverableDeletionManager extends TestJDBCBackend {

  private static final long RETENTION_MS = 604_800_000L;
  private static final String METALAKE = "recovery_metalake";
  private static final String CATALOG = "recovery_catalog";
  private static final String SCHEMA = "recovery_schema";

  @BeforeEach
  public void initializeLockManager() throws IllegalAccessException {
    Config config = GravitinoEnv.getInstance().config();
    Mockito.when(config.get(TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100_000L);
    Mockito.when(config.get(TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1_000L);
    Mockito.when(config.get(TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36_000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @TestTemplate
  public void testListsRecordedAndLegacyTableTombstones() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);

    TableEntity recoverable = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(recoverable, false);
    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(recoverable.nameIdentifier(), RETENTION_MS));

    TablePO recoverableTombstone =
        TableMetaService.getInstance().listDeletedTablesByNamespace(namespace).get(0);
    EntityDeletionPO deletionRecord =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE,
                recoverableTombstone.getSchemaId(),
                "orders",
                recoverable.id(),
                null)
            .get(0);

    TableEntity legacy = newTable(namespace, "legacy_orders");
    TableMetaService.getInstance().insertTable(legacy, false);
    SessionUtils.doWithCommit(
        TableMetaMapper.class, mapper -> mapper.softDeleteTableMetasByTableId(legacy.id()));

    Clock beforeExpiry =
        Clock.fixed(
            Instant.ofEpochMilli(recoverableTombstone.getDeletedAt() + 1_000L), ZoneOffset.UTC);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS, beforeExpiry);
    List<DeletedEntityDTO> deleted = manager.listDeletedTables(namespace, null, null);

    Assertions.assertEquals(2, deleted.size());
    DeletedEntityDTO recoverableDTO =
        deleted.stream()
            .filter(item -> item.getId().equals(String.valueOf(recoverable.id())))
            .findFirst()
            .orElseThrow();
    recoverableDTO.validate();
    Assertions.assertEquals(deletionRecord.getDeletionId(), recoverableDTO.getDeletionId());
    Assertions.assertNotNull(recoverableDTO.getDeletedBy());
    Assertions.assertTrue(recoverableDTO.getLatestForName());
    Assertions.assertTrue(recoverableDTO.getRestorable());
    Assertions.assertNull(recoverableDTO.getReason());

    DeletedEntityDTO legacyDTO =
        deleted.stream()
            .filter(item -> item.getId().equals(String.valueOf(legacy.id())))
            .findFirst()
            .orElseThrow();
    legacyDTO.validate();
    Assertions.assertFalse(legacyDTO.getRestorable());
    Assertions.assertEquals(RecoverableDeletionManager.LEGACY_TOMBSTONE, legacyDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.LEGACY_TOMBSTONE,
        () ->
            manager.restoreDeletedTable(
                namespace, legacy.name(), legacy.id(), legacyDTO.getEtag()));

    Assertions.assertEquals(
        List.of(recoverableDTO), manager.listDeletedTables(namespace, "orders", recoverable.id()));
    Assertions.assertEquals(
        recoverableDTO, manager.getDeletedTable(namespace, "orders", recoverable.id()));
    Assertions.assertThrows(
        TombstoneNotFoundException.class,
        () -> manager.getDeletedTable(namespace, "wrong-name", recoverable.id()));
    Assertions.assertTrue(manager.listDeletedTables(namespace, "missing", null).isEmpty());
  }

  @TestTemplate
  public void testLiveNameAndExpiryBlockRestore() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);

    TableEntity deletedTable = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(deletedTable, false);
    TableMetaService.getInstance().deleteTable(deletedTable.nameIdentifier(), RETENTION_MS);
    TablePO tombstone =
        TableMetaService.getInstance().listDeletedTablesByNamespace(namespace).get(0);
    EntityDeletionPO deletionRecord =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE, tombstone.getSchemaId(), "orders", deletedTable.id(), null)
            .get(0);

    TableEntity replacement = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(replacement, false);
    RecoverableDeletionManager beforeExpiry =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(tombstone.getDeletedAt() + 1L), ZoneOffset.UTC));
    DeletedEntityDTO occupied =
        beforeExpiry.listDeletedTables(namespace, null, deletedTable.id()).get(0);
    Assertions.assertFalse(occupied.getRestorable());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());

    TableMetaService.getInstance().deleteTable(replacement.nameIdentifier());
    RecoverableDeletionManager afterExpiry =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(deletionRecord.getExpiresAt()), ZoneOffset.UTC));
    DeletedEntityDTO expired =
        afterExpiry.listDeletedTables(namespace, null, deletedTable.id()).get(0);
    Assertions.assertFalse(expired.getRestorable());
    Assertions.assertEquals("TOMBSTONE_EXPIRED", expired.getReason());
  }

  @TestTemplate
  public void testConditionalRestoreIsIdempotentAcrossRetentionConfigChanges() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    long deletedAt = Instant.now().toEpochMilli();

    TableEntity table = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(table, false);
    EntityDeletionPO deletion = deleteAndGetDeletionRecord(table, deletedAt, RETENTION_MS);
    EntityCache entityCache = Mockito.mock(EntityCache.class);
    RecoverableDeletionManager firstManager =
        new RecoverableDeletionManager(RETENTION_MS, entityCache);

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            firstManager.restoreDeletedTable(
                namespace,
                table.name(),
                table.id(),
                "deletion-stale-representation-0000000000000000000000000000000000000000000000000000000000000000"));

    String etag = firstManager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    TableEntity restored =
        firstManager.restoreDeletedTable(namespace, table.name(), table.id(), etag);
    Assertions.assertEquals(table.id(), restored.id());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(etag, receipt.getRestoreEtag());

    RecoverableDeletionManager restartedManager =
        new RecoverableDeletionManager(RETENTION_MS * 2, entityCache);
    Assertions.assertEquals(
        table.id(),
        restartedManager.restoreDeletedTable(namespace, table.name(), table.id(), etag).id());
    Assertions.assertTrue(
        restartedManager.listDeletedTables(namespace, table.name(), table.id()).isEmpty());
    Mockito.verify(entityCache, Mockito.times(2))
        .invalidate(table.nameIdentifier(), Entity.EntityType.TABLE);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Mockito.verify(entityCache, Mockito.times(2))
          .invalidateRelationEntry(table.nameIdentifier(), Entity.EntityType.TABLE, relationType);
    }
  }

  @TestTemplate
  public void testImmutableTableIdReuseIsDetectedAcrossNamespaces() throws Exception {
    long deletedAt = 1_800_000_000_000L;
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    String reuseSchema = SCHEMA + "_reuse";
    createAndInsertSchema(METALAKE, CATALOG, reuseSchema);
    Namespace originalNamespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    Namespace reusedNamespace = NamespaceUtil.ofTable(METALAKE, CATALOG, reuseSchema);

    TableEntity deletedTable = newTable(originalNamespace, "orders");
    TableMetaService.getInstance().insertTable(deletedTable, false);
    deleteAndGetDeletionRecord(deletedTable, deletedAt, RETENTION_MS);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(deletedAt + 1_000L), ZoneOffset.UTC));
    String observedEtag =
        manager
            .getDeletedTable(originalNamespace, deletedTable.name(), deletedTable.id())
            .getEtag();

    TableEntity reusedTable =
        createTableEntity(deletedTable.id(), reusedNamespace, "reused_orders", AUDIT_INFO);
    TableMetaService.getInstance().insertTable(reusedTable, true);

    Assertions.assertTrue(
        manager
            .listDeletedTables(originalNamespace, deletedTable.name(), deletedTable.id())
            .isEmpty());
    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () ->
            manager.restoreDeletedTable(
                originalNamespace, deletedTable.name(), deletedTable.id(), observedEtag));
  }

  @TestTemplate
  public void testEntityTagCoversHiddenDeletionRecordState() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    long deletedAt = 1_800_000_000_000L;
    TableEntity table = newTable(namespace, "deletion_record_changed_orders");
    TableMetaService.getInstance().insertTable(table, false);
    EntityDeletionPO deletionRecord = deleteAndGetDeletionRecord(table, deletedAt, RETENTION_MS);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(deletedAt + 1_000L), ZoneOffset.UTC));
    String observedEtag = manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();

    updateDeletionRecord(
        "UPDATE entity_deletion SET expires_at = ? WHERE deletion_id = ?",
        deletionRecord.getExpiresAt() + 1L,
        deletionRecord.getDeletionId());
    String changedStoredExpiryEtag =
        manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    Assertions.assertNotEquals(observedEtag, changedStoredExpiryEtag);
    updateDeletionRecord(
        "UPDATE entity_deletion SET expires_at = ? WHERE deletion_id = ?",
        deletionRecord.getExpiresAt(),
        deletionRecord.getDeletionId());
    Assertions.assertEquals(
        observedEtag, manager.getDeletedTable(namespace, table.name(), table.id()).getEtag());

    updateDeletionRecord(
        "UPDATE entity_deletion SET revision = ? WHERE deletion_id = ?",
        deletionRecord.getRevision() + 1L,
        deletionRecord.getDeletionId());

    String changedEtag = manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    Assertions.assertNotEquals(observedEtag, changedEtag);
    Assertions.assertThrows(
        TombstoneChangedException.class,
        () -> manager.restoreDeletedTable(namespace, table.name(), table.id(), observedEtag));
  }

  @TestTemplate
  public void testRestoreFailurePrecedence() throws Exception {
    long now = 1_800_000_000_000L;
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));

    TableEntity expiredTable = newTable(namespace, "expired_orders");
    TableMetaService.getInstance().insertTable(expiredTable, false);
    EntityDeletionPO expired =
        deleteAndGetDeletionRecord(expiredTable, now - RETENTION_MS, RETENTION_MS * 2);
    DeletedEntityDTO expiredDTO =
        manager.getDeletedTable(namespace, expiredTable.name(), expiredTable.id());
    Assertions.assertTrue(expired.getExpiresAt() > now);
    Assertions.assertEquals(now, expiredDTO.getExpiresAt());
    Assertions.assertThrows(
        TombstoneExpiredException.class,
        () ->
            manager.restoreDeletedTable(
                namespace, expiredTable.name(), expiredTable.id(), expiredDTO.getEtag()));

    TableEntity occupiedTable = newTable(namespace, "occupied_orders");
    TableMetaService.getInstance().insertTable(occupiedTable, false);
    deleteAndGetDeletionRecord(occupiedTable, now - 1_000L, RETENTION_MS);
    String beforeOccupancy =
        manager.getDeletedTable(namespace, occupiedTable.name(), occupiedTable.id()).getEtag();
    TableMetaService.getInstance().insertTable(newTable(namespace, occupiedTable.name()), false);
    DeletedEntityDTO occupiedDTO =
        manager.getDeletedTable(namespace, occupiedTable.name(), occupiedTable.id());
    Assertions.assertNotEquals(beforeOccupancy, occupiedDTO.getEtag());
    Assertions.assertEquals("NAME_OCCUPIED", occupiedDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () ->
            manager.restoreDeletedTable(
                namespace, occupiedTable.name(), occupiedTable.id(), beforeOccupancy));

    TableEntity oldTable = newTable(namespace, "repeated_orders");
    TableMetaService.getInstance().insertTable(oldTable, false);
    deleteAndGetDeletionRecord(oldTable, now - 2_000L, RETENTION_MS);
    TableEntity newTable = newTable(namespace, oldTable.name());
    TableMetaService.getInstance().insertTable(newTable, false);
    deleteAndGetDeletionRecord(newTable, now - 1_000L, RETENTION_MS);
    DeletedEntityDTO oldGenerationDTO =
        manager.getDeletedTable(namespace, oldTable.name(), oldTable.id());
    Assertions.assertFalse(oldGenerationDTO.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", oldGenerationDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () ->
            manager.restoreDeletedTable(
                namespace, oldTable.name(), oldTable.id(), oldGenerationDTO.getEtag()));

    TableEntity changedParentTable = newTable(namespace, "parent_changed_orders");
    TableMetaService.getInstance().insertTable(changedParentTable, false);
    deleteAndGetDeletionRecord(changedParentTable, now - 1_000L, RETENTION_MS);
    String changedParentEtag =
        manager
            .getDeletedTable(namespace, changedParentTable.name(), changedParentTable.id())
            .getEtag();
    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(METALAKE, CATALOG, SCHEMA), true));
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    assertRecoveryConflict(
        RecoveryConflictReason.PARENT_CHANGED,
        () ->
            manager.restoreDeletedTable(
                namespace, changedParentTable.name(), changedParentTable.id(), changedParentEtag));
  }

  private EntityDeletionPO deleteAndGetDeletionRecord(
      TableEntity table, long deletedAt, long retentionMs) {
    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(table.nameIdentifier(), deletedAt, retentionMs));
    return EntityDeletionService.getInstance()
        .list(
            Entity.EntityType.TABLE,
            EntityIdService.getEntityId(
                NameIdentifier.of(table.namespace().levels()), Entity.EntityType.SCHEMA),
            table.name(),
            table.id(),
            null)
        .get(0);
  }

  private static void updateDeletionRecord(String sql, Object... parameters) throws Exception {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private static void assertRecoveryConflict(
      RecoveryConflictReason expectedReason, Executable executable) {
    RecoveryConflictException exception =
        Assertions.assertThrows(RecoveryConflictException.class, executable);
    Assertions.assertEquals(expectedReason, exception.getReason());
  }

  private TableEntity newTable(Namespace namespace, String name) {
    return createTableEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
  }
}
