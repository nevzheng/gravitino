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
package org.apache.gravitino.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.hc.core5.http.HttpHeaders;
import org.junit.jupiter.api.Test;

class TestRecoverableDeletionClient {

  @Test
  void testListAndLoadDeleted() {
    RESTClient restClient = mock(RESTClient.class);
    RecoverableDeletionClient recoveryClient = new RecoverableDeletionClient(restClient);
    DeletedEntityDTO deletedEntity = newDeletedEntity().build();

    when(restClient.get(
            eq("api/tables"),
            eq(ImmutableMap.of("include", "deleted", "name", "orders", "id", "984273")),
            eq(DeletedEntityListResponse.class),
            eq(Collections.emptyMap()),
            any(ErrorHandler.class)))
        .thenReturn(new DeletedEntityListResponse(new DeletedEntityDTO[] {deletedEntity}));
    when(restClient.get(
            eq("api/tables/orders"),
            eq(ImmutableMap.of("include", "deleted", "id", "984273")),
            eq(DeletedEntityResponse.class),
            eq(Collections.emptyMap()),
            any(ErrorHandler.class)))
        .thenReturn(new DeletedEntityResponse(deletedEntity));

    DeletedEntity[] listed =
        recoveryClient.listDeleted(
            "api/tables", "orders", "984273", ErrorHandlers.tableErrorHandler());
    DeletedEntity loaded =
        recoveryClient.loadDeleted(
            "api/tables/orders", "984273", ErrorHandlers.tableErrorHandler());

    assertEquals(1, listed.length);
    assertSame(deletedEntity, listed[0]);
    assertSame(deletedEntity, loaded);
  }

  @Test
  void testScopedDeletedList() {
    RESTClient restClient = mock(RESTClient.class);
    RecoverableDeletionClient recoveryClient = new RecoverableDeletionClient(restClient);
    DeletedEntityDTO deletedEntity = newDeletedEntity().build();

    when(restClient.get(
            eq("api/schemas"),
            eq(
                ImmutableMap.of(
                    "parentSchema",
                    "sales",
                    "include",
                    "deleted",
                    "name",
                    "sales:orders",
                    "id",
                    "984273")),
            eq(DeletedEntityListResponse.class),
            eq(Collections.emptyMap()),
            any(ErrorHandler.class)))
        .thenReturn(new DeletedEntityListResponse(new DeletedEntityDTO[] {deletedEntity}));

    DeletedEntity[] listed =
        recoveryClient.listDeleted(
            "api/schemas",
            ImmutableMap.of("parentSchema", "sales"),
            "sales:orders",
            "984273",
            ErrorHandlers.schemaErrorHandler());

    assertEquals(1, listed.length);
    assertSame(deletedEntity, listed[0]);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            recoveryClient.listDeleted(
                "api/schemas",
                ImmutableMap.of("include", "all"),
                null,
                null,
                ErrorHandlers.schemaErrorHandler()));
  }

  @Test
  void testRestoreUsesExactSelectorsAndStrongPrecondition() {
    RESTClient restClient = mock(RESTClient.class);
    RecoverableDeletionClient recoveryClient = new RecoverableDeletionClient(restClient);
    DeletedEntityDTO deletedEntity = newDeletedEntity().build();
    BaseResponse expected = new BaseResponse(0);

    when(restClient.patch(
            eq("api/tables/orders"),
            eq(ImmutableMap.of("include", "deleted", "id", "984273")),
            argThat(
                request ->
                    request instanceof EntityRestoreRequest
                        && Boolean.FALSE.equals(((EntityRestoreRequest) request).getDeleted())),
            eq(BaseResponse.class),
            argThat(
                headers ->
                    RecoverableDeletionClient.MERGE_PATCH_CONTENT_TYPE.equals(
                            headers.get(HttpHeaders.CONTENT_TYPE))
                        && ('"' + deletedEntity.getEtag() + '"')
                            .equals(headers.get(HttpHeaders.IF_MATCH))),
            any(ErrorHandler.class)))
        .thenReturn(expected);

    BaseResponse actual =
        recoveryClient.restoreDeleted(
            "api/tables/orders",
            deletedEntity,
            BaseResponse.class,
            ErrorHandlers.tableErrorHandler());

    assertSame(expected, actual);
  }

  @Test
  void testRestoreRejectsInvalidPreconditionBeforeSending() {
    RESTClient restClient = mock(RESTClient.class);
    RecoverableDeletionClient recoveryClient = new RecoverableDeletionClient(restClient);

    for (String invalidEtag :
        new String[] {"W/weak", "\"quoted\"", "contains space", "contains\ttab", "snowman-☃"}) {
      DeletedEntityDTO invalidGeneration = newDeletedEntity().withEtag(invalidEtag).build();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              recoveryClient.restoreDeleted(
                  "api/tables/orders",
                  invalidGeneration,
                  BaseResponse.class,
                  ErrorHandlers.tableErrorHandler()));
    }
  }

  @Test
  void testRecoveryErrorMapping() {
    ErrorHandler handler = ErrorHandlers.recoveryErrorHandler(ErrorHandlers.tableErrorHandler());

    assertThrows(
        TombstoneNotFoundException.class,
        () ->
            handler.accept(
                ErrorResponse.notFound(
                    TombstoneNotFoundException.class.getSimpleName(), "not found")));
    assertThrows(
        TombstoneExpiredException.class,
        () -> handler.accept(ErrorResponse.tombstoneExpired("expired", null)));
    assertThrows(
        TombstoneChangedException.class,
        () -> handler.accept(ErrorResponse.tombstoneChanged("changed", null)));
    assertThrows(
        PreconditionRequiredException.class,
        () -> handler.accept(ErrorResponse.preconditionRequired("required", null)));

    RecoveryConflictException conflict =
        assertThrows(
            RecoveryConflictException.class,
            () ->
                handler.accept(
                    ErrorResponse.recoveryConflict(
                        RecoveryConflictReason.NAME_OCCUPIED, "occupied", null)));
    assertEquals(RecoveryConflictReason.NAME_OCCUPIED, conflict.getReason());
  }

  @Test
  void testPublicDeletedEntityState() {
    DeletedEntity deletedEntity = newDeletedEntity().build();

    assertTrue(deletedEntity.deleted());
    assertTrue(deletedEntity.latestForName());
    assertTrue(deletedEntity.restorable());
    assertFalse(deletedEntity.etag().isEmpty());
  }

  private static DeletedEntityDTO.DeletedEntityDTOBuilder newDeletedEntity() {
    return DeletedEntityDTO.builder()
        .withId("984273")
        .withDeletionId("77192")
        .withName("orders")
        .withType(RecoveryEntityType.TABLE)
        .withDeletedAt(1784800000000L)
        .withExpiresAt(1785404800000L)
        .withDeletedBy("alice")
        .withVersion(17L)
        .withEtag(
            "deletion-77192-representation-"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withLatestForName(true)
        .withRestorable(true);
  }
}
