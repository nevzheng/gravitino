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
package org.apache.gravitino.rest.v1.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestV1ParentResourceModels {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testResourcesSerializeAndIgnoreFutureResponseFields() throws Exception {
    MetalakeResource metalake =
        objectMapper.readValue(
            "{\"resourceName\":\"metalakes/demo\",\"name\":\"demo\","
                + "\"properties\":{\"owner\":\"data\"},\"future\":true}",
            MetalakeResource.class);
    CatalogResource catalog =
        objectMapper.readValue(
            "{\"resourceName\":\"metalakes/demo/catalogs/lakehouse\",\"name\":\"lakehouse\","
                + "\"type\":\"RELATIONAL\",\"provider\":\"iceberg\",\"properties\":{},"
                + "\"future\":true}",
            CatalogResource.class);
    SchemaResource schema =
        objectMapper.readValue(
            "{\"resourceName\":\"metalakes/demo/catalogs/lakehouse/schemas/sales\","
                + "\"name\":\"sales\",\"properties\":{},\"future\":true}",
            SchemaResource.class);

    assertEquals("demo", metalake.getName());
    assertEquals("data", metalake.getProperties().get("owner"));
    assertEquals(CatalogType.RELATIONAL, catalog.getType());
    assertEquals("iceberg", catalog.getProvider());
    assertEquals("sales", schema.getName());
  }

  @Test
  public void testResourcesDefensivelyCopyProperties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("owner", "data");
    SchemaResource resource =
        new SchemaResource(
            "metalakes/demo/catalogs/lakehouse/schemas/sales", "sales", null, properties, null);

    properties.clear();
    assertEquals("data", resource.getProperties().get("owner"));
    assertThrows(
        UnsupportedOperationException.class, () -> resource.getProperties().put("key", "value"));
  }

  @Test
  public void testListResponsesHaveStableNamedCollections() throws Exception {
    MetalakeResource metalake =
        new MetalakeResource("metalakes/demo", "demo", null, Collections.emptyMap(), null);
    CatalogResource catalog =
        new CatalogResource(
            "metalakes/demo/catalogs/lakehouse",
            "lakehouse",
            CatalogType.RELATIONAL,
            "iceberg",
            null,
            Collections.emptyMap(),
            null);
    SchemaResource schema =
        new SchemaResource(
            "metalakes/demo/catalogs/lakehouse/schemas/sales",
            "sales",
            null,
            Collections.emptyMap(),
            null);
    List<TableReference> tables =
        new ArrayList<>(
            Collections.singletonList(
                new TableReference(
                    "metalakes/demo/catalogs/lakehouse/schemas/sales/tables/orders", "orders")));

    MetalakeListResponse metalakes = new MetalakeListResponse(Collections.singletonList(metalake));
    CatalogListResponse catalogs = new CatalogListResponse(Collections.singletonList(catalog));
    SchemaListResponse schemas = new SchemaListResponse(Collections.singletonList(schema));
    TableListResponse tableList = new TableListResponse(tables);
    tables.clear();

    assertEquals("demo", metalakes.getMetalakes().get(0).getName());
    assertEquals("lakehouse", catalogs.getCatalogs().get(0).getName());
    assertEquals("sales", schemas.getSchemas().get(0).getName());
    assertEquals("orders", tableList.getTables().get(0).getName());
    assertThrows(UnsupportedOperationException.class, () -> tableList.getTables().clear());

    JsonNode tableJson = objectMapper.valueToTree(tableList);
    assertEquals("orders", tableJson.get("tables").get(0).get("name").asText());
    assertFalse(tableJson.has("nextPageToken"));
    assertTrue(tableJson.get("tables").isArray());
  }

  @Test
  public void testListAndReferenceModelsIgnoreFutureResponseFields() throws Exception {
    TableListResponse response =
        objectMapper.readValue(
            "{\"tables\":[{\"resourceName\":\"metalakes/demo/catalogs/lakehouse/schemas/sales/"
                + "tables/orders\",\"name\":\"orders\",\"future\":true}],\"future\":true}",
            TableListResponse.class);

    assertEquals(1, response.getTables().size());
    assertEquals("orders", response.getTables().get(0).getName());
  }

  @Test
  public void testExistingTableAndAuditResponsesIgnoreFutureFields() {
    Audit audit =
        assertDoesNotThrow(
            () ->
                objectMapper.readValue(
                    "{\"creator\":\"data-platform\",\"future\":true}", Audit.class));
    TableResource table =
        assertDoesNotThrow(
            () ->
                objectMapper.readValue(
                    "{\"resourceName\":\"metalakes/demo/catalogs/lakehouse/schemas/sales/"
                        + "tables/orders\",\"name\":\"orders\",\"columns\":[],\"properties\":{},"
                        + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[],\"future\":true}",
                    TableResource.class));

    assertEquals("data-platform", audit.getCreator());
    assertEquals("orders", table.getName());
  }
}
