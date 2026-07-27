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

package org.apache.gravitino.iceberg.service.deletion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.TableDeletionService;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Tests exact-generation authorization after a table's live owner lookup is tombstoned. */
public class TestIcebergDeletionAuthorization {

  private static final String PARENT_USE = "ANY_USE_CATALOG && ANY_USE_SCHEMA";

  @Test
  void testRetainedOwnerFallbackRequiresCurrentParentUse() {
    NameIdentifier identifier = NameIdentifier.of("metalake", "catalog", "schema", "table");
    EntityDeletionPO deletion = EntityDeletionPO.builder().deletionId("D1").entityId(42L).build();
    UserPrincipal principal = new UserPrincipal("alice");
    TableDeletionService tableDeletionService = mock(TableDeletionService.class);
    when(tableDeletionService.isRetainedOwner(deletion, principal)).thenReturn(true);

    try (MockedStatic<MetadataAuthzHelper> authorization = mockStatic(MetadataAuthzHelper.class);
        MockedStatic<TableDeletionService> tableDeletions = mockStatic(TableDeletionService.class);
        MockedStatic<PrincipalUtils> principals = mockStatic(PrincipalUtils.class)) {
      authorization
          .when(
              () ->
                  MetadataAuthzHelper.checkAccess(
                      identifier,
                      Entity.EntityType.TABLE,
                      AuthorizationExpressionConstants.ICEBERG_DROP_TABLE_AUTHORIZATION_EXPRESSION))
          .thenReturn(false);
      authorization
          .when(
              () ->
                  MetadataAuthzHelper.checkAccess(identifier, Entity.EntityType.TABLE, PARENT_USE))
          .thenReturn(true);
      tableDeletions.when(TableDeletionService::getInstance).thenReturn(tableDeletionService);
      principals.when(PrincipalUtils::getCurrentPrincipal).thenReturn(principal);

      assertTrue(IcebergDeletionAuthorization.canDrop(identifier, deletion));

      authorization
          .when(
              () ->
                  MetadataAuthzHelper.checkAccess(identifier, Entity.EntityType.TABLE, PARENT_USE))
          .thenReturn(false);
      assertFalse(IcebergDeletionAuthorization.canDrop(identifier, deletion));
      verify(tableDeletionService).isRetainedOwner(deletion, principal);
    }
  }

  @Test
  void testLiveAuthorizationDoesNotReadRetainedOwnership() {
    NameIdentifier identifier = NameIdentifier.of("metalake", "catalog", "schema", "table");
    TableDeletionService tableDeletionService = mock(TableDeletionService.class);

    try (MockedStatic<MetadataAuthzHelper> authorization = mockStatic(MetadataAuthzHelper.class);
        MockedStatic<TableDeletionService> tableDeletions =
            mockStatic(TableDeletionService.class)) {
      authorization
          .when(
              () ->
                  MetadataAuthzHelper.checkAccess(
                      identifier,
                      Entity.EntityType.TABLE,
                      AuthorizationExpressionConstants.ICEBERG_DROP_TABLE_AUTHORIZATION_EXPRESSION))
          .thenReturn(true);
      tableDeletions.when(TableDeletionService::getInstance).thenReturn(tableDeletionService);

      assertTrue(IcebergDeletionAuthorization.canDrop(identifier, null));
      verifyNoInteractions(tableDeletionService);
    }
  }
}
