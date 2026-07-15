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
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestTableCreateRequest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testRoundTripAndOptionalTypedFields() throws Exception {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.MANAGED,
            TableStorage.TableFormat.ICEBERG,
            "s3://warehouse/orders",
            TableStorage.FileFormat.PARQUET);
    TableCreateRequest request =
        new TableCreateRequest(
            "orders",
            null,
            Collections.singletonList(column()),
            storage,
            new IcebergOptions(2),
            null,
            null,
            null,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    JsonNode json = objectMapper.valueToTree(request);
    assertEquals("orders", json.get("name").asText());
    assertFalse(json.has("comment"));
    assertFalse(json.has("distribution"));
    assertFalse(json.has("properties"));
    assertEquals("MANAGED", json.get("storage").get("ownership").asText());
    assertEquals("ICEBERG", json.get("storage").get("tableFormat").asText());
    assertEquals("PARQUET", json.get("storage").get("fileFormat").asText());
    assertEquals(2, json.get("icebergOptions").get("formatVersion").asInt());
    assertTrue(json.get("partitioning").isEmpty());
    assertTrue(json.get("sortOrders").isEmpty());
    assertTrue(json.get("indexes").isEmpty());

    TableCreateRequest parsed = objectMapper.readValue(json.toString(), TableCreateRequest.class);
    assertEquals("orders", parsed.getName());
    assertNull(parsed.getComment());
    assertEquals(1, parsed.getColumns().size());
    assertEquals("id", parsed.getColumns().get(0).getName());
    assertEquals(TableStorage.Ownership.MANAGED, parsed.getStorage().getOwnership());
    assertEquals(TableStorage.TableFormat.ICEBERG, parsed.getStorage().getTableFormat());
    assertEquals("s3://warehouse/orders", parsed.getStorage().getLocation());
    assertEquals(TableStorage.FileFormat.PARQUET, parsed.getStorage().getFileFormat());
    assertEquals(2, parsed.getIcebergOptions().getFormatVersion());
    assertTrue(parsed.getPartitioning().isEmpty());
    assertNull(parsed.getDistribution());
    assertTrue(parsed.getSortOrders().isEmpty());
    assertTrue(parsed.getIndexes().isEmpty());
  }

  @Test
  public void testDefensiveCopiesAndTypedOptionAccessors() {
    List<Column> columns = new ArrayList<>(Collections.singletonList(column()));
    MysqlOptions mysqlOptions = new MysqlOptions("InnoDB", 10);

    TableCreateRequest request =
        new TableCreateRequest(
            "orders",
            "Customer orders",
            columns,
            null,
            null,
            null,
            null,
            mysqlOptions,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    columns.clear();
    assertEquals(1, request.getColumns().size());
    assertEquals("InnoDB", request.getMysqlOptions().getEngine());
    assertEquals(10, request.getMysqlOptions().getAutoIncrementOffset());
    assertThrows(UnsupportedOperationException.class, () -> request.getColumns().clear());
  }

  @Test
  public void testRejectsInvalidRequiredValuesAndMultipleProviderOptions() {
    assertThrows(
        NullPointerException.class,
        () ->
            new TableCreateRequest(
                null,
                null,
                Collections.singletonList(column()),
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList()));
    TableCreateRequest connectorOwnedSchema =
        new TableCreateRequest(
            "orders",
            null,
            Collections.emptyList(),
            null,
            null,
            null,
            null,
            null,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());
    assertTrue(connectorOwnedSchema.getColumns().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TableCreateRequest(
                "orders",
                null,
                Collections.singletonList(column()),
                null,
                new IcebergOptions(2),
                new HiveOptions("input", null, null, null),
                null,
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
            + "\"partitioning\":[],\"sortOrders\":[],\"indexes\":[]";

    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{" + validFields + ",\"unknown\":true}", TableCreateRequest.class));
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{" + validFields + ",\"properties\":{}}", TableCreateRequest.class));
    TableCreateRequest connectorOwnedSchema =
        assertDoesNotThrow(
            () ->
                objectMapper.readValue(
                    "{\"name\":\"orders\",\"columns\":[],\"partitioning\":[],"
                        + "\"sortOrders\":[],\"indexes\":[]}",
                    TableCreateRequest.class));
    assertTrue(connectorOwnedSchema.getColumns().isEmpty());
    assertThrows(
        JsonProcessingException.class,
        () ->
            objectMapper.readValue(
                "{\"name\":\"orders\",\"columns\":[{\"name\":\"id\","
                    + "\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
                    + "\"nullable\":false,\"autoIncrement\":false}],\"partitioning\":[],"
                    + "\"sortOrders\":[]}",
                TableCreateRequest.class));
  }

  private static Column column() {
    return new Column("id", new DataType.IntegerType(true), null, false, false, null);
  }
}
