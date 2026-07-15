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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestParentCreateRequest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testMetalakeAndSchemaCreateRequestsRoundTrip() throws Exception {
    assertNameAndProperties(
        objectMapper.valueToTree(
            new MetalakeCreateRequest("demo", null, Collections.singletonMap("owner", "data"))),
        "demo");
    assertNameAndProperties(
        objectMapper.valueToTree(
            new SchemaCreateRequest("sales", null, Collections.singletonMap("owner", "data"))),
        "sales");

    MetalakeCreateRequest metalake =
        objectMapper.readValue(
            "{\"name\":\"demo\",\"properties\":{\"owner\":\"data\"}}", MetalakeCreateRequest.class);
    SchemaCreateRequest schema =
        objectMapper.readValue(
            "{\"name\":\"sales\",\"properties\":{\"owner\":\"data\"}}", SchemaCreateRequest.class);
    assertEquals("demo", metalake.getName());
    assertNull(metalake.getComment());
    assertEquals("sales", schema.getName());
    assertNull(schema.getComment());
  }

  @Test
  public void testCreateRequestPropertyMapsAreImmutable() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("owner", "data");
    MetalakeCreateRequest request = new MetalakeCreateRequest("demo", null, properties);

    properties.clear();
    assertEquals("data", request.getProperties().get("owner"));
    assertThrows(
        UnsupportedOperationException.class, () -> request.getProperties().put("key", "value"));
  }

  @Test
  public void testCatalogCreateProviderRulesAndRoundTrip() throws Exception {
    CatalogCreateRequest managed =
        new CatalogCreateRequest(
            "warehouse", CatalogType.FILESET, null, null, Collections.emptyMap());
    JsonNode managedJson = objectMapper.valueToTree(managed);
    assertEquals("FILESET", managedJson.get("type").asText());
    assertFalse(managedJson.has("provider"));

    CatalogCreateRequest external =
        objectMapper.readValue(
            "{\"name\":\"lakehouse\",\"type\":\"RELATIONAL\",\"provider\":\"iceberg\","
                + "\"properties\":{}}",
            CatalogCreateRequest.class);
    assertEquals(CatalogType.RELATIONAL, external.getType());
    assertEquals("iceberg", external.getProvider());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCreateRequest(
                "lakehouse", CatalogType.RELATIONAL, null, null, Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCreateRequest(
                "warehouse", CatalogType.FILESET, "", null, Collections.emptyMap()));
  }

  @Test
  public void testJacksonRejectsUnknownAndMissingFields() {
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"name\":\"demo\",\"properties\":{},\"unknown\":true}",
                MetalakeCreateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () -> objectMapper.readValue("{\"name\":\"sales\"}", SchemaCreateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"name\":\"lakehouse\",\"type\":\"RELATIONAL\",\"properties\":{}}",
                CatalogCreateRequest.class));
  }

  private static void assertNameAndProperties(JsonNode json, String name) {
    assertEquals(name, json.get("name").asText());
    assertEquals("data", json.get("properties").get("owner").asText());
    assertFalse(json.has("comment"));
    assertTrue(json.get("properties").isObject());
  }
}
