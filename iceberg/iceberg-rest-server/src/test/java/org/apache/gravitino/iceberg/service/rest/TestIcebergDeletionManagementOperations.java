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
package org.apache.gravitino.iceberg.service.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletedTable;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletedTableMetadata;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionAction;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.gravitino.iceberg.service.deletion.IcebergUndropRequest;
import org.junit.jupiter.api.Test;

/** Tests strict strong-ETag parsing for conditional UNDROP. */
public class TestIcebergDeletionManagementOperations {

  @Test
  public void testParseOneStrongIfMatch() {
    assertEquals(
        "iceberg-deletion-D1-r0-0123456789abcdef",
        IcebergDeletionManagementOperations.parseStrongIfMatch(
            "\"iceberg-deletion-D1-r0-0123456789abcdef\""));
  }

  @Test
  public void testMissingIfMatchRequiresPrecondition() {
    IcebergDeletionException error =
        assertThrows(
            IcebergDeletionException.class,
            () -> IcebergDeletionManagementOperations.parseStrongIfMatch(null));
    assertEquals(Outcome.PRECONDITION_REQUIRED, error.outcome());
  }

  @Test
  public void testWeakWildcardAndListsAreRejected() {
    for (String value :
        new String[] {
          "W/\"iceberg-deletion-D1-r0-tag\"", "*", "\"one\", \"two\"", "iceberg-deletion-D1-r0-tag"
        }) {
      IcebergDeletionException error =
          assertThrows(
              IcebergDeletionException.class,
              () -> IcebergDeletionManagementOperations.parseStrongIfMatch(value));
      assertEquals(Outcome.BAD_REQUEST, error.outcome());
    }
  }

  @Test
  public void testUndropBodyRequiresDeletionId() {
    assertEquals(
        "D1", IcebergDeletionManagementOperations.parseDeletionId(new IcebergUndropRequest("D1")));
    for (IcebergUndropRequest request :
        new IcebergUndropRequest[] {
          null, new IcebergUndropRequest(null), new IcebergUndropRequest(" ")
        }) {
      IcebergDeletionException error =
          assertThrows(
              IcebergDeletionException.class,
              () -> IcebergDeletionManagementOperations.parseDeletionId(request));
      assertEquals(Outcome.BAD_REQUEST, error.outcome());
    }
  }

  @Test
  public void testUndropBodyUsesApprovedCamelCaseField() throws Exception {
    IcebergUndropRequest request =
        IcebergObjectMapper.getInstance()
            .readValue("{\"deletionId\":\"D1\"}", IcebergUndropRequest.class);
    assertEquals("D1", request.getDeletionId());
  }

  @Test
  public void testDeletedTableResponseIsSafeAndCarriesReusableEtag() throws Exception {
    IcebergDeletedTable deletedTable =
        IcebergDeletedTable.builder()
            .table(
                IcebergDeletedTableMetadata.builder()
                    .id("42")
                    .namespace(List.of("sales"))
                    .name("orders")
                    .version(7L)
                    .build())
            .deletion(
                IcebergDeletionAction.builder()
                    .deletionId("D1")
                    .entityId("42")
                    .state("PURGING")
                    .revision(3L)
                    .deletedAt(100L)
                    .retentionExpiresAt(200L)
                    .purgeJobId("job-1")
                    .deletedBy("alice")
                    .recoverable(false)
                    .build())
            .etag("\"iceberg-deletion-D1-r3-0123456789abcdef\"")
            .build();

    String json = IcebergObjectMapper.getInstance().writeValueAsString(deletedTable);
    assertTrue(json.contains("\"etag\":\"\\\"iceberg-deletion-D1-r3-0123456789abcdef\\\"\""));
    assertTrue(json.contains("\"deletionId\":\"D1\""));
    assertTrue(json.contains("\"deletedAt\":100"));
    assertTrue(json.contains("\"retentionExpiresAt\":200"));
    assertTrue(json.contains("\"purgeJobId\":\"job-1\""));
    String normalized = json.toLowerCase(Locale.ROOT);
    assertFalse(normalized.contains("cleanup"));
    assertFalse(normalized.contains("metadata-location"));
    assertFalse(normalized.contains("metadatalocation"));
    assertFalse(normalized.contains("fileio"));
    assertFalse(normalized.contains("credential"));
  }
}
