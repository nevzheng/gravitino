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

public class TestTableUpdateRequest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testDesiredStateRoundTripWithExplicitNullComment() throws Exception {
    TableUpdateRequest request =
        new TableUpdateRequest(
            null,
            Collections.singletonList(column()),
            Collections.singletonMap("format", "iceberg"),
            Collections.emptyList(),
            new Distribution(Distribution.Strategy.NONE, null, Collections.emptyList()),
            Collections.emptyList(),
            Collections.emptyList());

    JsonNode json = objectMapper.valueToTree(request);
    assertTrue(json.get("comment").isNull());
    assertEquals("NONE", json.get("distribution").get("strategy").asText());
    assertFalse(json.has("updates"));
    assertFalse(json.has("name"));
    assertEquals(1, json.get("columns").size());

    TableUpdateRequest parsed = objectMapper.readValue(json.toString(), TableUpdateRequest.class);
    assertNull(parsed.getComment());
    assertEquals("id", parsed.getColumns().get(0).getName());
    assertEquals("iceberg", parsed.getProperties().get("format"));
    assertEquals(Distribution.Strategy.NONE, parsed.getDistribution().getStrategy());
  }

  @Test
  public void testAllowsEmptyColumnsForConnectorOwnedSchemaAndDefensiveCopies() {
    List<Column> columns = new ArrayList<>();
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("format", "lance");
    TableUpdateRequest request =
        new TableUpdateRequest(
            "Managed by connector",
            columns,
            properties,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    columns.add(column());
    properties.clear();
    assertTrue(request.getColumns().isEmpty());
    assertEquals("lance", request.getProperties().get("format"));
    assertThrows(UnsupportedOperationException.class, () -> request.getColumns().add(column()));
    assertThrows(
        UnsupportedOperationException.class, () -> request.getProperties().put("key", "value"));
  }

  @Test
  public void testRejectsMissingOrUnknownDesiredStateFields() {
    String requiredFields =
        "\"comment\":null,\"columns\":[],\"properties\":{},\"partitioning\":[],"
            + "\"sortOrders\":[],\"indexes\":[]";

    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{" + requiredFields + ",\"updates\":[]}", TableUpdateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"comment\":null,\"properties\":{},\"partitioning\":[],"
                    + "\"sortOrders\":[],\"indexes\":[]}",
                TableUpdateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"columns\":[],\"properties\":{},\"partitioning\":[],"
                    + "\"sortOrders\":[],\"indexes\":[]}",
                TableUpdateRequest.class));
  }

  private static Column column() {
    return new Column("id", new DataType.IntegerType(true), null, false, false, null);
  }
}
