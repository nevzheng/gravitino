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
package org.apache.gravitino.server.web.rest.v1.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rest.v1.model.Column;
import org.apache.gravitino.rest.v1.model.DataType;
import org.apache.gravitino.rest.v1.model.Distribution;
import org.apache.gravitino.rest.v1.model.Expression;
import org.apache.gravitino.rest.v1.model.Index;
import org.apache.gravitino.rest.v1.model.SortOrder;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.rest.v1.model.TableUpdateRequest;
import org.apache.gravitino.rest.v1.model.Transform;
import org.junit.jupiter.api.Test;

/** Tests for mapping a V1 desired table state to supported table mutations. */
public class TestTableMutationMapper {

  private static final String RESOURCE_NAME =
      "metalakes/demo/catalogs/lakehouse/schemas/sales/tables/orders";

  @Test
  public void testDesiredStateProducesDeterministicCommentAndPropertyMutations() {
    TableResource current =
        resource(
            "old comment",
            Map.of(
                "change", "old",
                "remove-a", "old",
                "remove-z", "old",
                "same", "same"),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());
    TableUpdateRequest desired =
        desired(
            "new comment",
            Map.of("add-a", "new", "change", "new", "add-z", "new", "same", "same"),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList());

    assertArrayEquals(
        new TableChange[] {
          TableChange.updateComment("new comment"),
          TableChange.setProperty("add-a", "new"),
          TableChange.setProperty("add-z", "new"),
          TableChange.setProperty("change", "new"),
          TableChange.removeProperty("remove-a"),
          TableChange.removeProperty("remove-z")
        },
        TableMutationMapper.toChanges(current, desired));
  }

  @Test
  public void testMatchingDesiredStateProducesNoMutations() {
    List<Column> columns = Collections.singletonList(column());
    List<Transform> partitioning =
        Collections.singletonList(new Transform.Identity(Collections.singletonList("id")));
    Distribution distribution =
        new Distribution(
            Distribution.Strategy.HASH,
            8,
            Collections.singletonList(Expression.Reference.named("id")));
    List<SortOrder> sortOrders =
        Collections.singletonList(
            new SortOrder(
                Expression.Reference.named("id"),
                SortOrder.Direction.ASC,
                SortOrder.NullOrdering.NULLS_LAST));
    List<Index> indexes =
        Collections.singletonList(
            new Index(
                Index.Type.PRIMARY_KEY,
                "pk_orders",
                Collections.singletonList(Collections.singletonList("id")),
                Collections.singletonMap("enforced", "true")));
    TableResource current =
        resource(
            "same comment",
            Collections.singletonMap("format", "iceberg"),
            columns,
            partitioning,
            distribution,
            sortOrders,
            indexes);
    TableUpdateRequest desired =
        desired(
            "same comment",
            Collections.singletonMap("format", "iceberg"),
            columns,
            partitioning,
            distribution,
            sortOrders,
            indexes);

    assertArrayEquals(new TableChange[0], TableMutationMapper.toChanges(current, desired));
  }

  @Test
  public void testRejectsDifferingColumnsBeforeProducingChanges() {
    assertUnsupportedReplacement(
        "columns",
        resource(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()),
        desired(
            "comment",
            Collections.emptyMap(),
            Collections.singletonList(column()),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()));
  }

  @Test
  public void testRejectsDifferingPartitioningBeforeProducingChanges() {
    assertUnsupportedReplacement(
        "partitioning",
        resource(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()),
        desired(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.singletonList(new Transform.Identity(Collections.singletonList("id"))),
            null,
            Collections.emptyList(),
            Collections.emptyList()));
  }

  @Test
  public void testRejectsDifferingDistributionBeforeProducingChanges() {
    assertUnsupportedReplacement(
        "distribution",
        resource(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()),
        desired(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            new Distribution(
                Distribution.Strategy.HASH,
                8,
                Collections.singletonList(Expression.Reference.named("id"))),
            Collections.emptyList(),
            Collections.emptyList()));
  }

  @Test
  public void testRejectsDifferingSortOrdersBeforeProducingChanges() {
    assertUnsupportedReplacement(
        "sortOrders",
        resource(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()),
        desired(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.singletonList(
                new SortOrder(
                    Expression.Reference.named("id"),
                    SortOrder.Direction.ASC,
                    SortOrder.NullOrdering.NULLS_LAST)),
            Collections.emptyList()));
  }

  @Test
  public void testRejectsDifferingIndexesBeforeProducingChanges() {
    assertUnsupportedReplacement(
        "indexes",
        resource(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.emptyList()),
        desired(
            "comment",
            Collections.emptyMap(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            Collections.singletonList(
                new Index(
                    Index.Type.PRIMARY_KEY,
                    "pk_orders",
                    Collections.singletonList(Collections.singletonList("id")),
                    Collections.emptyMap()))));
  }

  private static void assertUnsupportedReplacement(
      String field, TableResource current, TableUpdateRequest desired) {
    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class,
            () -> TableMutationMapper.toChanges(current, desired));
    assertEquals(
        "V1 table " + field + " replacement is not supported by the current dispatcher.",
        exception.getMessage());
  }

  private static TableResource resource(
      String comment,
      Map<String, String> properties,
      List<Column> columns,
      List<Transform> partitioning,
      Distribution distribution,
      List<SortOrder> sortOrders,
      List<Index> indexes) {
    return new TableResource(
        RESOURCE_NAME,
        "orders",
        comment,
        columns,
        properties,
        partitioning,
        distribution,
        sortOrders,
        indexes,
        null);
  }

  private static TableUpdateRequest desired(
      String comment,
      Map<String, String> properties,
      List<Column> columns,
      List<Transform> partitioning,
      Distribution distribution,
      List<SortOrder> sortOrders,
      List<Index> indexes) {
    return new TableUpdateRequest(
        comment, columns, properties, partitioning, distribution, sortOrders, indexes);
  }

  private static Column column() {
    return new Column("id", new DataType.IntegerType(true), null, false, false, null);
  }
}
