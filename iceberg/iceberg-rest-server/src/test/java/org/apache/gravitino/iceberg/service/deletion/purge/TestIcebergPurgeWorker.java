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

package org.apache.gravitino.iceberg.service.deletion.purge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionContextStore;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

/** Cross-backend tests for purge execution, partial failure, replay, and generation safety. */
public class TestIcebergPurgeWorker extends TestJDBCBackend {

  private static final long INITIAL_TIME = 20_000L;
  private static final long LEASE_MS = 1_000L;

  private Object originalConfig;
  private Object originalIdGenerator;
  private AtomicLong clock;
  private IcebergDeletionContextStore contextStore;
  private IcebergPurgeJobStore purgeStore;

  @BeforeAll
  public void snapshotGravitinoEnv() throws IllegalAccessException {
    originalConfig = FieldUtils.readField(GravitinoEnv.getInstance(), "config", true);
    originalIdGenerator = FieldUtils.readField(GravitinoEnv.getInstance(), "idGenerator", true);
  }

  @AfterAll
  public void restoreGravitinoEnv() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", originalConfig, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "idGenerator", originalIdGenerator, true);
  }

  @BeforeEach
  public void prepareStores() {
    clock = new AtomicLong(INITIAL_TIME);
    contextStore = new IcebergDeletionContextStore();
    purgeStore = new IcebergPurgeJobStore(new SequenceIdGenerator(10_000));
  }

  @TestTemplate
  void testSuccessfulPurgeDeletesChildrenBeforeRootAndFinalizesExactGeneration()
      throws SQLException {
    insertDeletion("success", 101L);
    Assertions.assertEquals(2, relationCount("tag_relation_meta", "success"));
    Assertions.assertEquals(2, relationCount("policy_relation_meta", "success"));
    String jobId = claimBatch(1).getPurgeJobId();
    List<String> deletedTypes = new ArrayList<>();
    List<String> operations = new ArrayList<>();

    IcebergPurgeWorker worker =
        worker(
            planner(),
            (context, target) -> {
              deletedTypes.add(target.getTargetType());
              operations.add("delete-" + target.getTargetType());
            },
            context -> operations.add("unregister"));
    Assertions.assertTrue(worker.runOnce("worker-success"));

    EntityDeletionPO action = deletion("success");
    Assertions.assertEquals("PURGED", action.getState());
    Assertions.assertEquals("SUCCEEDED", action.getCleanupStatus());
    Assertions.assertEquals(2, action.getRevision());
    Assertions.assertEquals(List.of("DATA", "ROOT_METADATA"), deletedTypes);
    Assertions.assertEquals("unregister", operations.get(0));
    Assertions.assertEquals(2, purgeStore.targetCounts("success").getSucceededCount());
    Assertions.assertEquals("SUCCEEDED", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals(1, purgeStore.countAudits(jobId, "PURGE_STARTED"));
    Assertions.assertEquals(1, purgeStore.countAudits(jobId, "PURGE_COMPLETED"));
    Assertions.assertEquals(0, rowCount("table_meta", 101L));
    Assertions.assertEquals(0, rowCount("table_version_info", 101L));
    Assertions.assertEquals(0, rowCount("table_column_version_info", 101L));
    Assertions.assertEquals(0, relationCount("tag_relation_meta", "success"));
    Assertions.assertEquals(0, relationCount("policy_relation_meta", "success"));
    Assertions.assertEquals(1, relationCount("tag_relation_meta", "other-success"));
    Assertions.assertEquals(1, relationCount("policy_relation_meta", "other-success"));

    Assertions.assertEquals(0, purgeStore.expireTerminalLedgers(INITIAL_TIME - 1, 10));
    Assertions.assertNotNull(purgeStore.getPlan("success"));
    Assertions.assertEquals(1, purgeStore.expireTerminalLedgers(INITIAL_TIME, 10));
    Assertions.assertNull(purgeStore.getPlan("success"));
    Assertions.assertNotNull(deletion("success"));
    Assertions.assertNotNull(purgeStore.getJob(jobId));
  }

  @TestTemplate
  void testPermanentItemFailureDoesNotRollBackUnrelatedBatchItem() throws SQLException {
    insertDeletion("failure", 201L);
    insertDeletion("healthy", 202L);
    String jobId = claimBatch(2).getPurgeJobId();

    IcebergPurgeTargetDeleter deleter =
        (context, target) -> {
          if (context.getDeletionId().equals("failure") && target.getTargetType().equals("DATA")) {
            throw new IcebergPurgeException(
                false,
                "ACCESS_DENIED",
                "permission denied at s3://private/path password=do-not-persist");
          }
        };
    Assertions.assertTrue(worker(planner(), deleter).runOnce("worker-partial"));

    EntityDeletionPO failed = deletion("failure");
    Assertions.assertEquals("PURGING", failed.getState());
    Assertions.assertEquals("FAILED", failed.getCleanupStatus());
    Assertions.assertEquals(
        "Iceberg purge operation failed (ACCESS_DENIED)", failed.getCleanupLastError());
    Assertions.assertFalse(failed.getCleanupLastError().contains("s3://"));
    Assertions.assertFalse(failed.getCleanupLastError().contains("do-not-persist"));
    Assertions.assertEquals("PURGED", deletion("healthy").getState());
    Assertions.assertEquals("PARTIAL_FAILED", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals(1, rowCount("table_meta", 201L));
    Assertions.assertEquals(0, rowCount("table_meta", 202L));
  }

  @TestTemplate
  void testProviderFailureDetailsAreNeverPersisted() throws SQLException {
    insertDeletion("secret-failure", 250L);
    claimBatch(1);
    IcebergPurgeRegistrationRemover remover =
        context -> {
          throw new IcebergPurgeException(
              false,
              "token=reason-secret",
              "failed at s3://private/path?credential=message-secret");
        };

    Assertions.assertTrue(worker(planner(), (context, target) -> {}, remover).runOnce("worker"));

    EntityDeletionPO failed = deletion("secret-failure");
    Assertions.assertEquals("FAILED", failed.getCleanupStatus());
    Assertions.assertEquals(
        "Iceberg purge operation failed (PURGE_FAILURE)", failed.getCleanupLastError());
    Assertions.assertFalse(failed.getCleanupLastError().contains("reason-secret"));
    Assertions.assertFalse(failed.getCleanupLastError().contains("message-secret"));
  }

  @TestTemplate
  void testCrashAfterExternalSuccessReclaimsWithoutRepeatingDeletes() throws SQLException {
    insertDeletion("crash-replay", 301L);
    String jobId = claimBatch(1).getPurgeJobId();
    AtomicInteger deleteCalls = new AtomicInteger();
    IcebergPurgeTargetDeleter deleter = (context, target) -> deleteCalls.incrementAndGet();
    IcebergPurgeWorker crashing =
        new IcebergPurgeWorker(
            purgeStore,
            contextStore,
            context -> {},
            planner(),
            deleter,
            clock::get,
            options(),
            () -> {
              throw new SimulatedCrashException();
            });

    Assertions.assertThrows(
        SimulatedCrashException.class, () -> crashing.runOnce("worker-before-crash"));
    Assertions.assertEquals(2, deleteCalls.get());
    Assertions.assertEquals("RUNNING", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals("PURGING", deletion("crash-replay").getState());
    Assertions.assertEquals(1, rowCount("table_meta", 301L));

    clock.addAndGet(LEASE_MS + 1);
    Assertions.assertTrue(worker(planner(), deleter).runOnce("worker-after-crash"));
    Assertions.assertEquals(2, deleteCalls.get());
    Assertions.assertEquals("PURGED", deletion("crash-replay").getState());
    Assertions.assertEquals("SUCCEEDED", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals(0, rowCount("table_meta", 301L));
  }

  @TestTemplate
  void testExactDeletionPointerPreventsDeletingRestoredOrReplacementRow() throws SQLException {
    insertDeletion("old-generation", 401L);
    String jobId = claimBatch(1).getPurgeJobId();
    updateDeletionPointer(401L, null);

    Assertions.assertTrue(worker(planner(), (context, target) -> {}).runOnce("worker-mismatch"));

    EntityDeletionPO action = deletion("old-generation");
    Assertions.assertEquals("PURGING", action.getState());
    Assertions.assertEquals("FAILED", action.getCleanupStatus());
    Assertions.assertEquals("FAILED", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals(1, rowCount("table_meta", 401L));
  }

  @TestTemplate
  void testInterruptedPlanningIsIdempotentAndDeletesNothingUntilReady() throws SQLException {
    insertDeletion("planning-replay", 501L);
    String jobId = claimBatch(1).getPurgeJobId();
    AtomicInteger planningAttempts = new AtomicInteger();
    AtomicInteger deleteCalls = new AtomicInteger();
    AtomicInteger registrationRemovals = new AtomicInteger();
    IcebergPurgePlanner flakyPlanner =
        (context, sink) -> {
          sink.add(dataTarget(context));
          if (planningAttempts.getAndIncrement() == 0) {
            throw new IcebergPurgeException(true, "THROTTLED", "provider returned HTTP 429");
          }
          return sink.add(rootTarget(context));
        };
    IcebergPurgeTargetDeleter deleter = (context, target) -> deleteCalls.incrementAndGet();

    IcebergPurgeWorker worker =
        worker(flakyPlanner, deleter, context -> registrationRemovals.incrementAndGet());
    Assertions.assertTrue(worker.runOnce("worker-plan-1"));
    Assertions.assertEquals(0, deleteCalls.get());
    Assertions.assertEquals(0, registrationRemovals.get());
    Assertions.assertEquals("PLANNING", purgeStore.getPlan("planning-replay").getState());
    Assertions.assertEquals("PENDING", deletion("planning-replay").getCleanupStatus());
    Assertions.assertEquals("PENDING", purgeStore.getJob(jobId).getState());

    Assertions.assertFalse(worker.runOnce("worker-before-retry-deadline"));
    clock.addAndGet(options().getRetryDelayMs());
    Assertions.assertTrue(worker.runOnce("worker-plan-2"));
    Assertions.assertEquals(2, deleteCalls.get());
    Assertions.assertEquals(1, registrationRemovals.get());
    Assertions.assertEquals("PURGED", deletion("planning-replay").getState());
    Assertions.assertEquals("SUCCEEDED", purgeStore.getJob(jobId).getState());
    Assertions.assertEquals(0, rowCount("table_meta", 501L));
  }

  @TestTemplate
  void testTargetRetriesHaveDurableDelayAndIndependentAttemptBudget() throws SQLException {
    insertDeletion("target-retry", 601L);
    String jobId = claimBatch(1).getPurgeJobId();
    AtomicInteger dataAttempts = new AtomicInteger();
    IcebergPurgeTargetDeleter deleter =
        (context, target) -> {
          if ("DATA".equals(target.getTargetType()) && dataAttempts.getAndIncrement() == 0) {
            throw new IcebergPurgeException(true, "THROTTLED", "provider secret response");
          }
        };
    IcebergPurgeWorker worker = worker(planner(), deleter);

    Assertions.assertTrue(worker.runOnce("worker-target-1"));
    Assertions.assertEquals("PENDING", deletion("target-retry").getCleanupStatus());
    Assertions.assertEquals(0, deletion("target-retry").getCleanupAttemptCount());
    Assertions.assertEquals("PENDING", purgeStore.getJob(jobId).getState());
    Assertions.assertFalse(worker.runOnce("worker-too-early"));

    clock.addAndGet(options().getRetryDelayMs());
    Assertions.assertTrue(worker.runOnce("worker-target-2"));
    Assertions.assertEquals("PURGED", deletion("target-retry").getState());
    Assertions.assertEquals(0, deletion("target-retry").getCleanupAttemptCount());
    Assertions.assertEquals(2, dataAttempts.get());
  }

  @TestTemplate
  void testRegistrationMismatchAfterPlanningDeletesNoTargets() throws SQLException {
    insertDeletion("registration-mismatch", 701L);
    claimBatch(1);
    AtomicInteger deleteCalls = new AtomicInteger();
    IcebergPurgeRegistrationRemover remover =
        context -> {
          throw new IcebergPurgeException(
              false, "REGISTRATION_GENERATION_MISMATCH", "unsafe provider detail");
        };

    Assertions.assertTrue(
        worker(planner(), (context, target) -> deleteCalls.incrementAndGet(), remover)
            .runOnce("worker-mismatch"));

    Assertions.assertEquals("READY", purgeStore.getPlan("registration-mismatch").getState());
    Assertions.assertEquals(0, deleteCalls.get());
    Assertions.assertEquals("FAILED", deletion("registration-mismatch").getCleanupStatus());
  }

  @TestTemplate
  void testSlowTargetBatchRenewsLeaseThroughoutWork() throws SQLException {
    insertDeletion("slow-batch", 801L);
    claimBatch(1);
    IcebergPurgePlanner threeTargets =
        (context, sink) -> {
          sink.add(new IcebergPurgeTarget(IcebergPurgeTarget.Type.DATA, "s3://bucket/a", null, 10));
          sink.add(new IcebergPurgeTarget(IcebergPurgeTarget.Type.DATA, "s3://bucket/b", null, 10));
          return sink.add(rootTarget(context));
        };
    IcebergPurgeTargetDeleter slowDeleter = (context, target) -> clock.addAndGet(LEASE_MS * 2 / 5);

    Assertions.assertTrue(worker(threeTargets, slowDeleter).runOnce("worker-slow"));
    Assertions.assertTrue(clock.get() - INITIAL_TIME > LEASE_MS);
    Assertions.assertEquals("PURGED", deletion("slow-batch").getState());
  }

  private IcebergPurgeWorker worker(
      IcebergPurgePlanner planner, IcebergPurgeTargetDeleter deleter) {
    return worker(planner, deleter, context -> {});
  }

  private IcebergPurgeWorker worker(
      IcebergPurgePlanner planner,
      IcebergPurgeTargetDeleter deleter,
      IcebergPurgeRegistrationRemover registrationRemover) {
    return new IcebergPurgeWorker(
        purgeStore, contextStore, registrationRemover, planner, deleter, clock::get, options());
  }

  private static IcebergPurgeWorkerOptions options() {
    return IcebergPurgeWorkerOptions.builder()
        .withLeaseDurationMs(LEASE_MS)
        .withJobCandidateWindow(10)
        .withTargetBatchSize(10)
        .withPlanningWriteBatchSize(1)
        .withMaxTargetBatchesPerRun(20)
        .withMaxActionAttempts(3)
        .withMaxTargetAttempts(3)
        .withRetryDelayMs(10L)
        .build();
  }

  private static IcebergPurgePlanner planner() {
    return (context, sink) -> {
      sink.add(dataTarget(context));
      return sink.add(rootTarget(context));
    };
  }

  private static IcebergPurgeTarget dataTarget(IcebergDeletionContextPO context) {
    return new IcebergPurgeTarget(
        IcebergPurgeTarget.Type.DATA,
        "s3://bucket/" + context.getDeletionId() + "/data.parquet",
        "data-version",
        10);
  }

  private static IcebergPurgeTarget rootTarget(IcebergDeletionContextPO context) {
    return new IcebergPurgeTarget(
        IcebergPurgeTarget.Type.ROOT_METADATA,
        context.getMetadataLocation(),
        "metadata-version",
        100);
  }

  private IcebergPurgeJobPO claimBatch(int limit) {
    return purgeStore
        .claimEligibleBatch(clock.get(), limit, "collector", "collect-request", "correlation")
        .orElseThrow();
  }

  private void insertDeletion(String deletionId, long tableId) throws SQLException {
    EntityDeletionService.getInstance()
        .insert(
            EntityDeletionPO.builder()
                .deletionId(deletionId)
                .entityType("TABLE")
                .entityId(tableId)
                .entityVersion(1L)
                .metalakeId(10L)
                .catalogId(20L)
                .parentId(30L)
                .namespaceSnapshot("db")
                .entityNameSnapshot(deletionId)
                .nameLookupKey("lookup-" + deletionId)
                .activeNameKey("active-" + deletionId)
                .state("DELETED")
                .revision(0L)
                .deletedAt(100L)
                .retentionExpiresAt(null)
                .deletedBy("alice")
                .purgeRequested(true)
                .purgeJobType(IcebergPurgeJobStore.PURGE_JOB_TYPE)
                .cleanupStatus("PENDING")
                .cleanupAttemptCount(0)
                .requestId("delete-" + deletionId)
                .correlationId("correlation-" + deletionId)
                .updatedAt(100L)
                .build());
    contextStore.insert(
        IcebergDeletionContextPO.builder()
            .withDeletionId(deletionId)
            .withIcebergNamespace("db")
            .withIcebergTableName(deletionId)
            .withIcebergTableUuid("uuid-" + deletionId)
            .withMetadataLocation("s3://bucket/db/" + deletionId + "/metadata/root.json")
            .withFileIoImpl("org.apache.iceberg.aws.s3.S3FileIO")
            .withProtectedFileIoRef("protected://catalog/20")
            .withContextDigest(digest(deletionId))
            .withCreatedAt(100L)
            .withUpdatedAt(100L)
            .build());
    insertTableRows(deletionId, tableId);
  }

  private static void insertTableRows(String deletionId, long tableId) throws SQLException {
    try (SqlSession session =
        SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true)) {
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO table_meta (table_id, table_name, metalake_id, catalog_id,"
                      + " schema_id, audit_info, current_version, last_version, deleted_at,"
                      + " deletion_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, tableId);
        statement.setString(2, deletionId);
        statement.setLong(3, 10L);
        statement.setLong(4, 20L);
        statement.setLong(5, 30L);
        statement.setString(6, "{}");
        statement.setInt(7, 1);
        statement.setInt(8, 1);
        statement.setLong(9, 100L);
        statement.setString(10, deletionId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO table_version_info"
                      + " (table_id, format, version, deleted_at, deletion_id)"
                      + " VALUES (?, ?, ?, ?, ?)")) {
        statement.setLong(1, tableId);
        statement.setString(2, "iceberg");
        statement.setLong(3, 1L);
        statement.setLong(4, 100L);
        statement.setString(5, deletionId);
        statement.executeUpdate();
      }
      long columnId = tableId * 100 + 1;
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO table_column_version_info"
                      + " (metalake_id, catalog_id, schema_id, table_id, table_version, column_id,"
                      + " column_name, column_position, column_type, column_comment,"
                      + " column_nullable, column_auto_increment, column_op_type, deleted_at,"
                      + " audit_info, deletion_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                      + " ?, ?, ?)")) {
        statement.setLong(1, 10L);
        statement.setLong(2, 20L);
        statement.setLong(3, 30L);
        statement.setLong(4, tableId);
        statement.setLong(5, 1L);
        statement.setLong(6, columnId);
        statement.setString(7, "c");
        statement.setInt(8, 0);
        statement.setString(9, "integer");
        statement.setString(10, "");
        statement.setBoolean(11, true);
        statement.setBoolean(12, false);
        statement.setInt(13, 1);
        statement.setLong(14, 100L);
        statement.setString(15, "{}");
        statement.setString(16, deletionId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO policy_relation_meta"
                      + " (policy_id, metadata_object_id, metadata_object_type, audit_info,"
                      + " current_version, last_version, deleted_at, deletion_id)"
                      + " VALUES (?, ?, 'COLUMN', '{}', 1, 1, 100, ?)")) {
        statement.setLong(1, tableId * 100 + 2);
        statement.setLong(2, columnId);
        statement.setString(3, deletionId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO tag_relation_meta"
                      + " (tag_id, metadata_object_id, metadata_object_type, audit_info,"
                      + " current_version, last_version, deleted_at, deletion_id)"
                      + " VALUES (?, ?, ?, '{}', 1, 1, 100, ?)")) {
        statement.setLong(1, tableId * 100 + 3);
        statement.setLong(2, tableId);
        statement.setString(3, "TABLE");
        statement.setString(4, deletionId);
        statement.addBatch();
        statement.setLong(1, tableId * 100 + 4);
        statement.setLong(2, columnId);
        statement.setString(3, "COLUMN");
        statement.setString(4, deletionId);
        statement.addBatch();
        statement.setLong(1, tableId * 100 + 5);
        statement.setLong(2, columnId);
        statement.setString(3, "COLUMN");
        statement.setString(4, "other-" + deletionId);
        statement.addBatch();
        statement.executeBatch();
      }
      try (PreparedStatement statement =
          session
              .getConnection()
              .prepareStatement(
                  "INSERT INTO policy_relation_meta"
                      + " (policy_id, metadata_object_id, metadata_object_type, audit_info,"
                      + " current_version, last_version, deleted_at, deletion_id)"
                      + " VALUES (?, ?, ?, '{}', 1, 1, 100, ?)")) {
        statement.setLong(1, tableId * 100 + 6);
        statement.setLong(2, tableId);
        statement.setString(3, "TABLE");
        statement.setString(4, deletionId);
        statement.addBatch();
        statement.setLong(1, tableId * 100 + 7);
        statement.setLong(2, columnId);
        statement.setString(3, "COLUMN");
        statement.setString(4, "other-" + deletionId);
        statement.addBatch();
        statement.executeBatch();
      }
    }
  }

  private static void updateDeletionPointer(long tableId, String deletionId) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        PreparedStatement statement =
            session
                .getConnection()
                .prepareStatement("UPDATE table_meta SET deletion_id = ? WHERE table_id = ?")) {
      statement.setString(1, deletionId);
      statement.setLong(2, tableId);
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private static long rowCount(String table, long tableId) throws SQLException {
    String sql;
    if ("table_meta".equals(table)) {
      sql = "SELECT COUNT(*) FROM table_meta WHERE table_id = ?";
    } else if ("table_version_info".equals(table)) {
      sql = "SELECT COUNT(*) FROM table_version_info WHERE table_id = ?";
    } else if ("table_column_version_info".equals(table)) {
      sql = "SELECT COUNT(*) FROM table_column_version_info WHERE table_id = ?";
    } else {
      throw new IllegalArgumentException("Unsupported test table " + table);
    }
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
      statement.setLong(1, tableId);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static long relationCount(String table, String deletionId) throws SQLException {
    String sql;
    if ("tag_relation_meta".equals(table)) {
      sql = "SELECT COUNT(*) FROM tag_relation_meta WHERE deletion_id = ?";
    } else if ("policy_relation_meta".equals(table)) {
      sql = "SELECT COUNT(*) FROM policy_relation_meta WHERE deletion_id = ?";
    } else {
      throw new IllegalArgumentException("Unsupported relation table " + table);
    }
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
      statement.setString(1, deletionId);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static EntityDeletionPO deletion(String deletionId) {
    return EntityDeletionService.getInstance().get(deletionId);
  }

  private static String digest(String deletionId) {
    return String.format("%064d", Math.abs(deletionId.hashCode()));
  }

  private static final class SequenceIdGenerator implements IdGenerator {
    private final AtomicLong value;

    private SequenceIdGenerator(long first) {
      value = new AtomicLong(first);
    }

    @Override
    public long nextId() {
      return value.getAndIncrement();
    }
  }

  private static final class SimulatedCrashException extends RuntimeException {}
}
