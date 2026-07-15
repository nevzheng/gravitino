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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestTableCreateRequest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testRoundTripAndOptionalFields() throws Exception {
    TableCreateRequest request =
        new TableCreateRequest(
            "orders",
            null,
            Collections.singletonList(column()),
            Collections.singletonMap("format", "iceberg"),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    JsonNode json = objectMapper.valueToTree(request);
    assertEquals("orders", json.get("name").asText());
    assertFalse(json.has("comment"));
    assertFalse(json.has("distribution"));
    assertEquals("iceberg", json.get("properties").get("format").asText());
    assertTrue(json.get("partitioning").isEmpty());
    assertTrue(json.get("sortOrders").isEmpty());
    assertTrue(json.get("indexes").isEmpty());

    TableCreateRequest parsed = objectMapper.readValue(json.toString(), TableCreateRequest.class);
    assertEquals("orders", parsed.getName());
    assertNull(parsed.getComment());
    assertEquals(1, parsed.getColumns().size());
    assertEquals("id", parsed.getColumns().get(0).getName());
    assertEquals("iceberg", parsed.getProperties().get("format"));
    assertTrue(parsed.getPartitioning().isEmpty());
    assertNull(parsed.getDistribution());
    assertTrue(parsed.getSortOrders().isEmpty());
    assertTrue(parsed.getIndexes().isEmpty());
  }

  @Test
  public void testDefensiveCopies() {
    List<Column> columns = new ArrayList<>(Collections.singletonList(column()));
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("format", "iceberg");

    TableCreateRequest request =
        new TableCreateRequest(
            "orders",
            "Customer orders",
            columns,
            properties,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    columns.clear();
    properties.clear();
    assertEquals(1, request.getColumns().size());
    assertEquals("iceberg", request.getProperties().get("format"));
    assertThrows(UnsupportedOperationException.class, () -> request.getColumns().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> request.getProperties().put("owner", "data"));
  }

  @Test
  public void testRejectsInvalidRequiredValues() {
    assertThrows(
        NullPointerException.class,
        () ->
            new TableCreateRequest(
                null,
                null,
                Collections.singletonList(column()),
                Collections.emptyMap(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList()));
    TableCreateRequest connectorOwnedSchema =
        new TableCreateRequest(
            "orders",
            null,
            Collections.emptyList(),
            Collections.emptyMap(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());
    assertTrue(connectorOwnedSchema.getColumns().isEmpty());
    assertThrows(
        NullPointerException.class,
        () ->
            new TableCreateRequest(
                "orders",
                null,
                Collections.singletonList(column()),
                null,
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList()));
  }

  @Test
  public void testJacksonRejectsUnknownAndMissingRequiredFields() {
    String validFields =
        "\"name\":\"orders\","
            + "\"columns\":[{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\","
            + "\"signed\":true},\"nullable\":false,\"autoIncrement\":false}],"
            + "\"properties\":{},\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]";

    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{" + validFields + ",\"unknown\":true}", TableCreateRequest.class));
    TableCreateRequest connectorOwnedSchema =
        assertDoesNotThrow(
            () ->
                objectMapper.readValue(
                    "{\"name\":\"orders\",\"columns\":[],\"properties\":{},"
                        + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]}",
                    TableCreateRequest.class));
    assertTrue(connectorOwnedSchema.getColumns().isEmpty());
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"name\":\"orders\",\"columns\":[{\"name\":\"id\","
                    + "\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
                    + "\"nullable\":false,\"autoIncrement\":false}],\"partitioning\":[],"
                    + "\"sortOrders\":[],\"indexes\":[]}",
                TableCreateRequest.class));
  }

  private static Column column() {
    return new Column("id", new DataType.IntegerType(true), null, false, false, null);
  }
}
