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

import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.junit.jupiter.api.Test;

/** Tests the public deletion-action strong validator. */
public class TestIcebergDeletionETags {

  @Test
  public void testOperationalProgressDoesNotChangePublicEtag() {
    EntityDeletionPO deletion = deletion();
    String etag = IcebergDeletionETags.strongTag(deletion, 100L);

    deletion.setCleanupStatus("RUNNING");
    deletion.setCleanupAttemptCount(7);
    deletion.setCleanupLastError("sanitized worker error");
    assertEquals(etag, IcebergDeletionETags.strongTag(deletion, 100L));

    deletion.setState(IcebergTableDeletionLifecycle.PURGING);
    deletion.setRevision(1L);
    deletion.setPurgeJobId("job-1");
    assertNotEquals(etag, IcebergDeletionETags.strongTag(deletion, 100L));
  }

  @Test
  public void testExpiryChangesEtagWithRecoverableRepresentation() {
    EntityDeletionPO deletion = deletion();
    assertNotEquals(
        IcebergDeletionETags.strongTag(deletion, 199L),
        IcebergDeletionETags.strongTag(deletion, 200L));
  }

  private static EntityDeletionPO deletion() {
    return EntityDeletionPO.builder()
        .deletionId("D1")
        .entityId(42L)
        .state(IcebergTableDeletionLifecycle.DELETED)
        .revision(0L)
        .retentionExpiresAt(200L)
        .cleanupStatus("PENDING")
        .cleanupAttemptCount(0)
        .build();
  }
}
