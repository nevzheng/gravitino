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

public class TestParentUpdateRequest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testDesiredStateRoundTripWithExplicitNullComment() throws Exception {
    MetalakeUpdateRequest metalake =
        new MetalakeUpdateRequest(null, Collections.singletonMap("owner", "data"));
    CatalogUpdateRequest catalog =
        new CatalogUpdateRequest(
            "Catalog comment", Collections.singletonMap("uri", "thrift://hms"));
    SchemaUpdateRequest schema =
        new SchemaUpdateRequest(null, Collections.singletonMap("location", "s3://warehouse"));

    JsonNode metalakeJson = objectMapper.valueToTree(metalake);
    JsonNode catalogJson = objectMapper.valueToTree(catalog);
    JsonNode schemaJson = objectMapper.valueToTree(schema);
    assertNull(metalake.getComment());
    assertFalse(metalakeJson.has("updates"));
    assertFalse(metalakeJson.has("name"));
    assertFalse(catalogJson.has("type"));
    assertFalse(catalogJson.has("provider"));
    assertFalse(schemaJson.has("name"));
    assertEquals("Catalog comment", catalogJson.get("comment").asText());
    assertTrue(metalakeJson.get("comment").isNull());
    assertTrue(schemaJson.get("comment").isNull());

    MetalakeUpdateRequest parsedMetalake =
        objectMapper.readValue(metalakeJson.toString(), MetalakeUpdateRequest.class);
    CatalogUpdateRequest parsedCatalog =
        objectMapper.readValue(catalogJson.toString(), CatalogUpdateRequest.class);
    SchemaUpdateRequest parsedSchema =
        objectMapper.readValue(schemaJson.toString(), SchemaUpdateRequest.class);
    assertNull(parsedMetalake.getComment());
    assertEquals("data", parsedMetalake.getProperties().get("owner"));
    assertEquals("Catalog comment", parsedCatalog.getComment());
    assertNull(parsedSchema.getComment());
  }

  @Test
  public void testDesiredStatePropertiesAreDefensivelyCopied() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("owner", "data");
    SchemaUpdateRequest request = new SchemaUpdateRequest(null, properties);

    properties.clear();
    assertEquals("data", request.getProperties().get("owner"));
    assertThrows(
        UnsupportedOperationException.class, () -> request.getProperties().put("key", "value"));
  }

  @Test
  public void testRejectsMissingOrUnknownDesiredStateFields() {
    assertThrows(
        JsonProcessingException.class,
        () -> objectMapper.readValue("{\"properties\":{}}", MetalakeUpdateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () -> objectMapper.readValue("{\"comment\":null}", CatalogUpdateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"comment\":null,\"properties\":{},\"updates\":[]}", SchemaUpdateRequest.class));
  }
}
