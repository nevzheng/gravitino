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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.ws.rs.core.Response;
import org.apache.gravitino.iceberg.service.IcebergRESTUtils;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.rest.responses.LoadTableResponse;
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
  public void testSuccessfulUndropResponseHasLiveTableEtag() {
    TableMetadata metadata = mock(TableMetadata.class);
    when(metadata.metadataFileLocation())
        .thenReturn("s3://warehouse/sales/orders/metadata/v1.metadata.json");
    LoadTableResponse loadTableResponse = mock(LoadTableResponse.class);
    when(loadTableResponse.tableMetadata()).thenReturn(metadata);

    Response response = IcebergDeletionManagementOperations.liveTableResponse(loadTableResponse);

    assertEquals(200, response.getStatus());
    assertEquals(loadTableResponse, response.getEntity());
    assertNotNull(response.getHeaderString("ETag"));
    assertEquals(
        '"'
            + IcebergRESTUtils.generateETag(metadata.metadataFileLocation())
                .orElseThrow()
                .getValue()
            + '"',
        response.getHeaderString("ETag"));
  }
}
