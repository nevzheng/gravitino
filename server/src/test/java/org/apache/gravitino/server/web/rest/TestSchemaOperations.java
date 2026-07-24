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
package org.apache.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.CACHE_ENABLED;
import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.SCHEMA_SEPARATOR;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.Schema;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.SchemaCreateRequest;
import org.apache.gravitino.dto.requests.SchemaUpdateRequest;
import org.apache.gravitino.dto.requests.SchemaUpdatesRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.EntityListResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.SchemaResponse;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestSchemaOperations extends BaseOperationsTest {

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private SchemaOperationDispatcher dispatcher = mock(SchemaOperationDispatcher.class);
  private RecoverableDeletionManager recoverableDeletionManager =
      mock(RecoverableDeletionManager.class);

  private final String metalake = "metalake1";

  private final String catalog = "catalog1";

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    Mockito.doReturn(false).when(config).get(CACHE_ENABLED);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    Mockito.doReturn(":").when(config).get(SCHEMA_SEPARATOR);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(SchemaOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(dispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(recoverableDeletionManager).to(RecoverableDeletionManager.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListSchemas() {
    NameIdentifier ident1 = NameIdentifier.of(metalake, catalog, "schema1");
    NameIdentifier ident2 = NameIdentifier.of(metalake, catalog, "schema2");

    when(dispatcher.listSchemas(any())).thenReturn(new NameIdentifier[] {ident1, ident2});

    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    EntityListResponse listResp = resp.readEntity(EntityListResponse.class);
    Assertions.assertEquals(0, listResp.getCode());

    NameIdentifier[] idents = listResp.identifiers();
    Assertions.assertEquals(2, idents.length);
    Assertions.assertEquals(ident1, idents[0]);
    Assertions.assertEquals(ident2, idents[1]);

    // Test throw NoSuchCatalogException
    doThrow(new NoSuchCatalogException("mock error")).when(dispatcher).listSchemas(any());
    Response resp1 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp1.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp1.getMediaType());

    ErrorResponse errorResp = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchCatalogException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(dispatcher).listSchemas(any());
    Response resp2 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp2.getMediaType());

    ErrorResponse errorResp2 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp2.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp2.getType());
  }

  @Test
  public void testListSchemasWithParentSchemaPassToDispatcher() {
    NameIdentifier ident = NameIdentifier.of(metalake, catalog, "A:sales");
    when(dispatcher.listSchemas(any())).thenReturn(new NameIdentifier[] {ident});

    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .queryParam("parentSchema", "A")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    EntityListResponse listResp = resp.readEntity(EntityListResponse.class);
    Assertions.assertEquals(0, listResp.getCode());
    Assertions.assertEquals(1, listResp.identifiers().length);
    Assertions.assertEquals("A:sales", listResp.identifiers()[0].name());

    verify(dispatcher).listSchemas(eq(Namespace.of(metalake, catalog, "A")));
  }

  @Test
  public void testListLiveSchemasFiltersByFullLogicalNameInsideParentScope() {
    NameIdentifier matching = NameIdentifier.of(metalake, catalog, "A:sales");
    NameIdentifier other = NameIdentifier.of(metalake, catalog, "A:engineering");
    when(dispatcher.listSchemas(Namespace.of(metalake, catalog, "A")))
        .thenReturn(new NameIdentifier[] {matching, other});

    Response response =
        target(schemaPath())
            .queryParam("parentSchema", "A")
            .queryParam("name", "A:sales")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertArrayEquals(
        new NameIdentifier[] {matching},
        response.readEntity(EntityListResponse.class).identifiers());
    verify(dispatcher).listSchemas(Namespace.of(metalake, catalog, "A"));
  }

  @Test
  public void testListSchemasWithMalformedParentSchemaReturnsBadRequest() {
    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .queryParam("parentSchema", "A::B")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    // The malformed parentSchema must be rejected before reaching the dispatcher.
    verify(dispatcher, never()).listSchemas(any());
  }

  @Test
  public void testDeletedSchemaDiscoveryPreservesHierarchyAndExactRead() {
    Namespace schemaNamespace = NamespaceUtil.ofSchema(metalake, catalog);
    String topLevelEtag = deletionEtag("schema-top", 'a');
    String childEtag = deletionEtag("schema-child", 'b');
    DeletedEntityDTO topLevel = deletedSchema("A", "101", "schema-top", topLevelEtag);
    DeletedEntityDTO child = deletedSchema("A:B", "102", "schema-child", childEtag);
    when(recoverableDeletionManager.listDeletedSchemas(schemaNamespace, null, null, null))
        .thenReturn(List.of(topLevel));
    when(recoverableDeletionManager.listDeletedSchemas(schemaNamespace, "A", null, null))
        .thenReturn(List.of(child));
    when(recoverableDeletionManager.listDeletedSchemas(schemaNamespace, "A", "A:B", 102L))
        .thenReturn(List.of(child));
    when(recoverableDeletionManager.getDeletedSchema(schemaNamespace, "A:B", 102L))
        .thenReturn(child);

    Response rootResponse =
        target(schemaPath())
            .queryParam("include", "deleted")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), rootResponse.getStatus());
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {topLevel},
        rootResponse.readEntity(DeletedEntityListResponse.class).getDeletedEntities());

    Response childResponse =
        target(schemaPath())
            .queryParam("include", "deleted")
            .queryParam("parentSchema", "A")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), childResponse.getStatus());
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {child},
        childResponse.readEntity(DeletedEntityListResponse.class).getDeletedEntities());

    Response filteredResponse =
        target(schemaPath())
            .queryParam("include", "deleted")
            .queryParam("parentSchema", "A")
            .queryParam("name", "A:B")
            .queryParam("id", "102")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), filteredResponse.getStatus());
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {child},
        filteredResponse.readEntity(DeletedEntityListResponse.class).getDeletedEntities());

    Response itemResponse =
        target(schemaPath())
            .path("A:B")
            .queryParam("include", "deleted")
            .queryParam("id", "102")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), itemResponse.getStatus());
    Assertions.assertEquals('"' + childEtag + '"', itemResponse.getHeaderString("ETag"));
    Assertions.assertEquals(
        child, itemResponse.readEntity(DeletedEntityResponse.class).getDeletedEntity());
    verify(recoverableDeletionManager).listDeletedSchemas(schemaNamespace, null, null, null);
    verify(recoverableDeletionManager).listDeletedSchemas(schemaNamespace, "A", null, null);
    verify(recoverableDeletionManager).listDeletedSchemas(schemaNamespace, "A", "A:B", 102L);
    verifyNoInteractions(dispatcher);
  }

  @Test
  public void testRestoreDeletedHierarchicalSchemaAndReplay() {
    Namespace schemaNamespace = NamespaceUtil.ofSchema(metalake, catalog);
    String etag = deletionEtag("schema-child", 'e');
    SchemaEntity restored =
        SchemaEntity.builder()
            .withId(102L)
            .withName("A:B")
            .withNamespace(schemaNamespace)
            .withComment("restored metadata tree")
            .withProperties(ImmutableMap.of("owner", "control-plane"))
            .withAuditInfo(
                AuditInfo.builder()
                    .withCreator("alice")
                    .withCreateTime(Instant.ofEpochMilli(1_784_000_000_000L))
                    .build())
            .build();
    when(recoverableDeletionManager.restoreDeletedSchema(schemaNamespace, "A:B", 102L, etag))
        .thenReturn(restored);

    Response response = patchRestoreSchema("A:B", "102", '"' + etag + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    SchemaDTO schema = response.readEntity(SchemaResponse.class).getSchema();
    Assertions.assertEquals("A:B", schema.name());
    Assertions.assertEquals("restored metadata tree", schema.comment());
    Assertions.assertEquals(ImmutableMap.of("owner", "control-plane"), schema.properties());

    Response replay = patchRestoreSchema("A:B", "102", '"' + etag + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), replay.getStatus());
    Assertions.assertEquals("A:B", replay.readEntity(SchemaResponse.class).getSchema().name());
    verify(recoverableDeletionManager, times(2))
        .restoreDeletedSchema(schemaNamespace, "A:B", 102L, etag);
    verifyNoInteractions(dispatcher);
  }

  @Test
  public void testDeletedSchemaQueryAndRestoreValidation() {
    Response invalidInclude =
        target(schemaPath())
            .queryParam("include", "all")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), invalidInclude.getStatus());

    Response liveId =
        target(schemaPath())
            .queryParam("id", "102")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), liveId.getStatus());

    Response invalidDeletedId =
        target(schemaPath())
            .queryParam("include", "deleted")
            .queryParam("id", "not-a-number")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), invalidDeletedId.getStatus());

    Response missingItemId =
        target(schemaPath())
            .path("A:B")
            .queryParam("include", "deleted")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), missingItemId.getStatus());

    Response missingIfMatch = patchRestoreSchema("A:B", "102", null);
    Assertions.assertEquals(428, missingIfMatch.getStatus());
    Assertions.assertEquals(
        ErrorConstants.PRECONDITION_REQUIRED_CODE,
        missingIfMatch.readEntity(ErrorResponse.class).getCode());

    Response weakIfMatch = patchRestoreSchema("A:B", "102", "W/\"schema-etag\"");
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), weakIfMatch.getStatus());

    Response multipleIfMatch = patchRestoreSchema("A:B", "102", "\"schema-etag\", \"other-etag\"");
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), multipleIfMatch.getStatus());

    Response wrongMediaType =
        patchRestoreSchema("A:B", "102", "\"schema-etag\"", MediaType.APPLICATION_JSON_TYPE);
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), wrongMediaType.getStatus());

    Response invalidBody =
        target(schemaPath())
            .path("A:B")
            .queryParam("include", "deleted")
            .queryParam("id", "102")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"schema-etag\"")
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(true), "application/merge-patch+json"));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), invalidBody.getStatus());
    verifyNoInteractions(recoverableDeletionManager, dispatcher);
  }

  @Test
  public void testRestoreDeletedSchemaMapsRecoveryFailures() {
    doThrow(new TombstoneChangedException("changed"))
        .when(recoverableDeletionManager)
        .restoreDeletedSchema(any(), eq("A:B"), eq(102L), eq("stale"));
    doThrow(new TombstoneExpiredException("expired"))
        .when(recoverableDeletionManager)
        .restoreDeletedSchema(any(), eq("A:B"), eq(102L), eq("expired"));
    doThrow(new RecoveryConflictException(RecoveryConflictReason.NAME_OCCUPIED, "occupied"))
        .when(recoverableDeletionManager)
        .restoreDeletedSchema(any(), eq("A:B"), eq(102L), eq("name-occupied"));
    doThrow(
            new RecoveryConflictException(
                RecoveryConflictReason.NOT_LATEST_TOMBSTONE, "not latest"))
        .when(recoverableDeletionManager)
        .restoreDeletedSchema(any(), eq("A:B"), eq(102L), eq("not-latest"));

    Response stale = patchRestoreSchema("A:B", "102", "\"stale\"");
    Assertions.assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), stale.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_CHANGED_CODE, stale.readEntity(ErrorResponse.class).getCode());

    Response expired = patchRestoreSchema("A:B", "102", "\"expired\"");
    Assertions.assertEquals(Response.Status.GONE.getStatusCode(), expired.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_EXPIRED_CODE, expired.readEntity(ErrorResponse.class).getCode());

    assertRecoveryConflict("name-occupied", RecoveryConflictReason.NAME_OCCUPIED);
    assertRecoveryConflict("not-latest", RecoveryConflictReason.NOT_LATEST_TOMBSTONE);
    verifyNoInteractions(dispatcher);
  }

  @Test
  public void testExactDeletedSchemaReadRequiresMatchingPathAndId() {
    Namespace schemaNamespace = NamespaceUtil.ofSchema(metalake, catalog);
    doThrow(new TombstoneNotFoundException("schema tombstone not found"))
        .when(recoverableDeletionManager)
        .getDeletedSchema(schemaNamespace, "A:B", 102L);

    Response response =
        target(schemaPath())
            .path("A:B")
            .queryParam("include", "deleted")
            .queryParam("id", "102")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    Assertions.assertEquals(
        ErrorConstants.NOT_FOUND_CODE, response.readEntity(ErrorResponse.class).getCode());
    verifyNoInteractions(dispatcher);
  }

  @Test
  public void testCreateSchema() {
    SchemaCreateRequest req =
        new SchemaCreateRequest("schema1", "comment", ImmutableMap.of("key", "value"));
    Schema mockSchema = mockSchema("schema1", "comment", ImmutableMap.of("key", "value"));

    when(dispatcher.createSchema(any(), any(), any())).thenReturn(mockSchema);

    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(javax.ws.rs.client.Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaResponse schemaResp = resp.readEntity(SchemaResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());

    SchemaDTO schemaDTO = schemaResp.getSchema();
    Assertions.assertEquals("schema1", schemaDTO.name());
    Assertions.assertEquals("comment", schemaDTO.comment());
    Assertions.assertEquals(ImmutableMap.of("key", "value"), schemaDTO.properties());

    // Test throw NoSuchCatalogException
    doThrow(new NoSuchCatalogException("mock error"))
        .when(dispatcher)
        .createSchema(any(), any(), any());
    Response resp1 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(javax.ws.rs.client.Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp1.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp1.getMediaType());

    ErrorResponse errorResp = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchCatalogException.class.getSimpleName(), errorResp.getType());

    // Test throw SchemaAlreadyExistsException
    doThrow(new SchemaAlreadyExistsException("mock error"))
        .when(dispatcher)
        .createSchema(any(), any(), any());

    Response resp2 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(javax.ws.rs.client.Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), resp2.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp2.getMediaType());

    ErrorResponse errorResp2 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResp2.getCode());
    Assertions.assertEquals(
        SchemaAlreadyExistsException.class.getSimpleName(), errorResp2.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(dispatcher).createSchema(any(), any(), any());

    Response resp3 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(javax.ws.rs.client.Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp3.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp3.getMediaType());

    ErrorResponse errorResp3 = resp3.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp3.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp3.getType());
  }

  @Test
  public void testLoadSchema() {
    Schema mockSchema = mockSchema("schema1", "comment", ImmutableMap.of("key", "value"));
    when(dispatcher.loadSchema(any())).thenReturn(mockSchema);

    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaResponse schemaResp = resp.readEntity(SchemaResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());

    SchemaDTO schemaDTO = schemaResp.getSchema();
    Assertions.assertEquals("schema1", schemaDTO.name());
    Assertions.assertEquals("comment", schemaDTO.comment());
    Assertions.assertEquals(ImmutableMap.of("key", "value"), schemaDTO.properties());

    // Test throw NoSuchSchemaException
    doThrow(new NoSuchSchemaException("mock error")).when(dispatcher).loadSchema(any());
    Response resp1 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp1.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp1.getMediaType());

    ErrorResponse errorResp = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchSchemaException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(dispatcher).loadSchema(any());
    Response resp2 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp2.getMediaType());

    ErrorResponse errorResp2 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp2.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp2.getType());
  }

  @Test
  public void testAlterSchema() {
    SchemaUpdateRequest setReq = new SchemaUpdateRequest.SetSchemaPropertyRequest("key2", "value2");
    Schema updatedSchema =
        mockSchema("schema1", "comment", ImmutableMap.of("key", "value", "key2", "value2"));

    SchemaUpdateRequest removeReq = new SchemaUpdateRequest.RemoveSchemaPropertyRequest("key2");
    Schema removedSchema = mockSchema("schema1", "comment", ImmutableMap.of("key", "value"));

    // Test set property
    when(dispatcher.alterSchema(any(), eq(setReq.schemaChange()))).thenReturn(updatedSchema);
    SchemaUpdatesRequest req = new SchemaUpdatesRequest(ImmutableList.of(setReq));
    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(javax.ws.rs.client.Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaResponse schemaResp = resp.readEntity(SchemaResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());

    SchemaDTO schemaDTO = schemaResp.getSchema();
    Assertions.assertEquals("schema1", schemaDTO.name());
    Assertions.assertEquals("comment", schemaDTO.comment());
    Assertions.assertEquals(
        ImmutableMap.of("key", "value", "key2", "value2"), schemaDTO.properties());

    // Test remove property
    when(dispatcher.alterSchema(any(), eq(removeReq.schemaChange()))).thenReturn(removedSchema);
    SchemaUpdatesRequest req1 = new SchemaUpdatesRequest(ImmutableList.of(removeReq));
    Response resp1 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(javax.ws.rs.client.Entity.entity(req1, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp1.getMediaType());

    SchemaResponse schemaResp1 = resp1.readEntity(SchemaResponse.class);
    Assertions.assertEquals(0, schemaResp1.getCode());

    SchemaDTO schemaDTO1 = schemaResp1.getSchema();
    Assertions.assertEquals("schema1", schemaDTO1.name());
    Assertions.assertEquals("comment", schemaDTO1.comment());
    Assertions.assertEquals(ImmutableMap.of("key", "value"), schemaDTO1.properties());

    // Test throw NoSuchSchemaException
    doThrow(new NoSuchSchemaException("mock error")).when(dispatcher).alterSchema(any(), any());
    Response resp2 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(javax.ws.rs.client.Entity.entity(req1, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp2.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp2.getMediaType());

    ErrorResponse errorResp = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchSchemaException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(dispatcher).alterSchema(any(), any());
    Response resp3 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(javax.ws.rs.client.Entity.entity(req1, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp3.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp3.getMediaType());

    ErrorResponse errorResp3 = resp3.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp3.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp3.getType());
  }

  @Test
  public void testDropSchema() {
    when(dispatcher.dropSchema(any(), eq(false))).thenReturn(true);

    Response resp =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    DropResponse dropResp = resp.readEntity(DropResponse.class);
    Assertions.assertEquals(0, dropResp.getCode());
    Assertions.assertTrue(dropResp.dropped());

    // Test when failed to drop schema
    when(dispatcher.dropSchema(any(), eq(false))).thenReturn(false);

    Response resp1 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp1.getMediaType());

    DropResponse dropResp1 = resp1.readEntity(DropResponse.class);
    Assertions.assertEquals(0, dropResp1.getCode());
    Assertions.assertFalse(dropResp1.dropped());

    // Test specifying cascade to true
    boolean cascade = true;
    when(dispatcher.dropSchema(any(), eq(false))).thenReturn(true);
    doThrow(NonEmptySchemaException.class).when(dispatcher).dropSchema(any(), eq(true));

    Response resp2 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .queryParam("cascade", cascade)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), resp2.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp2.getMediaType());

    ErrorResponse errorResp = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NON_EMPTY_CODE, errorResp.getCode());
    Assertions.assertEquals(NonEmptySchemaException.class.getSimpleName(), errorResp.getType());

    // Test specifying cascade to false
    Response resp3 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .queryParam("cascade", !cascade)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp3.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp3.getMediaType());

    DropResponse dropResp2 = resp3.readEntity(DropResponse.class);
    Assertions.assertEquals(0, dropResp2.getCode());
    Assertions.assertTrue(dropResp2.dropped());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(dispatcher).dropSchema(any(), eq(false));
    Response resp4 =
        target("/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas/schema1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp4.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp4.getMediaType());

    ErrorResponse errorResp4 = resp4.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp4.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp4.getType());
  }

  private void assertRecoveryConflict(String etag, RecoveryConflictReason reason) {
    Response response = patchRestoreSchema("A:B", "102", '"' + etag + '"');
    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    ErrorResponse body = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.RECOVERY_CONFLICT_CODE, body.getCode());
    Assertions.assertEquals(reason, body.getReason());
  }

  private Response patchRestoreSchema(String schema, String id, String ifMatch) {
    return patchRestoreSchema(
        schema, id, ifMatch, MediaType.valueOf("application/merge-patch+json"));
  }

  private Response patchRestoreSchema(
      String schema, String id, String ifMatch, MediaType requestMediaType) {
    Invocation.Builder request =
        target(schemaPath())
            .path(schema)
            .queryParam("include", "deleted")
            .queryParam("id", id)
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json");
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    return request.method(
        "PATCH", Entity.entity(new EntityRestoreRequest(false), requestMediaType));
  }

  private DeletedEntityDTO deletedSchema(String name, String id, String deletionId, String etag) {
    return DeletedEntityDTO.builder()
        .withId(id)
        .withDeletionId(deletionId)
        .withName(name)
        .withType(RecoveryEntityType.SCHEMA)
        .withDeletedAt(1_784_800_000_000L)
        .withExpiresAt(1_785_404_800_000L)
        .withDeletedBy("alice")
        .withEtag(etag)
        .withLatestForName(true)
        .withRestorable(true)
        .build();
  }

  private static String deletionEtag(String deletionId, char digestCharacter) {
    return "deletion-"
        + deletionId
        + "-representation-"
        + String.valueOf(digestCharacter).repeat(64);
  }

  private String schemaPath() {
    return "/metalakes/" + metalake + "/catalogs/" + catalog + "/schemas";
  }

  private static Schema mockSchema(String name, String comment, Map<String, String> properties) {
    Schema mockSchema = mock(Schema.class);
    when(mockSchema.name()).thenReturn(name);
    when(mockSchema.comment()).thenReturn(comment);
    when(mockSchema.properties()).thenReturn(properties);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockSchema.auditInfo()).thenReturn(mockAudit);

    return mockSchema;
  }

  @Test
  public void testSchemaUpdatesRequestWithUpdatesNull() {
    SchemaUpdatesRequest schemaUpdatesRequest = new SchemaUpdatesRequest(null);

    Throwable exception =
        Assertions.assertThrows(IllegalArgumentException.class, schemaUpdatesRequest::validate);
    Assertions.assertEquals("updates must not be null", exception.getMessage());
  }
}
