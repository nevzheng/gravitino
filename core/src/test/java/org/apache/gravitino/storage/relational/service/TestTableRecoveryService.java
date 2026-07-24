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

import static org.apache.gravitino.storage.relational.utils.POConverters.INIT_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.mapper.TableMetaMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.TestTemplate;
import org.mockito.Mockito;

public class TestTableRecoveryService extends TestJDBCBackend {

  private static final long DELETED_AT = 1_784_900_000_123L;
  private static final long RETENTION_MS = 604_800_000L;
  private static final long RESTORED_AT = DELETED_AT + 10_000L;
  private static final String RESTORE_ETAG = "deletion-test-representation-restore-etag";

  @TestTemplate
  public void testExactGenerationDeleteAndRestore() throws IOException, SQLException {
    TableFixture fixture = createFixture("exact_restore");
    insertCascadeParticipants(fixture);

    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));

    EntityDeletionPO deletion = getDeletion(fixture);
    assertAllAffectedRowsHaveDeletedAt(fixture, DELETED_AT);
    assertAllAffectedRowsHaveDeletionId(deletion.getDeletionId());

    TableEntity restored =
        TableMetaService.getInstance()
            .restoreTable(
                fixture.table.nameIdentifier(),
                deletion,
                RESTORED_AT,
                RESTORE_ETAG,
                Long.MAX_VALUE);

    assertEquals(fixture.table.id(), restored.id());
    assertEquals(fixture.table.name(), restored.name());
    assertEquals(1, restored.columns().size());
    assertEquals(fixture.column.id(), restored.columns().get(0).id());
    assertAllAffectedRowsAreLive(fixture);
    assertNoRowsHaveDeletionId(deletion.getDeletionId());

    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    assertNotNull(receipt);
    assertEquals(DeletionState.RESTORED, receipt.getState());
    assertEquals(2L, receipt.getRevision());
    assertEquals(RESTORED_AT, receipt.getRestoredAt());
    assertEquals(RESTORE_ETAG, receipt.getRestoreEtag());
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM entity_change_log WHERE metalake_name = ?"
                + " AND entity_type = 'TABLE' AND entity_full_name = ? AND operate_type = 4",
            fixture.metalake.name(),
            fixture.table.nameIdentifier().toString()));
  }

  @TestTemplate
  public void testCompletedConcurrentRestoreReplaysAcceptedEtag() throws IOException, SQLException {
    TableFixture fixture = createFixture("concurrent_restore_replay");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);

    TableEntity firstRestore =
        TableMetaService.getInstance()
            .restoreTable(
                fixture.table.nameIdentifier(),
                observed,
                RESTORED_AT,
                RESTORE_ETAG,
                Long.MAX_VALUE);
    TableEntity concurrentReplay =
        TableMetaService.getInstance()
            .restoreTable(
                fixture.table.nameIdentifier(),
                observed,
                RESTORED_AT + 1L,
                RESTORE_ETAG,
                Long.MAX_VALUE);

    assertEquals(firstRestore.id(), concurrentReplay.id());
    assertEquals(firstRestore.name(), concurrentReplay.name());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(observed.getDeletionId());
    assertNotNull(receipt);
    assertEquals(DeletionState.RESTORED, receipt.getState());
    assertEquals(2L, receipt.getRevision());
    assertEquals(RESTORED_AT, receipt.getRestoredAt());
    assertEquals(RESTORE_ETAG, receipt.getRestoreEtag());
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM entity_change_log WHERE metalake_name = ?"
                + " AND entity_type = 'TABLE' AND entity_full_name = ? AND operate_type = 4",
            fixture.metalake.name(),
            fixture.table.nameIdentifier().toString()));

    assertThrows(
        TombstoneChangedException.class,
        () ->
            TableMetaService.getInstance()
                .restoreTable(
                    fixture.table.nameIdentifier(),
                    observed,
                    RESTORED_AT + 2L,
                    RESTORE_ETAG + "-different",
                    Long.MAX_VALUE));
  }

  @TestTemplate
  public void testCompletedRestoreReplayDoesNotCrossNewerGeneration() throws IOException {
    TableFixture fixture = createFixture("concurrent_restore_newer_generation");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO firstDeletion = getDeletion(fixture);
    TableMetaService.getInstance()
        .restoreTable(
            fixture.table.nameIdentifier(),
            firstDeletion,
            RESTORED_AT,
            RESTORE_ETAG,
            Long.MAX_VALUE);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT + 1L, RETENTION_MS));

    RecoveryConflictException conflict =
        assertThrows(
            RecoveryConflictException.class,
            () ->
                TableMetaService.getInstance()
                    .restoreTable(
                        fixture.table.nameIdentifier(),
                        firstDeletion,
                        RESTORED_AT + 1L,
                        RESTORE_ETAG,
                        Long.MAX_VALUE));
    assertEquals(RecoveryConflictReason.NOT_LATEST_TOMBSTONE, conflict.getReason());
  }

  @TestTemplate
  public void testRestoreDoesNotReviveIndependentRelationWithMatchingDeletedAt()
      throws IOException, SQLException {
    TableFixture fixture = createFixture("independent_relation");
    long independentlyDeletedRoleId = RandomIdGenerator.INSTANCE.nextId();
    long independentlyDeletedPolicyId = RandomIdGenerator.INSTANCE.nextId();
    String independentDeletionId = "independent-deletion-generation";
    update(
        "INSERT INTO role_meta_securable_object (role_id, metadata_object_id, type,"
            + " privilege_names, privilege_conditions, deleted_at, deletion_id)"
            + " VALUES (?, ?, 'TABLE', '[]', '[]', ?, ?)",
        independentlyDeletedRoleId,
        fixture.table.id(),
        DELETED_AT,
        independentDeletionId);
    update(
        "INSERT INTO policy_relation_meta (policy_id, metadata_object_id,"
            + " metadata_object_type, audit_info, deleted_at)"
            + " VALUES (?, ?, 'TABLE', '{}', ?)",
        independentlyDeletedPolicyId,
        fixture.table.id(),
        DELETED_AT);
    insertCascadeParticipants(fixture);

    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO deletion = getDeletion(fixture);

    TableMetaService.getInstance()
        .restoreTable(
            fixture.table.nameIdentifier(), deletion, RESTORED_AT, RESTORE_ETAG, Long.MAX_VALUE);
    assertNoRowsHaveDeletionId(deletion.getDeletionId());

    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM role_meta_securable_object WHERE role_id = ?"
                + " AND metadata_object_id = ? AND deleted_at = ? AND deletion_id = ?",
            independentlyDeletedRoleId,
            fixture.table.id(),
            DELETED_AT,
            independentDeletionId));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM policy_relation_meta WHERE policy_id = ?"
                + " AND metadata_object_id = ? AND deleted_at = ? AND deletion_id IS NULL",
            independentlyDeletedPolicyId,
            fixture.table.id(),
            DELETED_AT));
  }

  @TestTemplate
  public void testDeleteReadsTableOnlyAfterEnteringTransactionalLock() throws Exception {
    TableFixture fixture = createFixture("transactional_snapshot");
    TableMetaService tableMetaService = TableMetaService.getInstance();
    BasePOStorageOps<TablePO, TableMetaMapper> originalOps = tableMetaService.ops();
    BasePOStorageOps<TablePO, TableMetaMapper> observedOps = Mockito.spy(originalOps);
    FieldUtils.writeField(tableMetaService, "ops", observedOps, true);
    try {
      assertTrue(
          tableMetaService.deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
      Mockito.verifyNoInteractions(observedOps);
      assertEquals(INIT_VERSION, getDeletion(fixture).getEntityVersion());
    } finally {
      FieldUtils.writeField(tableMetaService, "ops", originalOps, true);
    }
  }

  @TestTemplate
  public void testRestoreAndRedropInSameMillisecondUsesNewGenerationTimestamp()
      throws IOException, SQLException {
    TableFixture fixture = createFixture("same_millisecond_redrop");
    insertCascadeParticipants(fixture);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO firstDeletion = getDeletion(fixture);
    TableMetaService.getInstance()
        .restoreTable(
            fixture.table.nameIdentifier(),
            firstDeletion,
            RESTORED_AT,
            RESTORE_ETAG,
            Long.MAX_VALUE);

    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));

    EntityDeletionPO secondDeletion = getDeletion(fixture);
    assertEquals(DELETED_AT + 1L, secondDeletion.getDeletedAt());
    assertEquals(DELETED_AT + 1L + RETENTION_MS, secondDeletion.getExpiresAt());
    assertAllAffectedRowsHaveDeletedAt(fixture, DELETED_AT + 1L);

    TableEntity restored =
        TableMetaService.getInstance()
            .restoreTable(
                fixture.table.nameIdentifier(),
                secondDeletion,
                RESTORED_AT + 1L,
                RESTORE_ETAG,
                Long.MAX_VALUE);
    TableEntity renamed =
        TableEntity.builder()
            .withId(restored.id())
            .withName(fixture.table.name() + "_renamed")
            .withNamespace(restored.namespace())
            .withColumns(restored.columns())
            .withAuditInfo(restored.auditInfo())
            .build();
    TableMetaService.getInstance().updateTable(restored.nameIdentifier(), ignored -> renamed);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(renamed.nameIdentifier(), DELETED_AT, RETENTION_MS));

    EntityDeletionPO renamedDeletion =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE,
                fixture.schema.id(),
                renamed.name(),
                renamed.id(),
                DeletionState.DELETED)
            .get(0);
    assertEquals(DELETED_AT + 2L, renamedDeletion.getDeletedAt());
  }

  @TestTemplate
  public void testMissingRequiredVersionRollsBackEveryRestoreMutation()
      throws IOException, SQLException {
    TableFixture fixture = createFixture("missing_required_version");
    insertCascadeParticipants(fixture);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO deletion = getDeletion(fixture);

    assertEquals(
        1,
        update(
            "DELETE FROM table_version_info WHERE table_id = ? AND deleted_at = ?",
            fixture.table.id(),
            DELETED_AT));

    assertThrows(
        TombstoneChangedException.class,
        () ->
            TableMetaService.getInstance()
                .restoreTable(
                    fixture.table.nameIdentifier(),
                    deletion,
                    RESTORED_AT,
                    RESTORE_ETAG,
                    Long.MAX_VALUE));

    EntityDeletionPO unchanged = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    assertNotNull(unchanged);
    assertEquals(DeletionState.DELETED, unchanged.getState());
    assertEquals(0L, unchanged.getRevision());
    assertNull(unchanged.getRestoredAt());
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM owner_meta WHERE metadata_object_id = ? AND deleted_at = ?",
            fixture.table.id(),
            DELETED_AT));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM table_meta WHERE table_id = ? AND deleted_at = ?",
            fixture.table.id(),
            DELETED_AT));
    assertThrows(
        NoSuchEntityException.class,
        () -> TableMetaService.getInstance().getTableByIdentifier(fixture.table.nameIdentifier()));
  }

  @TestTemplate
  public void testChangedDeletionSnapshotRejectsRestore() throws IOException, SQLException {
    TableFixture fixture = createFixture("changed_tombstone");
    insertCascadeParticipants(fixture);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);
    assertTrue(
        EntityDeletionService.getInstance()
            .compareAndSetState(
                observed.getDeletionId(),
                DeletionState.DELETED,
                observed.getRevision(),
                DeletionState.RESTORING,
                null,
                null));

    assertThrows(
        TombstoneChangedException.class,
        () ->
            TableMetaService.getInstance()
                .restoreTable(
                    fixture.table.nameIdentifier(),
                    observed,
                    RESTORED_AT,
                    RESTORE_ETAG,
                    Long.MAX_VALUE));
    assertEquals(
        0, TableMetaService.getInstance().purgeExpiredTableDeletions(DELETED_AT + 1L, 100));
    assertAllAffectedRowsHaveDeletedAt(fixture, DELETED_AT);
  }

  @TestTemplate
  public void testRestoreRevalidatesEveryDeletionRecordField() throws IOException, SQLException {
    TableFixture fixture = createFixture("changed_deletion_record_fields");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);

    assertSnapshotMutationRejected(
        fixture, observed, "metalake_id", observed.getMetalakeId() + 1L, observed.getMetalakeId());
    assertSnapshotMutationRejected(
        fixture, observed, "catalog_id", observed.getCatalogId() + 1L, observed.getCatalogId());
    assertSnapshotMutationRejected(
        fixture, observed, "expires_at", observed.getExpiresAt() + 1L, observed.getExpiresAt());
    assertSnapshotMutationRejected(
        fixture, observed, "deleted_by", "changed-user", observed.getDeletedBy());
    assertSnapshotMutationRejected(
        fixture, observed, "restored_at", RESTORED_AT, observed.getRestoredAt());
    assertSnapshotMutationRejected(
        fixture, observed, "restore_etag", "changed-etag", observed.getRestoreEtag());
    assertSnapshotMutationRejected(
        fixture, observed, "purged_at", RESTORED_AT, observed.getPurgedAt());
  }

  @TestTemplate
  public void testRestoreRechecksCurrentGlobalExpiryInsideTransaction() throws IOException {
    TableFixture fixture = createFixture("expired_inside_transaction");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);

    assertThrows(
        TombstoneExpiredException.class,
        () ->
            TableMetaService.getInstance()
                .restoreTable(
                    fixture.table.nameIdentifier(), observed, RESTORED_AT, RESTORE_ETAG, 1L));
    EntityDeletionPO unchanged = EntityDeletionService.getInstance().get(observed.getDeletionId());
    assertNotNull(unchanged);
    assertEquals(DeletionState.DELETED, unchanged.getState());
    assertEquals(0L, unchanged.getRevision());
  }

  @TestTemplate
  public void testCurrentGlobalCutoffAtomicallyPurgesExactGeneration()
      throws IOException, SQLException {
    TableFixture fixture = createFixture("exact_purge");
    insertCascadeParticipants(fixture);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO deletion = getDeletion(fixture);
    assertTrue(deletion.getExpiresAt() > DELETED_AT + 1L);

    // The current global cutoff wins over the creation-time expiresAt snapshot.
    assertEquals(1, backend.hardDeleteLegacyData(Entity.EntityType.TABLE, DELETED_AT + 1L));
    assertAllAffectedRowsArePurged(DELETED_AT);

    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    assertNotNull(receipt);
    assertEquals(DeletionState.PURGED, receipt.getState());
    assertEquals(2L, receipt.getRevision());
    assertNotNull(receipt.getPurgedAt());
  }

  @TestTemplate
  public void testLiveNameConflictRollsBackAndMapsToTypedConflict()
      throws IOException, SQLException {
    TableFixture fixture = createFixture("name_conflict");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);
    TableEntity replacement = createReplacement(fixture);
    backend.insert(replacement, false);

    RecoveryConflictException conflict =
        assertThrows(
            RecoveryConflictException.class,
            () ->
                TableMetaService.getInstance()
                    .restoreTable(
                        fixture.table.nameIdentifier(),
                        observed,
                        RESTORED_AT,
                        RESTORE_ETAG,
                        Long.MAX_VALUE));
    assertEquals(RecoveryConflictReason.NAME_OCCUPIED, conflict.getReason());
    EntityDeletionPO unchanged = EntityDeletionService.getInstance().get(observed.getDeletionId());
    assertEquals(DeletionState.DELETED, unchanged.getState());
    assertEquals(0L, unchanged.getRevision());
    assertEquals(
        replacement.id(),
        TableMetaService.getInstance().getTableByIdentifier(fixture.table.nameIdentifier()).id());
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM table_meta WHERE table_id = ? AND deleted_at = ?",
            fixture.table.id(),
            DELETED_AT));
  }

  @TestTemplate
  public void testNewerSameNameDeletionInsertedAfterObservationRejectsRestore() throws IOException {
    TableFixture fixture = createFixture("newer_generation");
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(fixture.table.nameIdentifier(), DELETED_AT, RETENTION_MS));
    EntityDeletionPO observed = getDeletion(fixture);

    TableEntity replacement = createReplacement(fixture);
    backend.insert(replacement, false);
    assertTrue(
        TableMetaService.getInstance()
            .deleteTable(replacement.nameIdentifier(), DELETED_AT + 1, RETENTION_MS));

    RecoveryConflictException conflict =
        assertThrows(
            RecoveryConflictException.class,
            () ->
                TableMetaService.getInstance()
                    .restoreTable(
                        fixture.table.nameIdentifier(),
                        observed,
                        RESTORED_AT,
                        RESTORE_ETAG,
                        Long.MAX_VALUE));
    assertEquals(RecoveryConflictReason.NOT_LATEST_TOMBSTONE, conflict.getReason());
    EntityDeletionPO unchanged = EntityDeletionService.getInstance().get(observed.getDeletionId());
    assertEquals(DeletionState.DELETED, unchanged.getState());
    assertEquals(0L, unchanged.getRevision());
  }

  private TableFixture createFixture(String tableName) throws IOException {
    BaseMetalake metalake = createAndInsertMakeLake("metalake_" + tableName);
    CatalogEntity catalog = createAndInsertCatalog(metalake.name(), "catalog_" + tableName);
    SchemaEntity schema =
        createAndInsertSchema(metalake.name(), catalog.name(), "schema_" + tableName);
    ColumnEntity column =
        ColumnEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("id")
            .withPosition(0)
            .withDataType(Types.LongType.get())
            .withNullable(false)
            .withAutoIncrement(false)
            .withAuditInfo(AUDIT_INFO)
            .build();
    TableEntity table =
        TableEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(tableName)
            .withNamespace(NamespaceUtil.ofTable(metalake.name(), catalog.name(), schema.name()))
            .withColumns(List.of(column))
            .withAuditInfo(AUDIT_INFO)
            .build();
    backend.insert(table, false);
    return new TableFixture(metalake, schema, table, column);
  }

  private void insertCascadeParticipants(TableFixture fixture) throws SQLException {
    long ownerId = RandomIdGenerator.INSTANCE.nextId();
    long roleId = RandomIdGenerator.INSTANCE.nextId();
    long tableTagId = RandomIdGenerator.INSTANCE.nextId();
    long columnTagId = RandomIdGenerator.INSTANCE.nextId();
    long statisticId = RandomIdGenerator.INSTANCE.nextId();
    long policyId = RandomIdGenerator.INSTANCE.nextId();

    update(
        "INSERT INTO owner_meta (metalake_id, owner_id, owner_type, metadata_object_id,"
            + " metadata_object_type, audit_info, deleted_at, updated_at)"
            + " VALUES (?, ?, 'USER', ?, 'TABLE', '{}', 0, 0)",
        fixture.metalake.id(),
        ownerId,
        fixture.table.id());
    update(
        "INSERT INTO role_meta_securable_object (role_id, metadata_object_id, type,"
            + " privilege_names, privilege_conditions, deleted_at)"
            + " VALUES (?, ?, 'TABLE', '[]', '[]', 0)",
        roleId,
        fixture.table.id());
    update(
        "INSERT INTO tag_relation_meta (tag_id, metadata_object_id, metadata_object_type,"
            + " audit_info, deleted_at) VALUES (?, ?, 'TABLE', '{}', 0)",
        tableTagId,
        fixture.table.id());
    update(
        "INSERT INTO tag_relation_meta (tag_id, metadata_object_id, metadata_object_type,"
            + " audit_info, deleted_at) VALUES (?, ?, 'COLUMN', '{}', 0)",
        columnTagId,
        fixture.column.id());
    update(
        "INSERT INTO statistic_meta (statistic_id, statistic_name, metalake_id,"
            + " statistic_value, metadata_object_id, metadata_object_type, audit_info, deleted_at)"
            + " VALUES (?, 'row_count', ?, '{}', ?, 'TABLE', '{}', 0)",
        statisticId,
        fixture.metalake.id(),
        fixture.table.id());
    update(
        "INSERT INTO policy_relation_meta (policy_id, metadata_object_id,"
            + " metadata_object_type, audit_info, deleted_at)"
            + " VALUES (?, ?, 'TABLE', '{}', 0)",
        policyId,
        fixture.table.id());
  }

  private TableEntity createReplacement(TableFixture fixture) {
    ColumnEntity column =
        ColumnEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("id")
            .withPosition(0)
            .withDataType(Types.LongType.get())
            .withNullable(false)
            .withAutoIncrement(false)
            .withAuditInfo(AUDIT_INFO)
            .build();
    return TableEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(fixture.table.name())
        .withNamespace(fixture.table.namespace())
        .withColumns(List.of(column))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private EntityDeletionPO getDeletion(TableFixture fixture) {
    List<EntityDeletionPO> deletions =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE,
                fixture.schema.id(),
                fixture.table.name(),
                fixture.table.id(),
                DeletionState.DELETED);
    assertEquals(1, deletions.size());
    return deletions.get(0);
  }

  private void assertSnapshotMutationRejected(
      TableFixture fixture,
      EntityDeletionPO observed,
      String column,
      Object changedValue,
      @Nullable Object originalValue)
      throws SQLException {
    String sql = "UPDATE entity_deletion SET " + column + " = ? WHERE deletion_id = ?";
    assertEquals(1, update(sql, changedValue, observed.getDeletionId()));
    try {
      assertThrows(
          TombstoneChangedException.class,
          () ->
              TableMetaService.getInstance()
                  .restoreTable(
                      fixture.table.nameIdentifier(),
                      observed,
                      RESTORED_AT,
                      RESTORE_ETAG,
                      Long.MAX_VALUE));
    } finally {
      assertEquals(1, update(sql, originalValue, observed.getDeletionId()));
    }
  }

  private void assertAllAffectedRowsHaveDeletedAt(TableFixture fixture, long deletedAt)
      throws SQLException {
    assertEquals(1, countByDeletedAt("table_meta", deletedAt));
    assertEquals(1, countByDeletedAt("owner_meta", deletedAt));
    assertEquals(1, countByDeletedAt("table_column_version_info", deletedAt));
    assertEquals(1, countByDeletedAt("role_meta_securable_object", deletedAt));
    assertEquals(2, countByDeletedAt("tag_relation_meta", deletedAt));
    assertEquals(1, countByDeletedAt("statistic_meta", deletedAt));
    assertEquals(1, countByDeletedAt("policy_relation_meta", deletedAt));
    assertEquals(1, countByDeletedAt("table_version_info", deletedAt));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM table_meta WHERE table_id = ? AND deleted_at = ?",
            fixture.table.id(),
            deletedAt));
  }

  private void assertAllAffectedRowsHaveDeletionId(String deletionId) throws SQLException {
    assertEquals(1, countByDeletionId("table_meta", deletionId));
    assertEquals(1, countByDeletionId("owner_meta", deletionId));
    assertEquals(1, countByDeletionId("table_column_version_info", deletionId));
    assertEquals(1, countByDeletionId("role_meta_securable_object", deletionId));
    assertEquals(2, countByDeletionId("tag_relation_meta", deletionId));
    assertEquals(1, countByDeletionId("statistic_meta", deletionId));
    assertEquals(1, countByDeletionId("policy_relation_meta", deletionId));
    assertEquals(1, countByDeletionId("table_version_info", deletionId));
  }

  private void assertNoRowsHaveDeletionId(String deletionId) throws SQLException {
    assertEquals(0, countByDeletionId("table_meta", deletionId));
    assertEquals(0, countByDeletionId("owner_meta", deletionId));
    assertEquals(0, countByDeletionId("table_column_version_info", deletionId));
    assertEquals(0, countByDeletionId("role_meta_securable_object", deletionId));
    assertEquals(0, countByDeletionId("tag_relation_meta", deletionId));
    assertEquals(0, countByDeletionId("statistic_meta", deletionId));
    assertEquals(0, countByDeletionId("policy_relation_meta", deletionId));
    assertEquals(0, countByDeletionId("table_version_info", deletionId));
  }

  private void assertAllAffectedRowsAreLive(TableFixture fixture) throws SQLException {
    assertEquals(1, countByDeletedAt("table_meta", 0));
    assertEquals(1, countByDeletedAt("owner_meta", 0));
    assertEquals(1, countByDeletedAt("table_column_version_info", 0));
    assertEquals(1, countByDeletedAt("role_meta_securable_object", 0));
    assertEquals(2, countByDeletedAt("tag_relation_meta", 0));
    assertEquals(1, countByDeletedAt("statistic_meta", 0));
    assertEquals(1, countByDeletedAt("policy_relation_meta", 0));
    assertEquals(1, countByDeletedAt("table_version_info", 0));
    assertEquals(0, countByDeletedAt("table_meta", DELETED_AT));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM table_meta WHERE table_id = ? AND deleted_at = 0",
            fixture.table.id()));
  }

  private void assertAllAffectedRowsArePurged(long deletedAt) throws SQLException {
    assertEquals(0, countByDeletedAt("table_meta", deletedAt));
    assertEquals(0, countByDeletedAt("owner_meta", deletedAt));
    assertEquals(0, countByDeletedAt("table_column_version_info", deletedAt));
    assertEquals(0, countByDeletedAt("role_meta_securable_object", deletedAt));
    assertEquals(0, countByDeletedAt("tag_relation_meta", deletedAt));
    assertEquals(0, countByDeletedAt("statistic_meta", deletedAt));
    assertEquals(0, countByDeletedAt("policy_relation_meta", deletedAt));
    assertEquals(0, countByDeletedAt("table_version_info", deletedAt));
  }

  private int countByDeletedAt(String table, long deletedAt) throws SQLException {
    return count("SELECT COUNT(*) FROM " + table + " WHERE deleted_at = ?", deletedAt);
  }

  private int countByDeletionId(String table, String deletionId) throws SQLException {
    return count("SELECT COUNT(*) FROM " + table + " WHERE deletion_id = ?", deletionId);
  }

  private int count(String sql, Object... parameters) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      setParameters(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private int update(String sql, Object... parameters) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      setParameters(statement, parameters);
      return statement.executeUpdate();
    }
  }

  private static void setParameters(PreparedStatement statement, Object[] parameters)
      throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }

  private static class TableFixture {
    private final BaseMetalake metalake;
    private final SchemaEntity schema;
    private final TableEntity table;
    private final ColumnEntity column;

    private TableFixture(
        BaseMetalake metalake, SchemaEntity schema, TableEntity table, ColumnEntity column) {
      this.metalake = metalake;
      this.schema = schema;
      this.table = table;
      this.column = column;
    }
  }
}
