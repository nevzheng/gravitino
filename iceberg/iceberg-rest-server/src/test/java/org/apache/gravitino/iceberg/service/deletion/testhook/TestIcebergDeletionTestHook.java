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
package org.apache.gravitino.iceberg.service.deletion.testhook;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionNamespaceCodec;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeException;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeTargetDeleter;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.gravitino.iceberg.service.deletion.testhook.IcebergDeletionTestHookController.Fault;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIcebergDeletionTestHook {

  private static final String TOKEN = "integration-secret";

  @Test
  public void testFaultRunsAfterRealSuccessAndClearRestoresDelegate() throws Exception {
    IcebergDeletionTestHookController controller = new IcebergDeletionTestHookController();
    String namespace = IcebergDeletionNamespaceCodec.encode(new String[] {"db"});
    controller.install(20L, namespace, "table", Fault.THROTTLING, 1);
    AtomicInteger realDeletes = new AtomicInteger();
    IcebergPurgeTargetDeleter deleter =
        controller.decorate((context, target) -> realDeletes.incrementAndGet());

    deleter.delete(context(), target("first"));
    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class, () -> deleter.delete(context(), target("second")));
    Assertions.assertEquals(1, realDeletes.get());
    Assertions.assertTrue(failure.retryable());
    Assertions.assertEquals("THROTTLED", failure.reasonCode());

    controller.clear(20L, namespace, "table");
    deleter.delete(context(), target("third"));
    Assertions.assertEquals(2, realDeletes.get());
  }

  @Test
  public void testPermissionFaultIsPermanent() throws Exception {
    IcebergDeletionTestHookController controller = new IcebergDeletionTestHookController();
    String namespace = IcebergDeletionNamespaceCodec.encode(new String[] {"db"});
    controller.install(20L, namespace, "table", Fault.PERMISSION_DENIED, 0);
    IcebergPurgeTargetDeleter deleter = controller.decorate((context, target) -> {});

    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class, () -> deleter.delete(context(), target("target")));
    Assertions.assertFalse(failure.retryable());
    Assertions.assertEquals("PERMISSION_DENIED", failure.reasonCode());
  }

  @Test
  public void testHookAuthenticationContractAndSafeResponses() throws Exception {
    IcebergDeletionTestHookController controller = new IcebergDeletionTestHookController();
    AtomicInteger runs = new AtomicInteger();
    IcebergDeletionTestHookOperations operations =
        new IcebergDeletionTestHookOperations(
            controller,
            catalog -> 20L,
            (actor, correlationId) -> {
              Assertions.assertEquals("iceberg-deletion-test-hook", actor);
              Assertions.assertFalse(correlationId.isBlank());
              runs.incrementAndGet();
              return 2;
            },
            TOKEN);
    Map<String, Object> request =
        Map.of(
            "catalog",
            "catalog",
            "namespace",
            "db",
            "table",
            "table",
            "operation",
            "ICEBERG_REST_PURGE",
            "fault",
            "SERVER_ERROR",
            "afterSuccessfulTargets",
            1);

    Response unauthorized = operations.installFault("wrong-token", request);
    Assertions.assertEquals(401, unauthorized.getStatus());
    Assertions.assertFalse(unauthorized.getEntity().toString().contains(TOKEN));
    Assertions.assertEquals(204, operations.installFault(TOKEN, request).getStatus());

    AtomicInteger deletes = new AtomicInteger();
    IcebergPurgeTargetDeleter decorated =
        controller.decorate((context, target) -> deletes.incrementAndGet());
    decorated.delete(context(), target("one"));
    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class, () -> decorated.delete(context(), target("two")));
    Assertions.assertTrue(failure.retryable());
    Assertions.assertEquals("PROVIDER_SERVER_ERROR", failure.reasonCode());

    Assertions.assertEquals(
        204, operations.clearFault(TOKEN, "catalog", "db", "table").getStatus());
    Response run = operations.runCleanupOnce(TOKEN);
    Assertions.assertEquals(200, run.getStatus());
    Assertions.assertEquals(1, runs.get());
    Assertions.assertEquals(2, ((Map<?, ?>) run.getEntity()).get("redrivenActions"));
    Assertions.assertFalse(run.getEntity().toString().contains(TOKEN));

    IcebergDeletionTestHookOperations failingOperations =
        new IcebergDeletionTestHookOperations(
            controller,
            catalog -> 20L,
            (actor, correlationId) -> {
              throw new IllegalStateException("token=" + TOKEN);
            },
            TOKEN);
    Response failedRun = failingOperations.runCleanupOnce(TOKEN);
    Assertions.assertEquals(500, failedRun.getStatus());
    Assertions.assertFalse(failedRun.getEntity().toString().contains(TOKEN));
  }

  @Test
  public void testHookHasStableConfiguredBasePath() {
    Assertions.assertEquals(
        "/test/v1/deletion",
        IcebergDeletionTestHookOperations.class.getAnnotation(Path.class).value());
  }

  private static IcebergDeletionContextPO context() {
    return IcebergDeletionContextPO.builder()
        .withDeletionId("deletion")
        .withIcebergNamespace(IcebergDeletionNamespaceCodec.encode(new String[] {"db"}))
        .withIcebergTableName("table")
        .withIcebergTableUuid("uuid")
        .withMetadataLocation("s3://bucket/root.json")
        .withFileIoImpl("file-io")
        .withProtectedFileIoRef("catalog-id:20")
        .withContextDigest("digest")
        .withCreatedAt(1L)
        .withUpdatedAt(1L)
        .build();
  }

  private static IcebergPurgeTargetPO target(String id) {
    return IcebergPurgeTargetPO.builder()
        .withDeletionId("deletion")
        .withTargetId(id)
        .withPurgeJobId("job")
        .withTargetType("DATA")
        .withTargetUri("s3://bucket/" + id)
        .withDeleteOrder(1)
        .withState("RUNNING")
        .withLeaseEpoch(1L)
        .withAttemptCount(1)
        .withCreatedAt(1L)
        .withUpdatedAt(1L)
        .build();
  }
}
