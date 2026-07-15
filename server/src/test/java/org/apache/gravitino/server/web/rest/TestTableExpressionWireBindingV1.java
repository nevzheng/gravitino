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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.expressions.NamedReference;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.TableOperationsV1;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorResponseFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1MediaTypeFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicExceptionMapper;
import org.apache.gravitino.server.web.rest.v1.validation.V1JsonBodyFilter;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** End-to-end binding coverage for V1 table value expressions. */
public class TestTableExpressionWireBindingV1 extends BaseOperationsTest {

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
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(4101, 4200)));
    } catch (IOException exception) {
      throw new RuntimeException(exception);
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
  public void testCreateBindsColumnDefaultsAndPhysicalExpressions() {
    Table table = canonicalTable();
    when(dispatcher.loadTable(any())).thenReturn(table);

    Response response =
        target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .post(Entity.json(createTableWithExpressionsPayload()));

    String responseBody = response.readEntity(String.class);
    assertEquals(201, response.getStatus(), responseBody);
    assertTrue(responseBody.contains("\"name\":\"expression_orders\""));

    ArgumentCaptor<Column[]> columns = ArgumentCaptor.forClass(Column[].class);
    ArgumentCaptor<Distribution> distribution = ArgumentCaptor.forClass(Distribution.class);
    ArgumentCaptor<SortOrder[]> sortOrders = ArgumentCaptor.forClass(SortOrder[].class);
    Mockito.verify(dispatcher)
        .createTable(
            any(),
            columns.capture(),
            any(),
            any(),
            any(),
            distribution.capture(),
            sortOrders.capture(),
            any());

    assertEquals(2, columns.getValue().length);
    assertTrue(columns.getValue()[0].defaultValue() instanceof Literal);
    assertNull(((Literal<?>) columns.getValue()[0].defaultValue()).value());
    assertTrue(columns.getValue()[1].defaultValue() instanceof Literal);
    assertEquals(
        3L, ((Number) ((Literal<?>) columns.getValue()[1].defaultValue()).value()).longValue());

    assertEquals(1, distribution.getValue().expressions().length);
    assertTrue(distribution.getValue().expressions()[0] instanceof NamedReference);
    assertArrayEquals(
        new String[] {"id"},
        ((NamedReference) distribution.getValue().expressions()[0]).fieldName());

    assertEquals(1, sortOrders.getValue().length);
    assertTrue(sortOrders.getValue()[0].expression() instanceof NamedReference);
    assertArrayEquals(
        new String[] {"priority"},
        ((NamedReference) sortOrders.getValue()[0].expression()).fieldName());
  }

  private static String createTableWithExpressionsPayload() {
    return "{"
        + "\"name\":\"expression_orders\","
        + "\"columns\":["
        + "{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
        + "\"nullable\":true,\"autoIncrement\":false,\"defaultValue\":null},"
        + "{\"name\":\"priority\",\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
        + "\"nullable\":false,\"autoIncrement\":false,\"defaultValue\":{\"type\":\"literal\","
        + "\"value\":3,\"data-type\":{\"kind\":\"INTEGER\",\"signed\":true}}}],"
        + "\"mysqlOptions\":{\"engine\":\"InnoDB\"},"
        + "\"partitioning\":[],"
        + "\"distribution\":{\"strategy\":\"HASH\",\"expressions\":[{\"type\":\"reference\","
        + "\"name\":\"id\"}]},"
        + "\"sortOrders\":[{\"expression\":{\"type\":\"reference\",\"name\":\"priority\"},"
        + "\"direction\":\"ASC\",\"nullOrdering\":\"NULLS_LAST\"}],"
        + "\"indexes\":[]"
        + "}";
  }

  private static Table canonicalTable() {
    Table table = mock(Table.class);
    when(table.name()).thenReturn("expression_orders");
    when(table.columns()).thenReturn(new Column[0]);
    when(table.properties()).thenReturn(Map.of("engine", "InnoDB"));
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
