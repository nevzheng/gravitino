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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestV1RequestModelValidation {

  @Test
  public void testCreateRequestsValidateIdentifiersCommentsAndProvider() {
    assertDoesNotThrow(
        () -> new MetalakeCreateRequest("analytics lake", "A metalake", Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetalakeCreateRequest(" analytics", null, Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SchemaCreateRequest(repeated('s', 256), null, Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCreateRequest(
                "catalog", CatalogType.RELATIONAL, "iceberg ", null, Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCreateRequest(
                "catalog",
                CatalogType.RELATIONAL,
                repeated('p', 1_025),
                null,
                Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetalakeCreateRequest("analytics", " comment", Collections.emptyMap()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SchemaCreateRequest("warehouse", repeated('c', 16_385), Collections.emptyMap()));
  }

  @Test
  public void testParentRequestPropertiesMatchTheirDocumentedBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetalakeCreateRequest("analytics", null, Collections.singletonMap("owner", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SchemaCreateRequest(
                "warehouse", null, Collections.singletonMap("owner", repeated('v', 1_048_577))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CatalogUpdateRequest(null, properties(10_001, "value")));
    assertDoesNotThrow(
        () -> new MetalakeUpdateRequest(null, Collections.singletonMap("owner", "data")));
    assertThrows(
        IllegalArgumentException.class, () -> new SchemaUpdateRequest(" ", Collections.emptyMap()));
  }

  @Test
  public void testTableRequestsValidateDocumentedBoundsWithoutTighteningProperties() {
    TableCreateRequest emptyTableProperty =
        createTable(
            "orders",
            null,
            Collections.emptyList(),
            Collections.singletonMap("format", ""),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList());
    assertEquals("", emptyTableProperty.getProperties().get("format"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                " orders",
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                "orders ",
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                null,
                Collections.emptyList(),
                Collections.singletonMap("format", repeated('v', 1_048_577)),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                null,
                Collections.nCopies(10_001, column()),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.nCopies(1_025, partitioning()),
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.nCopies(1_025, sortOrder()),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createTable(
                "orders",
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.nCopies(1_025, index())));
  }

  @Test
  public void testTableUpdateValidationPermitsExplicitNullAndBoundsItsCollections() {
    assertDoesNotThrow(
        () ->
            new TableUpdateRequest(
                null,
                Collections.emptyList(),
                Collections.singletonMap("format", ""),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TableUpdateRequest(
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList(),
                null,
                Collections.nCopies(1_025, sortOrder()),
                Collections.emptyList()));
  }

  private static TableCreateRequest createTable(
      String name,
      String comment,
      List<Column> columns,
      Map<String, String> properties,
      List<Transform> partitioning,
      List<SortOrder> sortOrders,
      List<Index> indexes) {
    return new TableCreateRequest(
        name, comment, columns, properties, partitioning, null, sortOrders, indexes);
  }

  private static Column column() {
    return new Column("id", new DataType.IntegerType(true), null, false, false, null);
  }

  private static Transform partitioning() {
    return new Transform.Identity(Collections.singletonList("id"));
  }

  private static SortOrder sortOrder() {
    return new SortOrder(
        Expression.Reference.named("id"),
        SortOrder.Direction.ASC,
        SortOrder.NullOrdering.NULLS_FIRST);
  }

  private static Index index() {
    return new Index(
        Index.Type.BTREE,
        null,
        Collections.singletonList(Collections.singletonList("id")),
        Collections.emptyMap());
  }

  private static Map<String, String> properties(int count, String value) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (int index = 0; index < count; index++) {
      properties.put("key-" + index, value);
    }
    return properties;
  }

  private static String repeated(char value, int count) {
    StringBuilder builder = new StringBuilder(count);
    for (int index = 0; index < count; index++) {
      builder.append(value);
    }
    return builder.toString();
  }
}
