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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Audit;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.dto.rel.expressions.FieldReferenceDTO;
import org.apache.gravitino.dto.rel.expressions.FuncExpressionDTO;
import org.apache.gravitino.dto.rel.expressions.LiteralDTO;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.NamedReference;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.distributions.Strategy;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.NullOrdering;
import org.apache.gravitino.rel.expressions.sorts.SortDirection;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.sorts.SortOrders;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.v1.model.DataType;
import org.apache.gravitino.rest.v1.model.Expression;
import org.apache.gravitino.rest.v1.model.PartitionAssignment;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.junit.jupiter.api.Test;

public class TestTableMapper {

  private static final String RESOURCE_NAME =
      "metalakes/demo/catalogs/lakehouse/schemas/sales/tables/orders";

  @Test
  public void testMapsEveryCurrentDataType() {
    List<Type> types =
        Arrays.asList(
            Types.BooleanType.get(),
            Types.ByteType.get(),
            Types.ByteType.unsigned(),
            Types.ShortType.get(),
            Types.ShortType.unsigned(),
            Types.IntegerType.get(),
            Types.IntegerType.unsigned(),
            Types.LongType.get(),
            Types.LongType.unsigned(),
            Types.FloatType.get(),
            Types.DoubleType.get(),
            Types.DecimalType.of(18, 4),
            Types.DateType.get(),
            Types.TimeType.get(),
            Types.TimeType.of(6),
            Types.TimestampType.withoutTimeZone(),
            Types.TimestampType.withTimeZone(9),
            Types.IntervalYearType.get(),
            Types.IntervalDayType.get(),
            Types.StringType.get(),
            Types.VarCharType.of(32),
            Types.FixedCharType.of(8),
            Types.UUIDType.get(),
            Types.FixedType.of(16),
            Types.BinaryType.get(),
            Types.VariantType.get(),
            Types.StructType.of(
                Types.StructType.Field.nullableField("nested", Types.StringType.get(), "field")),
            Types.ListType.notNull(Types.LongType.get()),
            Types.MapType.valueNullable(Types.StringType.get(), Types.IntegerType.get()),
            Types.UnionType.of(Types.StringType.get(), Types.IntegerType.get()),
            Types.NullType.get(),
            Types.UnparsedType.of("geography"),
            Types.ExternalType.of("GEOMETRY(4326)"));
    Column[] columns = new Column[types.size()];
    for (int index = 0; index < types.size(); index++) {
      columns[index] = Column.of("column_" + index, types.get(index));
    }

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table(columns));
    Set<DataType.Kind> mappedKinds =
        resource.getColumns().stream()
            .map(column -> column.getType().getKind())
            .collect(Collectors.toSet());

    assertEquals(EnumSet.allOf(DataType.Kind.class), mappedKinds);
    assertTrue(
        resource.getColumns().stream()
            .map(column -> column.getType())
            .filter(DataType.IntegralType.class::isInstance)
            .map(DataType.IntegralType.class::cast)
            .anyMatch(type -> !type.isSigned()));
    assertEquals(
        6,
        resource.getColumns().stream()
            .map(column -> column.getType())
            .filter(DataType.TimeType.class::isInstance)
            .map(DataType.TimeType.class::cast)
            .filter(type -> type.getPrecision() != null)
            .findFirst()
            .orElseThrow(AssertionError::new)
            .getPrecision());
    DataType.StructType struct =
        resource.getColumns().stream()
            .map(column -> column.getType())
            .filter(DataType.StructType.class::isInstance)
            .map(DataType.StructType.class::cast)
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertEquals("nested", struct.getFields().get(0).getName());
    assertEquals("field", struct.getFields().get(0).getComment());
  }

  @Test
  public void testMapsSupportedIcebergValueExpressionSubset() {
    Column[] columns =
        new Column[] {
          Column.of("unset", Types.IntegerType.get()),
          Column.of(
              "literal", Types.IntegerType.get(), null, false, false, Literals.integerLiteral(34)),
          Column.of(
              "reference",
              Types.IntegerType.get(),
              null,
              true,
              false,
              NamedReference.field("source")),
          Column.of(
              "function",
              Types.StringType.get(),
              null,
              true,
              false,
              FunctionExpression.of(
                  "concat", NamedReference.field("source"), Literals.stringLiteral("suffix"))),
          Column.of(
              "date",
              Types.DateType.get(),
              null,
              true,
              false,
              Literals.dateLiteral(LocalDate.of(2026, 7, 14))),
          Column.of(
              "decimal",
              Types.DecimalType.of(4, 2),
              null,
              true,
              false,
              Literals.decimalLiteral(Decimal.of(new BigDecimal("12.30"), 4, 2))),
          Column.of(
              "dto_literal",
              Types.IntegerType.get(),
              null,
              true,
              false,
              LiteralDTO.builder().withDataType(Types.IntegerType.get()).withValue("42").build()),
          Column.of(
              "dto_reference",
              Types.StringType.get(),
              null,
              true,
              false,
              FieldReferenceDTO.of("source")),
          Column.of(
              "dto_function",
              Types.StringType.get(),
              null,
              true,
              false,
              FuncExpressionDTO.builder()
                  .withFunctionName("lower")
                  .withFunctionArgs(FieldReferenceDTO.of("source"))
                  .build()),
          Column.of("dto_null", Types.NullType.get(), null, true, false, LiteralDTO.NULL),
          Column.of(
              "binary",
              Types.BinaryType.get(),
              null,
              true,
              false,
              Literals.of(new byte[] {0, 1, 2, (byte) 0xff}, Types.BinaryType.get())),
          Column.of(
              "fixed",
              Types.FixedType.of(4),
              null,
              true,
              false,
              Literals.of(ByteBuffer.wrap(new byte[] {10, 11, 12, 13}), Types.FixedType.of(4)))
        };

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table(columns));
    assertNull(resource.getColumns().get(0).getDefaultValue());
    Expression.Literal literal =
        assertInstanceOf(Expression.Literal.class, resource.getColumns().get(1).getDefaultValue());
    assertEquals(34, literal.getValue());
    assertEquals(DataType.Kind.INTEGER, literal.getDataType().getKind());
    Expression.Reference reference =
        assertInstanceOf(
            Expression.Reference.class, resource.getColumns().get(2).getDefaultValue());
    assertEquals("source", reference.getName());
    Expression.Apply apply =
        assertInstanceOf(Expression.Apply.class, resource.getColumns().get(3).getDefaultValue());
    assertEquals(Collections.singletonList("concat"), apply.getFunction().getIdentifier());
    assertEquals(2, apply.getArguments().size());
    assertEquals(
        "2026-07-14",
        ((Expression.Literal) resource.getColumns().get(4).getDefaultValue()).getValue());
    assertEquals(
        "12.30", ((Expression.Literal) resource.getColumns().get(5).getDefaultValue()).getValue());
    assertEquals(
        42, ((Expression.Literal) resource.getColumns().get(6).getDefaultValue()).getValue());
    assertEquals(
        "source",
        ((Expression.Reference) resource.getColumns().get(7).getDefaultValue()).getName());
    assertEquals(
        Collections.singletonList("lower"),
        ((Expression.Apply) resource.getColumns().get(8).getDefaultValue())
            .getFunction()
            .getIdentifier());
    assertNull(((Expression.Literal) resource.getColumns().get(9).getDefaultValue()).getValue());
    assertEquals(
        "000102ff",
        ((Expression.Literal) resource.getColumns().get(10).getDefaultValue()).getValue());
    assertEquals(
        "0a0b0c0d",
        ((Expression.Literal) resource.getColumns().get(11).getDefaultValue()).getValue());
  }

  @Test
  public void testNormalizesLegacyDtoScalarsToIcebergJsonValues() {
    Column[] columns =
        new Column[] {
          dtoLiteralColumn("boolean", Types.BooleanType.get(), "true"),
          dtoLiteralColumn("uint", Types.IntegerType.unsigned(), "4294967295"),
          dtoLiteralColumn("ulong", Types.LongType.unsigned(), "18446744073709551615"),
          dtoLiteralColumn("float", Types.FloatType.get(), "1.5"),
          dtoLiteralColumn("binary", Types.BinaryType.get(), "000102ff")
        };

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table(columns));
    assertEquals(true, defaultLiteralValue(resource, 0));
    assertEquals(4_294_967_295L, defaultLiteralValue(resource, 1));
    assertEquals(new BigInteger("18446744073709551615"), defaultLiteralValue(resource, 2));
    assertEquals(1.5F, defaultLiteralValue(resource, 3));
    assertEquals("000102ff", defaultLiteralValue(resource, 4));
  }

  @Test
  public void testRejectsInternalExpressionsOutsidePublicGrammar() {
    Table unparsedTable =
        table(
            new Column[] {
              Column.of(
                  "unsupported",
                  Types.StringType.get(),
                  null,
                  true,
                  false,
                  UnparsedExpression.of("catalog_expression"))
            });
    assertThrows(
        IllegalArgumentException.class, () -> TableMapper.toResource(RESOURCE_NAME, unparsedTable));

    Table nestedReferenceTable =
        table(
            new Column[] {
              Column.of(
                  "nested",
                  Types.StringType.get(),
                  null,
                  true,
                  false,
                  NamedReference.field(new String[] {"struct", "field"}))
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> TableMapper.toResource(RESOURCE_NAME, nestedReferenceTable));

    Table metadataReferenceTable =
        table(
            new Column[] {
              Column.of(
                  "metadata",
                  Types.StringType.get(),
                  null,
                  true,
                  false,
                  NamedReference.metadataField(new String[] {"partition_name"}))
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> TableMapper.toResource(RESOURCE_NAME, metadataReferenceTable));

    Table structuredLiteralTable =
        table(
            new Column[] {
              Column.of(
                  "structured",
                  Types.ListType.notNull(Types.IntegerType.get()),
                  null,
                  true,
                  false,
                  Literals.of(Arrays.asList(1, 2), Types.ListType.notNull(Types.IntegerType.get())))
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> TableMapper.toResource(RESOURCE_NAME, structuredLiteralTable));

    Table invalidDtoLiteralTable =
        table(
            new Column[] {
              Column.of(
                  "invalid_dto",
                  Types.IntegerType.get(),
                  null,
                  true,
                  false,
                  LiteralDTO.builder()
                      .withDataType(Types.IntegerType.get())
                      .withValue("not-an-integer")
                      .build())
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> TableMapper.toResource(RESOURCE_NAME, invalidDtoLiteralTable));
  }

  @Test
  public void testMapsEveryPartitionTransformAndAssignment() {
    ListPartition listAssignment =
        Partitions.list(
            "west",
            new org.apache.gravitino.rel.expressions.literals.Literal<?>[][] {
              {Literals.stringLiteral("US"), Literals.stringLiteral("CA")}
            },
            Collections.singletonMap("location", "west"));
    RangePartition rangeAssignment =
        Partitions.range(
            "recent",
            Literals.integerLiteral(100),
            Literals.integerLiteral(1),
            Collections.singletonMap("location", "recent"));
    Transform[] transforms =
        new Transform[] {
          Transforms.identity("id"),
          Transforms.year("created_at"),
          Transforms.month("created_at"),
          Transforms.day("created_at"),
          Transforms.hour("created_at"),
          Transforms.bucket(16, new String[] {"id"}, new String[] {"tenant"}),
          Transforms.truncate(8, "name"),
          Transforms.list(
              new String[][] {new String[] {"country"}, new String[] {"state"}},
              new ListPartition[] {listAssignment}),
          Transforms.range(new String[] {"id"}, new RangePartition[] {rangeAssignment}),
          Transforms.apply(
              "custom_transform",
              new org.apache.gravitino.rel.expressions.Expression[] {
                NamedReference.field("id"), Literals.integerLiteral(2)
              })
        };
    Table table = table(new Column[] {Column.of("id", Types.IntegerType.get())});
    when(table.partitioning()).thenReturn(transforms);

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table);
    assertEquals(10, resource.getPartitioning().size());
    assertEquals(
        EnumSet.allOf(org.apache.gravitino.rest.v1.model.Transform.Kind.class),
        resource.getPartitioning().stream()
            .map(org.apache.gravitino.rest.v1.model.Transform::getKind)
            .collect(Collectors.toSet()));
    org.apache.gravitino.rest.v1.model.Transform.ListTransform list =
        assertInstanceOf(
            org.apache.gravitino.rest.v1.model.Transform.ListTransform.class,
            resource.getPartitioning().get(7));
    PartitionAssignment.ListAssignment mappedList =
        assertInstanceOf(PartitionAssignment.ListAssignment.class, list.getAssignments().get(0));
    assertEquals("CA", mappedList.getValues().get(0).get(1).getValue());
    org.apache.gravitino.rest.v1.model.Transform.Range range =
        assertInstanceOf(
            org.apache.gravitino.rest.v1.model.Transform.Range.class,
            resource.getPartitioning().get(8));
    PartitionAssignment.Range mappedRange =
        assertInstanceOf(PartitionAssignment.Range.class, range.getAssignments().get(0));
    assertEquals(1, mappedRange.getLower().getValue());
    assertEquals(100, mappedRange.getUpper().getValue());
  }

  @Test
  public void testMapsDistributionSortsIndexesAndPartialAudit() {
    for (Strategy strategy : Strategy.values()) {
      Table table = table(new Column[] {Column.of("id", Types.IntegerType.get())});
      Distribution distribution =
          strategy == Strategy.NONE
              ? Distributions.NONE
              : Distributions.of(strategy, 4, NamedReference.field("id"));
      when(table.distribution()).thenReturn(distribution);
      TableResource resource = TableMapper.toResource(RESOURCE_NAME, table);
      assertEquals(strategy.name(), resource.getDistribution().getStrategy().name());
      if (strategy == Strategy.NONE) {
        assertNull(resource.getDistribution().getBucketCount());
      } else {
        assertEquals(4, resource.getDistribution().getBucketCount());
      }
    }

    Table table = table(new Column[] {Column.of("id", Types.IntegerType.get())});
    SortOrder[] sortOrders =
        new SortOrder[] {
          SortOrders.of(
              NamedReference.field("id"), SortDirection.ASCENDING, NullOrdering.NULLS_LAST),
          SortOrders.of(
              NamedReference.field("id"), SortDirection.DESCENDING, NullOrdering.NULLS_FIRST)
        };
    when(table.sortOrder()).thenReturn(sortOrders);
    List<Index> indexes = new ArrayList<>();
    for (Index.IndexType type : Index.IndexType.values()) {
      indexes.add(
          Indexes.of(
              type,
              type.name(),
              new String[][] {new String[] {"id"}},
              Collections.singletonMap("option", type.name())));
    }
    when(table.index()).thenReturn(indexes.toArray(new Index[0]));
    Audit audit = mock(Audit.class);
    when(audit.creator()).thenReturn("nevin");
    when(audit.createTime()).thenReturn(Instant.parse("2026-07-14T12:00:00Z"));
    when(table.auditInfo()).thenReturn(audit);

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table);
    assertEquals(
        org.apache.gravitino.rest.v1.model.SortOrder.Direction.ASC,
        resource.getSortOrders().get(0).getDirection());
    assertEquals(
        org.apache.gravitino.rest.v1.model.SortOrder.Direction.DESC,
        resource.getSortOrders().get(1).getDirection());
    assertEquals(Index.IndexType.values().length, resource.getIndexes().size());
    assertEquals("nevin", resource.getAudit().getCreator());
    assertNull(resource.getAudit().getLastModifier());
  }

  @Test
  public void testUsesOnlyRepresentableFilteredProperties() {
    Table catalogTable = table(new Column[0]);
    Map<String, String> properties = new HashMap<>();
    properties.put("engine", "InnoDB");
    properties.put("secret", "credential");
    properties.put("null-value", null);
    properties.put(null, "null-key");
    when(catalogTable.properties()).thenReturn(properties);
    Audit emptyAudit = mock(Audit.class);
    when(catalogTable.auditInfo()).thenReturn(emptyAudit);
    EntityCombinedTable filtered =
        EntityCombinedTable.of(catalogTable).withHiddenProperties(Collections.singleton("secret"));

    TableResource resource = TableMapper.toResource(RESOURCE_NAME, filtered, "jdbc-mysql");
    assertEquals("InnoDB", resource.getMysqlOptions().getEngine());
    assertNull(resource.getAudit());
  }

  @Test
  public void testRequiredEmptyCollectionsOptionalDistributionAndSerialization() {
    TableResource resource = TableMapper.toResource(RESOURCE_NAME, table(new Column[0]));
    assertTrue(resource.getColumns().isEmpty());
    assertNull(resource.getStorage());
    assertNull(resource.getIcebergOptions());
    assertNull(resource.getHiveOptions());
    assertNull(resource.getClickhouseOptions());
    assertNull(resource.getMysqlOptions());
    assertTrue(resource.getPartitioning().isEmpty());
    assertTrue(resource.getSortOrders().isEmpty());
    assertTrue(resource.getIndexes().isEmpty());
    assertNull(resource.getDistribution());
    assertNull(resource.getAudit());

    ObjectMapper objectMapper = ObjectMapperProvider.objectMapper();
    JsonNode json = objectMapper.valueToTree(resource);
    assertEquals(RESOURCE_NAME, json.get("resourceName").asText());
    assertTrue(json.get("columns").isArray());
    assertFalse(json.has("properties"));
    assertFalse(json.has("comment"));
    assertFalse(json.has("distribution"));
    assertFalse(json.has("audit"));
  }

  @Test
  public void testRejectsUnknownInternalVariants() {
    Type type = mock(Type.class);
    when(type.name()).thenReturn(Type.Name.BOOLEAN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TableMapper.toResource(
                RESOURCE_NAME, table(new Column[] {Column.of("unknown", type)})));

    Transform transform = mock(Transform.class);
    Table table = table(new Column[0]);
    when(table.partitioning()).thenReturn(new Transform[] {transform});
    assertThrows(
        IllegalArgumentException.class, () -> TableMapper.toResource(RESOURCE_NAME, table));
  }

  private static Table table(Column[] columns) {
    Table table = mock(Table.class);
    when(table.name()).thenReturn("orders");
    when(table.columns()).thenReturn(columns);
    when(table.properties()).thenReturn(Collections.emptyMap());
    when(table.partitioning()).thenReturn(new Transform[0]);
    when(table.sortOrder()).thenReturn(new SortOrder[0]);
    when(table.index()).thenReturn(new Index[0]);
    return table;
  }

  private static Column dtoLiteralColumn(String name, Type type, String value) {
    return Column.of(
        name,
        type,
        null,
        true,
        false,
        LiteralDTO.builder().withDataType(type).withValue(value).build());
  }

  private static Object defaultLiteralValue(TableResource resource, int columnIndex) {
    return ((Expression.Literal) resource.getColumns().get(columnIndex).getDefaultValue())
        .getValue();
  }
}
