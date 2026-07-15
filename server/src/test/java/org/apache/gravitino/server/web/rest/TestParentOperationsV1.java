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
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogProvider;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.CatalogOperationsV1;
import org.apache.gravitino.server.web.rest.v1.MetalakeOperationsV1;
import org.apache.gravitino.server.web.rest.v1.SchemaOperationsV1;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorResponseFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1MediaTypeFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicExceptionMapper;
import org.apache.gravitino.server.web.rest.v1.validation.V1JsonBodyFilter;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** End-to-end Jersey tests for the route-versioned V1 parent-resource CRUD slice. */
public class TestParentOperationsV1 extends BaseOperationsTest {

  private final MetalakeDispatcher metalakeDispatcher = mock(MetalakeDispatcher.class);
  private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
  private final SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);

  @BeforeAll
  public static void setupLockManager() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    Mockito.doReturn(false).when(config).get(CACHE_ENABLED);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @BeforeEach
  public void resetDispatchers() {
    reset(metalakeDispatcher, catalogDispatcher, schemaDispatcher);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(3001, 4000)));
    } catch (IOException exception) {
      throw new RuntimeException(exception);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(MetalakeOperationsV1.class);
    resourceConfig.register(CatalogOperationsV1.class);
    resourceConfig.register(SchemaOperationsV1.class);
    resourceConfig.register(V1PublicExceptionMapper.class);
    resourceConfig.register(V1ErrorResponseFilter.class);
    resourceConfig.register(V1MediaTypeFilter.class);
    resourceConfig.register(V1JsonBodyFilter.class);
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(JacksonFeature.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(metalakeDispatcher).to(MetalakeDispatcher.class).ranked(1);
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(1);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(1);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testMetalakeCreateGetAndConditionalUpdateUseStrongEntityTags() {
    Metalake created = metalake("demo", "created", Map.of("one", "1"));
    Metalake updated = metalake("demo", "updated", Map.of("two", "2"));
    when(metalakeDispatcher.createMetalake(any(), any(), any())).thenReturn(created);
    when(metalakeDispatcher.loadMetalake(any())).thenReturn(created);
    when(metalakeDispatcher.alterMetalake(any(), any())).thenReturn(updated);

    Response createResponse =
        target("v1/metalakes")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(
                Entity.json(
                    "{\"name\":\"demo\",\"comment\":\"created\",\"properties\":{\"one\":\"1\"}}"));

    assertEquals(201, createResponse.getStatus());
    assertEquals("no-store", createResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    String createTag = createResponse.getHeaderString(HttpHeaders.ETAG);
    assertStrongEntityTag(createTag);
    assertTrue(createResponse.getLocation().getPath().endsWith("/v1/metalakes/demo"));

    Response getResponse = metalakeTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    assertEquals(200, getResponse.getStatus());
    assertEquals("no-store", getResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    String getTag = getResponse.getHeaderString(HttpHeaders.ETAG);
    assertStrongEntityTag(getTag);
    assertTrue(
        getResponse.readEntity(String.class).contains("\"resourceName\":\"metalakes/demo\""));

    Response updateResponse =
        metalakeTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, getTag)
            .put(Entity.json("{\"comment\":\"updated\",\"properties\":{\"two\":\"2\"}}"));

    assertEquals(200, updateResponse.getStatus());
    assertEquals("no-store", updateResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertStrongEntityTag(updateResponse.getHeaderString(HttpHeaders.ETAG));
    verify(metalakeDispatcher).alterMetalake(any(), any());
  }

  @Test
  public void testCatalogCreateEmitsAProviderAndStrongEntityTag() {
    Catalog catalog = catalog("lakehouse", "hive", "comment", Map.of("uri", "thrift://hms"));
    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any())).thenReturn(catalog);

    Response response =
        catalogCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(
                Entity.json(
                    "{\"name\":\"lakehouse\",\"type\":\"RELATIONAL\",\"provider\":\"hive\",\"comment\":\"comment\",\"properties\":{\"uri\":\"thrift://hms\"}}"));

    assertEquals(201, response.getStatus());
    assertEquals("no-store", response.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertStrongEntityTag(response.getHeaderString(HttpHeaders.ETAG));
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"provider\":\"hive\""));
    assertTrue(response.getLocation().getPath().endsWith("/v1/metalakes/demo/catalogs/lakehouse"));
  }

  @Test
  public void testManagedCatalogCreateMapsAnOmittedProviderToItsEffectiveProvider() {
    Catalog catalog = mock(Catalog.class);
    when(catalog.name()).thenReturn("managed_files");
    when(catalog.type()).thenReturn(Catalog.Type.FILESET);
    when(catalog.provider())
        .thenReturn(CatalogProvider.shortNameForManagedCatalog(Catalog.Type.FILESET));
    when(catalog.comment()).thenReturn(null);
    when(catalog.properties()).thenReturn(Map.of());
    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any())).thenReturn(catalog);

    Response response =
        catalogCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(
                Entity.json("{\"name\":\"managed_files\",\"type\":\"FILESET\",\"properties\":{}}"));

    assertEquals(201, response.getStatus());
    verify(catalogDispatcher)
        .createCatalog(
            any(),
            eq(Catalog.Type.FILESET),
            eq(CatalogProvider.shortNameForManagedCatalog(Catalog.Type.FILESET)),
            any(),
            any());
  }

  @Test
  public void testStrictBodyFilterRejectsUnknownParentCreateProperties() {
    Response response =
        target("v1/metalakes")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json("{\"name\":\"demo\",\"properties\":{},\"unknown\":true}"));

    assertStrictBodyFailure(response);
    verify(metalakeDispatcher, never()).createMetalake(any(), any(), any());
  }

  @Test
  public void testStrictBodyFilterRejectsCaseInsensitiveParentEnums() {
    Response response =
        catalogCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(
                Entity.json(
                    "{\"name\":\"lakehouse\",\"type\":\"relational\","
                        + "\"provider\":\"hive\",\"properties\":{}}"));

    assertStrictBodyFailure(response);
    verify(catalogDispatcher, never()).createCatalog(any(), any(), any(), any(), any());
  }

  @Test
  public void testStrictBodyFilterRejectsDuplicateParentUpdateProperties() {
    Response response =
        metalakeTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .put(Entity.json("{\"comment\":\"first\",\"comment\":\"second\",\"properties\":{}}"));

    assertStrictBodyFailure(response);
    verify(metalakeDispatcher, never()).loadMetalake(any());
  }

  @Test
  public void testCollectionEndpointsReturnCompletePublicResourceEnvelopes() {
    Metalake metalake = metalake("demo", "comment", Map.of("key", "value"));
    Catalog catalog = catalog("lakehouse", "hive", "comment", Map.of("key", "value"));
    Schema schema = schema("sales", "comment", Map.of("key", "value"));
    when(metalakeDispatcher.listMetalakes()).thenReturn(new Metalake[] {metalake});
    when(catalogDispatcher.listCatalogsInfo(any())).thenReturn(new Catalog[] {catalog});
    when(schemaDispatcher.listSchemas(any()))
        .thenReturn(
            new NameIdentifier[] {NameIdentifierUtil.ofSchema("demo", "lakehouse", "sales")});
    when(schemaDispatcher.loadSchema(any())).thenReturn(schema);

    Response metalakeResponse =
        target("v1/metalakes").request(MediaType.APPLICATION_JSON_TYPE).get();
    Response catalogResponse =
        catalogCollectionTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    Response schemaResponse =
        target("v1/metalakes/demo/catalogs/lakehouse/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .get();

    assertEquals(200, metalakeResponse.getStatus());
    assertEquals("no-store", metalakeResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertTrue(metalakeResponse.readEntity(String.class).contains("\"metalakes\""));
    assertEquals(200, catalogResponse.getStatus());
    assertEquals("no-store", catalogResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertTrue(catalogResponse.readEntity(String.class).contains("\"catalogs\""));
    assertEquals(200, schemaResponse.getStatus());
    assertEquals("no-store", schemaResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertTrue(schemaResponse.readEntity(String.class).contains("\"schemas\""));
  }

  @Test
  public void testSchemaCommentMutationIsExplicitlyUnsupported() {
    Schema schema = schema("sales", "current", Map.of("key", "value"));
    when(schemaDispatcher.loadSchema(any())).thenReturn(schema);

    Response getResponse = schemaTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    assertEquals(200, getResponse.getStatus());
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);

    Response updateResponse =
        schemaTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, entityTag)
            .put(Entity.json("{\"comment\":\"changed\",\"properties\":{\"key\":\"value\"}}"));

    assertEquals(501, updateResponse.getStatus());
    assertTrue(
        updateResponse.readEntity(String.class).contains("\"type\":\"UNSUPPORTED_OPERATION\""));
    verify(schemaDispatcher, never()).alterSchema(any(), any());
  }

  @Test
  public void testConditionalDeleteRejectsStaleTagWithoutDispatchingMutation() {
    Metalake current = metalake("demo", "current", Map.of());
    when(metalakeDispatcher.loadMetalake(any())).thenReturn(current);

    Response response =
        metalakeTarget()
            .queryParam("force", "false")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "\"stale\"")
            .delete();

    assertEquals(412, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"PRECONDITION_FAILED\""));
    assertFalse(body.contains("stale"));
    verify(metalakeDispatcher, never()).dropMetalake(any(), anyBoolean());
  }

  @Test
  public void testMetalakeDeleteDoesNotStoreTheSuccessfulResponse() {
    Metalake current = metalake("demo", "current", Map.of());
    when(metalakeDispatcher.loadMetalake(any())).thenReturn(current);
    when(metalakeDispatcher.dropMetalake(any(), anyBoolean())).thenReturn(true);

    Response getResponse = metalakeTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);

    Response deleteResponse =
        metalakeTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, entityTag)
            .delete();

    assertEquals(204, deleteResponse.getStatus());
    assertEquals("no-store", deleteResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertFalse(deleteResponse.hasEntity());
  }

  private WebTarget metalakeTarget() {
    return target("v1/metalakes/demo");
  }

  private WebTarget catalogCollectionTarget() {
    return target("v1/metalakes/demo/catalogs");
  }

  private WebTarget schemaTarget() {
    return target("v1/metalakes/demo/catalogs/lakehouse/schemas/sales");
  }

  private static void assertStrongEntityTag(String value) {
    assertNotNull(value);
    assertTrue(value.startsWith("\""));
    assertFalse(value.startsWith("W/"));
  }

  private static void assertStrictBodyFailure(Response response) {
    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"body\""));
  }

  private static Metalake metalake(String name, String comment, Map<String, String> properties) {
    Metalake metalake = mock(Metalake.class);
    when(metalake.name()).thenReturn(name);
    when(metalake.comment()).thenReturn(comment);
    when(metalake.properties()).thenReturn(properties);
    return metalake;
  }

  private static Catalog catalog(
      String name, String provider, String comment, Map<String, String> properties) {
    Catalog catalog = mock(Catalog.class);
    when(catalog.name()).thenReturn(name);
    when(catalog.type()).thenReturn(Catalog.Type.RELATIONAL);
    when(catalog.provider()).thenReturn(provider);
    when(catalog.comment()).thenReturn(comment);
    when(catalog.properties()).thenReturn(properties);
    return catalog;
  }

  private static Schema schema(String name, String comment, Map<String, String> properties) {
    Schema schema = mock(Schema.class);
    when(schema.name()).thenReturn(name);
    when(schema.comment()).thenReturn(comment);
    when(schema.properties()).thenReturn(properties);
    return schema;
  }

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      return mock(HttpServletRequest.class);
    }
  }
}
