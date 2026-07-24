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

import java.util.List;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestTemplate;

public class TestEntityDeletionService extends TestJDBCBackend {

  private static final long DELETED_AT = 1_784_800_000_000L;
  private static final long RETENTION_MS = 604_800_000L;
  private static final String RESTORE_ETAG = "deletion-test-representation-restore-etag";

  @TestTemplate
  public void testDeletionGenerationLifecycle() {
    EntityDeletionService service = EntityDeletionService.getInstance();
    EntityDeletionPO deletion =
        service.newDeletion(
            Entity.EntityType.TABLE,
            984273L,
            100L,
            200L,
            300L,
            "orders",
            17L,
            DELETED_AT,
            RETENTION_MS,
            "alice");

    service.insert(deletion);

    EntityDeletionPO loaded = service.get(deletion.getDeletionId());
    Assertions.assertNotNull(loaded);
    Assertions.assertEquals(Entity.EntityType.TABLE.name(), loaded.getEntityType());
    Assertions.assertEquals(984273L, loaded.getEntityId());
    Assertions.assertEquals(300L, loaded.getParentId());
    Assertions.assertEquals("orders", loaded.getEntityName());
    Assertions.assertEquals(DELETED_AT + RETENTION_MS, loaded.getExpiresAt());
    Assertions.assertEquals(DeletionState.DELETED, loaded.getState());
    Assertions.assertNull(loaded.getRestoreEtag());
    List<EntityDeletionPO> byName =
        service.list(Entity.EntityType.TABLE, 300L, "orders", null, DeletionState.DELETED);
    Assertions.assertEquals(1, byName.size());
    Assertions.assertEquals(deletion.getDeletionId(), byName.get(0).getDeletionId());

    List<EntityDeletionPO> byId =
        service.list(Entity.EntityType.TABLE, 300L, null, 984273L, DeletionState.DELETED);
    Assertions.assertEquals(1, byId.size());

    Assertions.assertTrue(
        service.compareAndSetState(
            deletion.getDeletionId(),
            DeletionState.DELETED,
            0L,
            DeletionState.RESTORING,
            null,
            null));
    Assertions.assertFalse(
        service.compareAndSetState(
            deletion.getDeletionId(),
            DeletionState.DELETED,
            0L,
            DeletionState.RESTORING,
            null,
            null));

    long restoredAt = DELETED_AT + 1_000L;
    Assertions.assertTrue(
        service.compareAndSetState(
            deletion.getDeletionId(),
            DeletionState.RESTORING,
            1L,
            DeletionState.RESTORED,
            restoredAt,
            RESTORE_ETAG));

    EntityDeletionPO restored = service.get(deletion.getDeletionId());
    Assertions.assertEquals(DeletionState.RESTORED, restored.getState());
    Assertions.assertEquals(2L, restored.getRevision());
    Assertions.assertEquals(restoredAt, restored.getRestoredAt());
    Assertions.assertEquals(RESTORE_ETAG, restored.getRestoreEtag());
  }

  @TestTemplate
  public void testDeletionListFiltersByImmutableParent() {
    EntityDeletionService service = EntityDeletionService.getInstance();
    EntityDeletionPO first =
        service.newDeletion(
            Entity.EntityType.TABLE,
            1L,
            10L,
            20L,
            30L,
            "orders",
            1L,
            DELETED_AT,
            RETENTION_MS,
            null);
    EntityDeletionPO second =
        service.newDeletion(
            Entity.EntityType.TABLE,
            2L,
            10L,
            20L,
            31L,
            "orders",
            1L,
            DELETED_AT + 1L,
            RETENTION_MS,
            null);

    service.insert(first);
    service.insert(second);

    List<EntityDeletionPO> parent30 =
        service.list(Entity.EntityType.TABLE, 30L, null, null, DeletionState.DELETED);
    Assertions.assertEquals(1, parent30.size());
    Assertions.assertEquals(first.getDeletionId(), parent30.get(0).getDeletionId());
  }

  @TestTemplate
  public void testCurrentGlobalExpiryAndBatchedTerminalReceiptCleanup() {
    EntityDeletionService service = EntityDeletionService.getInstance();
    EntityDeletionPO first =
        service.newDeletion(
            Entity.EntityType.TABLE,
            11L,
            10L,
            20L,
            30L,
            "orders_11",
            1L,
            DELETED_AT,
            RETENTION_MS,
            null);
    EntityDeletionPO second =
        service.newDeletion(
            Entity.EntityType.TABLE,
            12L,
            10L,
            20L,
            30L,
            "orders_12",
            1L,
            DELETED_AT + 1L,
            RETENTION_MS,
            null);
    service.insert(first);
    service.insert(second);

    // The current global cutoff, not the creation-time expiresAt snapshot, selects expiry.
    List<EntityDeletionPO> expired =
        service.listExpired(Entity.EntityType.TABLE, DELETED_AT + 2L, 1);
    Assertions.assertEquals(1, expired.size());
    Assertions.assertTrue(expired.get(0).getExpiresAt() > DELETED_AT + 2L);

    long restoredAt = DELETED_AT + 3L;
    for (EntityDeletionPO deletion : List.of(first, second)) {
      Assertions.assertTrue(
          service.compareAndSetState(
              deletion.getDeletionId(),
              DeletionState.DELETED,
              0L,
              DeletionState.RESTORING,
              null,
              null));
      Assertions.assertTrue(
          service.compareAndSetState(
              deletion.getDeletionId(),
              DeletionState.RESTORING,
              1L,
              DeletionState.RESTORED,
              restoredAt,
              RESTORE_ETAG));
    }

    Assertions.assertEquals(
        1, service.deleteTerminalReceipts(Entity.EntityType.TABLE, restoredAt + 1L, 1));
    int remaining =
        (service.get(first.getDeletionId()) == null ? 0 : 1)
            + (service.get(second.getDeletionId()) == null ? 0 : 1);
    Assertions.assertEquals(1, remaining);
    Assertions.assertEquals(
        1, service.deleteTerminalReceipts(Entity.EntityType.TABLE, restoredAt + 1L, 1));
    Assertions.assertNull(service.get(first.getDeletionId()));
    Assertions.assertNull(service.get(second.getDeletionId()));
  }
}
