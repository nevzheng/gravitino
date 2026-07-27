/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.function.Consumer;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.junit.jupiter.api.Test;

/** Tests strong validators for the complete public deletion-action representation. */
public class TestIcebergDeletionETags {

  private static final long BEFORE_EXPIRY = 199L;

  @Test
  public void testTagChangesAtTheRecoveryDeadline() {
    EntityDeletionPO deletion = deletion();

    assertEquals(
        IcebergDeletionETags.strongTag(deletion, 100L),
        IcebergDeletionETags.strongTag(deletion, BEFORE_EXPIRY));
    assertNotEquals(
        IcebergDeletionETags.strongTag(deletion, BEFORE_EXPIRY),
        IcebergDeletionETags.strongTag(deletion, 200L));
    assertEquals(
        IcebergDeletionETags.strongTag(deletion, 200L),
        IcebergDeletionETags.strongTag(deletion, 300L));
  }

  @Test
  public void testEveryPublicPersistedFieldChangesTheTag() {
    String baseline = tag(ignored -> {});

    assertNotEquals(baseline, tag(value -> value.setDeletionId("D2")));
    assertNotEquals(baseline, tag(value -> value.setEntityId(12L)));
    assertNotEquals(baseline, tag(value -> value.setState("PURGING")));
    assertNotEquals(baseline, tag(value -> value.setRevision(2L)));
    assertNotEquals(baseline, tag(value -> value.setDeletedAt(101L)));
    assertNotEquals(baseline, tag(value -> value.setRetentionExpiresAt(201L)));
    assertNotEquals(baseline, tag(value -> value.setCleanupStatus("FAILED")));
    assertNotEquals(baseline, tag(value -> value.setPurgeJobId("J1")));
    assertNotEquals(baseline, tag(value -> value.setCleanupAttemptCount(2)));
    assertNotEquals(baseline, tag(value -> value.setCleanupLastError("safe failure")));
    assertNotEquals(baseline, tag(value -> value.setDeletedBy("bob")));
    assertNotEquals(baseline, tag(value -> value.setRestoredAt(180L)));
    assertNotEquals(baseline, tag(value -> value.setPurgedAt(190L)));
  }

  private static String tag(Consumer<EntityDeletionPO> mutation) {
    EntityDeletionPO deletion = deletion();
    mutation.accept(deletion);
    return IcebergDeletionETags.strongTag(deletion, BEFORE_EXPIRY);
  }

  private static EntityDeletionPO deletion() {
    return EntityDeletionPO.builder()
        .deletionId("D1")
        .entityType("TABLE")
        .entityId(11L)
        .entityVersion(1L)
        .metalakeId(1L)
        .catalogId(2L)
        .parentId(3L)
        .namespaceSnapshot("1:2:db")
        .entityNameSnapshot("orders")
        .nameLookupKey("lookup")
        .activeNameKey("active")
        .state("DELETED")
        .revision(1L)
        .deletedAt(100L)
        .retentionExpiresAt(200L)
        .deletedBy("alice")
        .purgeRequested(false)
        .purgeJobType("ICEBERG_REST_PURGE")
        .cleanupStatus("PENDING")
        .cleanupAttemptCount(1)
        .updatedAt(100L)
        .build();
  }
}
