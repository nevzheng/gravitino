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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.TableOperationsV1;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorResponseFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1MediaTypeFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicExceptionMapper;
import org.apache.gravitino.server.web.rest.v1.validation.V1JsonBodyFilter;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** End-to-end Jersey tests for the route-versioned V1 table REST slice. */
public class TestTableOperationsV1 extends BaseOperationsTest {

  private final TableDispatcher dispatcher = mock(TableDispatcher.class);
  private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
  private final Catalog catalogInfo = mock(Catalog.class);

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
  public void setupCatalogProvider() {
    when(catalogDispatcher.loadCatalog(any())).thenReturn(catalogInfo);
    when(catalogInfo.provider()).thenReturn("jdbc-mysql");
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(3001, 4000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(TableOperationsV1.class);
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
            bind(dispatcher).to(TableDispatcher.class).ranked(1);
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(1);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testGetHeadAndConditionalGetUseThePublicV1Representation() {
    Table table = mock(Table.class);
    when(table.name()).thenReturn("orders");
    when(dispatcher.loadTable(any())).thenReturn(table);

    Response getResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).get();

    assertEquals(200, getResponse.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, getResponse.getMediaType());
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);
    assertNotNull(entityTag);
    assertTrue(entityTag.startsWith("\""));
    assertFalse(entityTag.startsWith("W/\""));
    assertEquals("private, no-cache", getResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertEquals(
        "Authorization, Accept, Accept-Encoding", getResponse.getHeaderString(HttpHeaders.VARY));
    assertTrue(getResponse.readEntity(String.class).contains("\"resourceName\""));

    Response conditionalResponse =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_NONE_MATCH, entityTag)
            .get();
    assertEquals(304, conditionalResponse.getStatus());
    assertFalse(conditionalResponse.hasEntity());
    assertEquals(entityTag, conditionalResponse.getHeaderString(HttpHeaders.ETAG));

    Response headResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).head();
    assertEquals(200, headResponse.getStatus());
    assertFalse(headResponse.hasEntity());
    assertEquals(entityTag, headResponse.getHeaderString(HttpHeaders.ETAG));
  }

  @Test
  public void testCollectionGetAndPostCreateUsePublicV1WireObjects() {
    when(dispatcher.listTables(any()))
        .thenReturn(
            new NameIdentifier[] {NameIdentifier.of("demo", "catalog", "schema", "orders")});

    Response listResponse = tableCollectionTarget().request(MediaType.APPLICATION_JSON_TYPE).get();

    assertEquals(200, listResponse.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, listResponse.getMediaType());
    assertEquals("no-store", listResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    String listBody = listResponse.readEntity(String.class);
    assertTrue(listBody.contains("\"tables\""));
    assertTrue(listBody.contains("\"name\":\"orders\""));
    assertTrue(
        listBody.contains(
            "\"resourceName\":\"metalakes/demo/catalogs/catalog/schemas/schema/tables/orders\""));

    Table createReturn =
        mockV1Table("created_orders", "Transient create result", Map.of("legacy", "value"));
    Table canonicalTable =
        mockV1Table(
            "created_orders",
            "Created through V1",
            Map.of("engine", "InnoDB", "auto-increment-offset", "1"));
    when(dispatcher.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(createReturn);
    when(dispatcher.loadTable(any())).thenReturn(canonicalTable);

    Response createResponse =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(createTablePayload()));

    assertEquals(201, createResponse.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, createResponse.getMediaType());
    assertEquals("no-store", createResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertNotNull(createResponse.getHeaderString(HttpHeaders.LOCATION));
    assertTrue(
        createResponse
            .getHeaderString(HttpHeaders.LOCATION)
            .endsWith("/v1/metalakes/demo/catalogs/catalog/schemas/schema/tables/created_orders"));
    String createBody = createResponse.readEntity(String.class);
    assertTrue(createBody.contains("\"name\":\"created_orders\""));
    assertTrue(createBody.contains("\"comment\":\"Created through V1\""));
    assertTrue(createBody.contains("\"kind\":\"INTEGER\""));
    assertTrue(createBody.contains("\"mysqlOptions\""));
    assertTrue(createBody.contains("\"autoIncrementOffset\":1"));
    Mockito.verify(dispatcher).loadTable(any());
  }

  @Test
  public void testPutRequiresStrongIfMatchAndReplacesCompleteDesiredState() {
    Table currentTable = mockV1Table("orders", "Original", Map.of("engine", "InnoDB"));
    Table updatedTable = mockV1Table("orders", "Replaced", Map.of("engine", "InnoDB"));
    Table alterReturn = mockV1Table("orders", "Transient alter result", Map.of("legacy", "value"));
    AtomicBoolean altered = new AtomicBoolean();
    when(dispatcher.loadTable(any()))
        .thenAnswer(ignored -> altered.get() ? updatedTable : currentTable);
    when(dispatcher.alterTable(any(), any(TableChange[].class)))
        .thenAnswer(
            ignored -> {
              altered.set(true);
              return alterReturn;
            });

    Response getResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    assertEquals(200, getResponse.getStatus());
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);
    assertStrongEntityTag(entityTag);

    Response missingPrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .put(Entity.json(completeTableUpdatePayload()));
    assertPreconditionFailed(missingPrecondition);

    Response weakPrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "W/\"weak\"")
            .put(Entity.json(completeTableUpdatePayload()));
    assertPreconditionFailed(weakPrecondition);

    Response wildcardPrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "*")
            .put(Entity.json(completeTableUpdatePayload()));
    assertPreconditionFailed(wildcardPrecondition);

    Response partialReplacement =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, entityTag)
            .put(Entity.json("{\"comment\":\"partial\"}"));
    assertEquals(400, partialReplacement.getStatus());
    assertTrue(
        partialReplacement.readEntity(String.class).contains("\"type\":\"INVALID_ARGUMENT\""));

    Response stalePrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "\"stale\"")
            .put(Entity.json(completeTableUpdatePayload()));
    assertEquals(412, stalePrecondition.getStatus());
    assertTrue(
        stalePrecondition.readEntity(String.class).contains("\"type\":\"PRECONDITION_FAILED\""));

    Response updateResponse =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, entityTag)
            .put(Entity.json(completeTableUpdatePayload()));
    String updateBody = updateResponse.readEntity(String.class);
    assertEquals(200, updateResponse.getStatus(), updateBody);
    assertEquals("private, no-cache", updateResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    String updatedEntityTag = updateResponse.getHeaderString(HttpHeaders.ETAG);
    assertStrongEntityTag(updatedEntityTag);
    assertTrue(updateBody.contains("\"comment\":\"Replaced\""));
    assertTrue(updateBody.contains("\"mysqlOptions\""));
    assertTrue(updateBody.contains("\"engine\":\"InnoDB\""));
    assertTrue(updateBody.contains("\"kind\":\"INTEGER\""));

    Response immediatelyLoaded = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    assertEquals(200, immediatelyLoaded.getStatus());
    assertEquals(updatedEntityTag, immediatelyLoaded.getHeaderString(HttpHeaders.ETAG));
    assertTrue(immediatelyLoaded.readEntity(String.class).contains("\"comment\":\"Replaced\""));
  }

  @Test
  public void testDeleteRequiresStrongCurrentIfMatch() {
    Table table = mockV1Table("orders", "Delete me", Map.of());
    when(dispatcher.loadTable(any())).thenReturn(table);
    when(dispatcher.dropTable(any())).thenReturn(true);

    Response getResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).get();
    assertEquals(200, getResponse.getStatus());
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);
    assertStrongEntityTag(entityTag);

    Response missingPrecondition = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).delete();
    assertPreconditionFailed(missingPrecondition);

    Response weakPrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "W/\"weak\"")
            .delete();
    assertPreconditionFailed(weakPrecondition);

    Response wildcardPrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "*")
            .delete();
    assertPreconditionFailed(wildcardPrecondition);

    Response stalePrecondition =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, "\"stale\"")
            .delete();
    assertEquals(412, stalePrecondition.getStatus());
    assertTrue(
        stalePrecondition.readEntity(String.class).contains("\"type\":\"PRECONDITION_FAILED\""));

    Response deleteResponse =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_MATCH, entityTag)
            .delete();
    assertEquals(204, deleteResponse.getStatus());
    assertEquals("no-store", deleteResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertFalse(deleteResponse.hasEntity());
  }

  @Test
  public void testPatchIsNotPartOfTheV1TableMutationContract() {
    Response response =
        tableTarget()
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .method("PATCH", Entity.json(completeTableUpdatePayload()));

    assertEquals(405, response.getStatus());
    Set<String> allowedMethods =
        Arrays.stream(response.getHeaderString(HttpHeaders.ALLOW).split(","))
            .map(String::trim)
            .collect(Collectors.toSet());
    assertEquals(Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE"), allowedMethods);
    assertFalse(allowedMethods.contains("PATCH"));
    assertTrue(response.readEntity(String.class).contains("\"type\":\"METHOD_NOT_ALLOWED\""));
  }

  @Test
  public void testStrictBodyFilterRejectsTrailingValuesOnTableCreate() {
    Response response =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(createTablePayload() + "{}"));

    assertStrictBodyFailure(response);
    Mockito.verify(dispatcher, Mockito.never())
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  public void testCreateRejectsTheLegacyGenericPropertyBag() {
    Response response =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(
                Entity.json(
                    createTablePayload()
                        .replace(
                            "\"mysqlOptions\":{\"engine\":\"InnoDB\",\"autoIncrementOffset\":1}",
                            "\"properties\":{\"owner\":\"qa\"}")));

    assertStrictBodyFailure(response);
    Mockito.verify(dispatcher, Mockito.never())
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  public void testCreateRejectsUnsupportedDeltaLayoutWithPublicInputError() {
    when(catalogInfo.provider()).thenReturn("lakehouse-generic");

    Response response =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(deltaTableWithYearPartitionPayload()));

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"field\":\"partitioning[0]\""));
    Mockito.verify(dispatcher, Mockito.never())
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  public void testCreateRejectsAmbiguousHiveStorageAndDescriptorWithPublicInputError() {
    when(catalogInfo.provider()).thenReturn("hive");

    Response response =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(hiveTableWithFileFormatAndDescriptorPayload()));

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"field\":\"hiveOptions\""));
    Mockito.verify(dispatcher, Mockito.never())
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  public void testCreateRejectsPaimonBeforeAResponseWouldLoseTypedState() {
    when(catalogInfo.provider()).thenReturn("lakehouse-paimon");

    Response response =
        tableCollectionTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(createTablePayload()));

    assertEquals(501, response.getStatus());
    assertTrue(response.readEntity(String.class).contains("\"type\":\"UNSUPPORTED_OPERATION\""));
    Mockito.verify(dispatcher, Mockito.never())
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  public void testStrictBodyFilterRejectsScalarCoercionOnTableUpdate() {
    Response response =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .put(
                Entity.json(
                    completeTableUpdatePayload()
                        .replace("\"comment\":\"Replaced\"", "\"comment\":42")));

    assertStrictBodyFailure(response);
    Mockito.verify(dispatcher, Mockito.never()).loadTable(any());
  }

  @Test
  public void testRouteRejectsUnexpectedQueryParametersWithPublicError() {
    Response response = tableTarget().queryParam("privileges", "MODIFY_TABLE").request().get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"query\""));
    assertFalse(body.contains("\"details\":[]"));
  }

  @Test
  public void testRouteRejectsNonconformingPathSegmentsWithFieldViolation() {
    Response response =
        target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables")
            .path("a".repeat(256))
            .request()
            .get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"table\""));
  }

  @Test
  public void testRouteRejectsInvalidIfNoneMatchWithFieldViolation() {
    Response response =
        tableTarget().request().header(HttpHeaders.IF_NONE_MATCH, "not-an-entity-tag").get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"If-None-Match\""));
  }

  @Test
  public void testRouteTranslatesMissingTableWithoutLeakingInternalMessage() {
    doThrow(new NoSuchTableException("internal catalog topology"))
        .when(dispatcher)
        .loadTable(any());

    Response response = tableTarget().request().get();

    assertEquals(404, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"TABLE_NOT_FOUND\""));
    assertTrue(body.contains("\"resourceType\":\"TABLE\""));
    assertFalse(body.contains("topology"));

    Response headResponse = tableTarget().request().head();
    assertEquals(404, headResponse.getStatus());
    assertFalse(headResponse.hasEntity());
  }

  @Test
  public void testV1RouteRejectsLegacyVendorMediaNegotiation() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/vnd.gravitino.v1+json")
            .get();

    assertEquals(406, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"NOT_ACCEPTABLE\""));
  }

  @Test
  public void testV1RouteRejectsMixedLegacyAndJsonMediaNegotiation() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/json, application/vnd.gravitino.v1+json")
            .get();

    assertEquals(406, response.getStatus());
  }

  @Test
  public void testV1HeadMediaNegotiationFailureDoesNotWriteAnErrorBody() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/vnd.gravitino.v1+json")
            .head();

    assertEquals(406, response.getStatus());
    assertFalse(response.hasEntity());
  }

  @Test
  public void testFrameworkNegotiationFailureUsesThePublicErrorEnvelope() {
    Response response = tableTarget().request(MediaType.APPLICATION_XML_TYPE).get();

    assertEquals(406, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"NOT_ACCEPTABLE\""));
    assertTrue(body.contains("\"details\":[]"));
  }

  @Test
  public void testMethodNotAllowedUsesThePublicErrorAndAllowHeader() {
    Response response = tableTarget().request().post(Entity.json("{}"));

    assertEquals(405, response.getStatus());
    Set<String> allowedMethods =
        Arrays.stream(response.getHeaderString(HttpHeaders.ALLOW).split(","))
            .map(String::trim)
            .collect(Collectors.toSet());
    assertEquals(Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE"), allowedMethods);
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"METHOD_NOT_ALLOWED\""));
    assertTrue(body.contains("\"details\":[]"));
  }

  private WebTarget tableTarget() {
    return target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables/orders");
  }

  private WebTarget tableCollectionTarget() {
    return target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables");
  }

  private static void assertStrongEntityTag(String entityTag) {
    assertNotNull(entityTag);
    assertTrue(entityTag.startsWith("\""));
    assertTrue(entityTag.endsWith("\""));
    assertFalse(entityTag.startsWith("W/\""));
  }

  private static void assertPreconditionFailed(Response response) {
    assertEquals(412, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"PRECONDITION_FAILED\""));
  }

  private static void assertStrictBodyFailure(Response response) {
    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"body\""));
  }

  private static String createTablePayload() {
    return "{"
        + "\"name\":\"created_orders\","
        + "\"comment\":\"Created through V1\","
        + "\"columns\":[{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\","
        + "\"signed\":true},\"nullable\":false,\"autoIncrement\":false}],"
        + "\"mysqlOptions\":{\"engine\":\"InnoDB\",\"autoIncrementOffset\":1},"
        + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]"
        + "}";
  }

  private static String completeTableUpdatePayload() {
    return "{"
        + "\"comment\":\"Replaced\","
        + "\"columns\":[{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\","
        + "\"signed\":true},\"nullable\":false,\"autoIncrement\":false}],"
        + "\"mysqlOptions\":{\"engine\":\"InnoDB\"},"
        + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]"
        + "}";
  }

  private static String deltaTableWithYearPartitionPayload() {
    return "{"
        + "\"name\":\"delta_orders\","
        + "\"columns\":[{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\","
        + "\"signed\":true},\"nullable\":false,\"autoIncrement\":false}],"
        + "\"storage\":{\"ownership\":\"EXTERNAL\",\"tableFormat\":\"DELTA\","
        + "\"location\":\"s3://warehouse/delta-orders\"},"
        + "\"partitioning\":[{\"kind\":\"YEAR\",\"fieldName\":[\"id\"]}],"
        + "\"sortOrders\":[],\"indexes\":[]"
        + "}";
  }

  private static String hiveTableWithFileFormatAndDescriptorPayload() {
    return "{"
        + "\"name\":\"hive_orders\","
        + "\"columns\":[{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\","
        + "\"signed\":true},\"nullable\":false,\"autoIncrement\":false}],"
        + "\"storage\":{\"ownership\":\"EXTERNAL\",\"tableFormat\":\"HIVE\","
        + "\"location\":\"s3://warehouse/hive-orders\",\"fileFormat\":\"ORC\"},"
        + "\"hiveOptions\":{\"serdeLibrary\":\"org.apache.hadoop.hive.ql.io.orc.OrcSerde\"},"
        + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]"
        + "}";
  }

  private static Table mockV1Table(String name, String comment, Map<String, String> properties) {
    Table table = mock(Table.class);
    when(table.name()).thenReturn(name);
    when(table.comment()).thenReturn(comment);
    when(table.columns())
        .thenReturn(
            new Column[] {Column.of("id", Types.IntegerType.get(), null, false, false, null)});
    when(table.properties()).thenReturn(properties);
    when(table.partitioning()).thenReturn(new Transform[0]);
    when(table.sortOrder()).thenReturn(new SortOrder[0]);
    when(table.index()).thenReturn(new Index[0]);
    return table;
  }

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      return mock(HttpServletRequest.class);
    }
  }
}
