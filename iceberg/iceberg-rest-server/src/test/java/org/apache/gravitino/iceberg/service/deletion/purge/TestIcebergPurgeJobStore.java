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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionContextStore;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgePlanPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

/** Cross-backend tests for durable purge batching, lease fencing, and target-plan publication. */
public class TestIcebergPurgeJobStore extends TestJDBCBackend {

  private static final long NOW = 10_000L;

  private Object originalConfig;
  private Object originalIdGenerator;
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
    contextStore = new IcebergDeletionContextStore();
    purgeStore = new IcebergPurgeJobStore(new SequenceIdGenerator(1));
  }

  @TestTemplate
  void testBoundedClaimUsesDerivedInclusiveEligibility() {
    insertDeletion("immediate", null, "DELETED", true);
    insertDeletion("expired", NOW - 1, "DELETED", true);
    insertDeletion("boundary", NOW, "DELETED", true);
    insertDeletion("future", NOW + 1, "DELETED", true);
    insertDeletion("restored", NOW - 1, "RESTORED", true);
    insertDeletion("missing-context", NOW - 1, "DELETED", false);

    IcebergPurgeJobPO first =
        purgeStore
            .claimEligibleBatch(NOW, 2, "collector-a", "request-a", "correlation-a")
            .orElseThrow();
    Assertions.assertEquals("PENDING", first.getState());
    Assertions.assertEquals(2, first.getItemCount());
    Assertions.assertEquals(2, first.getPendingCount());
    Assertions.assertEquals(2, purgeStore.listActions(first.getPurgeJobId()).size());
    Assertions.assertEquals(2, purgeStore.countAudits(first.getPurgeJobId()));

    IcebergPurgeJobPO second =
        purgeStore.claimEligibleBatch(NOW, 10, "collector-b", null, "correlation-b").orElseThrow();
    Assertions.assertEquals(1, second.getItemCount());
    Assertions.assertEquals(2, purgeStore.countJobs());
    Assertions.assertTrue(
        purgeStore.claimEligibleBatch(NOW, 10, "collector-c", null, "correlation-c").isEmpty());

    assertPurging("immediate");
    assertPurging("expired");
    assertPurging("boundary");
    Assertions.assertEquals("DELETED", deletion("future").getState());
    Assertions.assertEquals("RESTORED", deletion("restored").getState());
    Assertions.assertEquals("DELETED", deletion("missing-context").getState());
  }

  @TestTemplate
  void testCompetingCollectorsCreateExactlyOneNonemptyBatch() throws Exception {
    insertDeletion("one-winner", NOW, "DELETED", true);
    CyclicBarrier scanBarrier = new CyclicBarrier(2);
    Runnable afterScan = () -> await(scanBarrier);
    IcebergPurgeJobStore first = new IcebergPurgeJobStore(new SequenceIdGenerator(100), afterScan);
    IcebergPurgeJobStore second = new IcebergPurgeJobStore(new SequenceIdGenerator(200), afterScan);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Optional<IcebergPurgeJobPO>> left =
          executor.submit(() -> first.claimEligibleBatch(NOW, 1, "collector-1", null, "race"));
      Future<Optional<IcebergPurgeJobPO>> right =
          executor.submit(() -> second.claimEligibleBatch(NOW, 1, "collector-2", null, "race"));

      List<IcebergPurgeJobPO> winners = new ArrayList<>();
      collectSuccessfulClaim(left, winners);
      collectSuccessfulClaim(right, winners);
      Assertions.assertEquals(1, winners.size());
      Assertions.assertEquals(1, purgeStore.countJobs());
      Assertions.assertEquals(1, winners.get(0).getItemCount());
      Assertions.assertEquals(
          winners.get(0).getPurgeJobId(), deletion("one-winner").getPurgeJobId());
    } finally {
      executor.shutdownNow();
      Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @TestTemplate
  void testLeaseReclaimIncrementsEpochAndFencesStaleOwner() {
    insertDeletion("lease", NOW, "DELETED", true);
    String jobId =
        purgeStore
            .claimEligibleBatch(NOW, 1, "collector", null, "lease-correlation")
            .orElseThrow()
            .getPurgeJobId();

    IcebergPurgeJobPO first = purgeStore.takeJob("worker-1", NOW, 100, 10).orElseThrow();
    Assertions.assertEquals(jobId, first.getPurgeJobId());
    Assertions.assertEquals(1, first.getLeaseEpoch());
    Assertions.assertTrue(purgeStore.heartbeat(jobId, "worker-1", 1, NOW + 50, 100));
    Assertions.assertTrue(purgeStore.takeJob("worker-2", NOW + 149, 100, 10).isEmpty());

    IcebergPurgeJobPO reclaimed = purgeStore.takeJob("worker-2", NOW + 150, 100, 10).orElseThrow();
    Assertions.assertEquals(2, reclaimed.getLeaseEpoch());
    Assertions.assertEquals("worker-2", reclaimed.getOwner());
    Assertions.assertEquals(1, purgeStore.countAudits(jobId, "PURGE_RECLAIMED"));
    Assertions.assertFalse(purgeStore.heartbeat(jobId, "worker-1", 1, NOW + 151, 100));
    Assertions.assertTrue(purgeStore.heartbeat(jobId, "worker-2", 2, NOW + 151, 100));
  }

  @TestTemplate
  void testReclaimResetsInterruptedItemAndTargetWhileFencingStaleCompletion() {
    insertDeletion("deep-fence", NOW, "DELETED", true);
    String jobId =
        purgeStore
            .claimEligibleBatch(NOW, 1, "collector", null, "deep-fence-correlation")
            .orElseThrow()
            .getPurgeJobId();
    IcebergPurgeJobPO first = purgeStore.takeJob("worker-1", NOW, 100, 10).orElseThrow();
    EntityDeletionPO action =
        purgeStore
            .beginAction("deep-fence", jobId, "worker-1", first.getLeaseEpoch(), NOW)
            .orElseThrow();
    purgeStore.beginPlan("deep-fence", jobId, digest("deep-fence"), NOW);
    IcebergPurgeTargetPO root = target("deep-fence", jobId, "root", "ROOT_METADATA", 100);
    purgeStore.addTargetBatch(List.of(root));
    purgeStore.completePlan("deep-fence", jobId, digest("deep-fence"), "root", NOW);
    IcebergPurgeTargetPO oldClaim =
        purgeStore
            .claimTargetBatch("deep-fence", jobId, "worker-1", first.getLeaseEpoch(), NOW, 1)
            .get(0);

    IcebergPurgeJobPO reclaimed = purgeStore.takeJob("worker-2", NOW + 100, 100, 10).orElseThrow();
    Assertions.assertEquals(2, reclaimed.getLeaseEpoch());
    Assertions.assertEquals("PENDING", purgeStore.getAction("deep-fence").getCleanupStatus());
    Assertions.assertEquals("RETRYING", purgeStore.getTarget("deep-fence", "root").getState());
    Assertions.assertFalse(
        purgeStore.markTargetSucceeded(oldClaim, "worker-1", first.getLeaseEpoch(), NOW + 101));
    Assertions.assertFalse(
        purgeStore.yieldAction(action, "worker-1", first.getLeaseEpoch(), NOW + 101));

    Assertions.assertTrue(
        purgeStore
            .beginAction("deep-fence", jobId, "worker-2", reclaimed.getLeaseEpoch(), NOW + 101)
            .isPresent());
    IcebergPurgeTargetPO newClaim =
        purgeStore
            .claimTargetBatch(
                "deep-fence", jobId, "worker-2", reclaimed.getLeaseEpoch(), NOW + 101, 1)
            .get(0);
    Assertions.assertEquals(2, newClaim.getLeaseEpoch());
    Assertions.assertTrue(
        purgeStore.markTargetSucceeded(newClaim, "worker-2", reclaimed.getLeaseEpoch(), NOW + 101));
    Assertions.assertEquals(2, purgeStore.countAudits(jobId, "PURGE_STARTED"));
    Assertions.assertEquals(1, purgeStore.countAudits(jobId, "PURGE_RECLAIMED"));
  }

  @TestTemplate
  void testPlanIsStreamedIdempotentAndRootLastBeforeReady() {
    insertDeletion("planned", NOW, "DELETED", true);
    String jobId =
        purgeStore
            .claimEligibleBatch(NOW, 1, "collector", null, "plan-correlation")
            .orElseThrow()
            .getPurgeJobId();
    purgeStore.beginPlan("planned", jobId, digest("planned"), NOW);

    IcebergPurgeTargetPO data = target("planned", jobId, "data", "DATA", 10);
    IcebergPurgeTargetPO root = target("planned", jobId, "root", "ROOT_METADATA", 100);
    purgeStore.addTargetBatch(List.of(data));
    purgeStore.addTargetBatch(List.of(root));
    purgeStore.addTargetBatch(List.of(data, root));

    Assertions.assertEquals(
        2, purgeStore.completePlan("planned", jobId, digest("planned"), "root", NOW + 1));
    IcebergPurgePlanPO ready = purgeStore.getPlan("planned");
    Assertions.assertEquals("READY", ready.getState());
    Assertions.assertEquals(2, ready.getTargetCount());
    Assertions.assertThrows(
        IllegalStateException.class, () -> purgeStore.addTargetBatch(List.of(data)));
  }

  @TestTemplate
  void testPlanRejectsMissingOrNonLastRoot() {
    insertDeletion("bad-plan", NOW, "DELETED", true);
    String jobId =
        purgeStore
            .claimEligibleBatch(NOW, 1, "collector", null, "bad-plan-correlation")
            .orElseThrow()
            .getPurgeJobId();
    purgeStore.beginPlan("bad-plan", jobId, digest("bad-plan"), NOW);
    purgeStore.addTargetBatch(
        List.of(
            target("bad-plan", jobId, "root", "ROOT_METADATA", 10),
            target("bad-plan", jobId, "manifest", "MANIFEST", 20)));

    Assertions.assertThrows(
        IllegalStateException.class,
        () -> purgeStore.completePlan("bad-plan", jobId, digest("bad-plan"), "root", NOW + 1));
    Assertions.assertEquals("PLANNING", purgeStore.getPlan("bad-plan").getState());
  }

  private void insertDeletion(
      String deletionId, Long retentionExpiresAt, String state, boolean includeContext) {
    EntityDeletionService.getInstance()
        .insert(
            EntityDeletionPO.builder()
                .deletionId(deletionId)
                .entityType("TABLE")
                .entityId(1000L + Math.abs((long) deletionId.hashCode()))
                .entityVersion(1L)
                .metalakeId(10L)
                .catalogId(20L)
                .parentId(30L)
                .namespaceSnapshot("db")
                .entityNameSnapshot(deletionId)
                .nameLookupKey("lookup-" + deletionId)
                .activeNameKey("active-" + deletionId)
                .state(state)
                .revision(0L)
                .deletedAt(100L + Math.abs(deletionId.hashCode() % 100))
                .retentionExpiresAt(retentionExpiresAt)
                .deletedBy("alice")
                .purgeRequested(true)
                .purgeJobType(IcebergPurgeJobStore.PURGE_JOB_TYPE)
                .cleanupStatus("PENDING")
                .cleanupAttemptCount(0)
                .requestId("delete-" + deletionId)
                .correlationId("correlation-" + deletionId)
                .updatedAt(100L)
                .build());
    if (includeContext) {
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
    }
  }

  private static IcebergPurgeTargetPO target(
      String deletionId, String jobId, String targetId, String targetType, int deleteOrder) {
    return IcebergPurgeTargetPO.builder()
        .withDeletionId(deletionId)
        .withTargetId(targetId)
        .withPurgeJobId(jobId)
        .withTargetType(targetType)
        .withTargetUri("s3://bucket/" + deletionId + "/" + targetId)
        .withObjectVersion("version-" + targetId)
        .withDeleteOrder(deleteOrder)
        .withState("PENDING")
        .withLeaseEpoch(0L)
        .withAttemptCount(0)
        .withCreatedAt(NOW)
        .withUpdatedAt(NOW)
        .build();
  }

  private static String digest(String deletionId) {
    return String.format("%064d", Math.abs(deletionId.hashCode()));
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  private static void collectSuccessfulClaim(
      Future<Optional<IcebergPurgeJobPO>> result, List<IcebergPurgeJobPO> winners)
      throws InterruptedException, TimeoutException {
    try {
      result.get(20, TimeUnit.SECONDS).ifPresent(winners::add);
    } catch (ExecutionException concurrentWriteFailure) {
      // Some JDBC engines surface the losing update as a serialization/deadlock retry rather than
      // an update count of zero. The collector caller may retry; the invariant under test is that
      // the loser cannot leave an empty durable batch.
      Assertions.assertNotNull(concurrentWriteFailure.getCause());
    }
  }

  private static EntityDeletionPO deletion(String deletionId) {
    return EntityDeletionService.getInstance().get(deletionId);
  }

  private static void assertPurging(String deletionId) {
    EntityDeletionPO action = deletion(deletionId);
    Assertions.assertEquals("PURGING", action.getState());
    Assertions.assertEquals("PENDING", action.getCleanupStatus());
    Assertions.assertNotNull(action.getPurgeJobId());
    Assertions.assertEquals(1, action.getRevision());
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
}
