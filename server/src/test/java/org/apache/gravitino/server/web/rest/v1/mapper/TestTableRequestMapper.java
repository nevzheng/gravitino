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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.NamedReference;
import org.apache.gravitino.rel.expressions.distributions.Strategy;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.sorts.NullOrdering;
import org.apache.gravitino.rel.expressions.sorts.SortDirection;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.v1.model.Column;
import org.apache.gravitino.rest.v1.model.DataType;
import org.apache.gravitino.rest.v1.model.Distribution;
import org.apache.gravitino.rest.v1.model.Expression;
import org.apache.gravitino.rest.v1.model.PartitionAssignment;
import org.apache.gravitino.rest.v1.model.SortOrder;
import org.apache.gravitino.rest.v1.model.Transform;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.junit.jupiter.api.Test;

/** Tests V1 public table request mapping at the wire-to-domain boundary. */
public class TestTableRequestMapper {

  @Test
  public void testMapsEveryV1DataTypeInColumns() {
    List<DataType> types =
        Arrays.asList(
            new DataType.BooleanType(),
            new DataType.ByteType(true),
            new DataType.ByteType(false),
            new DataType.ShortType(true),
            new DataType.ShortType(false),
            new DataType.IntegerType(true),
            new DataType.IntegerType(false),
            new DataType.LongType(true),
            new DataType.LongType(false),
            new DataType.FloatType(),
            new DataType.DoubleType(),
            new DataType.DecimalType(18, 4),
            new DataType.DateType(),
            new DataType.TimeType(null),
            new DataType.TimeType(6),
            new DataType.TimestampType(false, null),
            new DataType.TimestampType(true, 9),
            new DataType.IntervalYearType(),
            new DataType.IntervalDayType(),
            new DataType.StringType(),
            new DataType.VarCharType(32),
            new DataType.FixedCharType(8),
            new DataType.UuidType(),
            new DataType.FixedType(16),
            new DataType.BinaryType(),
            new DataType.VariantType(),
            new DataType.StructType(
                Collections.singletonList(
                    new DataType.StructField(
                        "nested", new DataType.StringType(), true, "nested comment"))),
            new DataType.ListType(new DataType.LongType(true), false),
            new DataType.MapType(new DataType.StringType(), new DataType.IntegerType(true), true),
            new DataType.UnionType(
                Arrays.asList(new DataType.StringType(), new DataType.IntegerType(true))),
            new DataType.NullType(),
            new DataType.UnparsedType("geography"),
            new DataType.ExternalType("GEOMETRY(4326)"));
    List<Column> columns =
        types.stream()
            .map(type -> new Column("column_" + type.getKind(), type, null, true, false, null))
            .collect(Collectors.toList());

    org.apache.gravitino.rel.Column[] mapped = TableRequestMapper.toColumns(columns);

    Set<Type.Name> mappedKinds =
        Arrays.stream(mapped).map(column -> column.dataType().name()).collect(Collectors.toSet());
    assertEquals(EnumSet.allOf(Type.Name.class), mappedKinds);
    assertTrue(
        Arrays.stream(mapped)
            .filter(column -> column.dataType().name() == Type.Name.INTEGER)
            .map(column -> (Types.IntegerType) column.dataType())
            .anyMatch(type -> !type.signed()));
    Types.StructType struct =
        (Types.StructType)
            Arrays.stream(mapped)
                .filter(column -> column.dataType().name() == Type.Name.STRUCT)
                .findFirst()
                .orElseThrow(AssertionError::new)
                .dataType();
    assertEquals("nested", struct.fields()[0].name());
    assertEquals("nested comment", struct.fields()[0].comment());
  }

  @Test
  public void testMapsColumnsExpressionsAndPhysicalTableDefinitions() {
    List<Column> columns =
        Arrays.asList(
            new Column(
                "id",
                new DataType.IntegerType(true),
                "primary key",
                false,
                true,
                new Expression.Literal(7, new DataType.IntegerType(true))),
            new Column(
                "derived",
                new DataType.StringType(),
                null,
                true,
                false,
                new Expression.Apply(
                    Expression.FunctionReference.named("concat"),
                    Arrays.asList(
                        Expression.Reference.named("source"),
                        new Expression.Literal("suffix", new DataType.StringType())))));

    org.apache.gravitino.rel.Column[] mappedColumns = TableRequestMapper.toColumns(columns);
    Literal<?> literal = assertInstanceOf(Literal.class, mappedColumns[0].defaultValue());
    assertEquals(7, ((Number) literal.value()).intValue());
    FunctionExpression expression =
        assertInstanceOf(FunctionExpression.class, mappedColumns[1].defaultValue());
    assertEquals("concat", expression.functionName());
    assertEquals("source", ((NamedReference) expression.arguments()[0]).fieldName()[0]);

    Transform.ListTransform listTransform =
        new Transform.ListTransform(
            Collections.singletonList(Collections.singletonList("country")),
            Collections.singletonList(
                new PartitionAssignment.ListAssignment(
                    "west",
                    Collections.singletonMap("location", "west"),
                    Collections.singletonList(
                        Arrays.asList(
                            new Expression.Literal("US", new DataType.StringType()),
                            new Expression.Literal("CA", new DataType.StringType()))))));
    Transform.Range rangeTransform =
        new Transform.Range(
            Collections.singletonList("id"),
            Collections.singletonList(
                new PartitionAssignment.Range(
                    "recent",
                    Collections.singletonMap("location", "recent"),
                    new Expression.Literal(1, new DataType.IntegerType(true)),
                    new Expression.Literal(100, new DataType.IntegerType(true)))));
    List<Transform> transforms =
        Arrays.asList(
            new Transform.Identity(Collections.singletonList("id")),
            new Transform.Year(Collections.singletonList("created_at")),
            new Transform.Month(Collections.singletonList("created_at")),
            new Transform.Day(Collections.singletonList("created_at")),
            new Transform.Hour(Collections.singletonList("created_at")),
            new Transform.Bucket(
                16,
                Arrays.asList(
                    Collections.singletonList("id"), Collections.singletonList("tenant"))),
            new Transform.Truncate(8, Collections.singletonList("name")),
            listTransform,
            rangeTransform,
            new Transform.Apply(
                "custom_transform",
                Arrays.asList(
                    Expression.Reference.named("id"),
                    new Expression.Literal(2, new DataType.IntegerType(true)))));

    org.apache.gravitino.rel.expressions.transforms.Transform[] mappedTransforms =
        TableRequestMapper.toTransforms(transforms);
    assertInstanceOf(Transforms.IdentityTransform.class, mappedTransforms[0]);
    assertInstanceOf(Transforms.YearTransform.class, mappedTransforms[1]);
    assertInstanceOf(Transforms.MonthTransform.class, mappedTransforms[2]);
    assertInstanceOf(Transforms.DayTransform.class, mappedTransforms[3]);
    assertInstanceOf(Transforms.HourTransform.class, mappedTransforms[4]);
    assertInstanceOf(Transforms.BucketTransform.class, mappedTransforms[5]);
    assertInstanceOf(Transforms.TruncateTransform.class, mappedTransforms[6]);
    assertEquals(1, mappedTransforms[7].assignments().length);
    assertEquals(1, mappedTransforms[8].assignments().length);
    assertInstanceOf(Transforms.ApplyTransform.class, mappedTransforms[9]);

    org.apache.gravitino.rel.expressions.distributions.Distribution distribution =
        TableRequestMapper.toDistribution(
            new Distribution(
                Distribution.Strategy.HASH,
                8,
                Collections.singletonList(Expression.Reference.named("id"))));
    assertEquals(Strategy.HASH, distribution.strategy());
    assertEquals(8, distribution.number());
    assertEquals("id", ((NamedReference) distribution.expressions()[0]).fieldName()[0]);

    org.apache.gravitino.rel.expressions.sorts.SortOrder[] sortOrders =
        TableRequestMapper.toSortOrders(
            Collections.singletonList(
                new SortOrder(
                    Expression.Reference.named("id"),
                    SortOrder.Direction.DESC,
                    SortOrder.NullOrdering.NULLS_FIRST)));
    assertEquals(SortDirection.DESCENDING, sortOrders[0].direction());
    assertEquals(NullOrdering.NULLS_FIRST, sortOrders[0].nullOrdering());

    org.apache.gravitino.rel.indexes.Index[] indexes =
        TableRequestMapper.toIndexes(
            Collections.singletonList(
                new org.apache.gravitino.rest.v1.model.Index(
                    org.apache.gravitino.rest.v1.model.Index.Type.PRIMARY_KEY,
                    "pk_orders",
                    Collections.singletonList(Collections.singletonList("id")),
                    Collections.singletonMap("enforced", "true"))));
    assertEquals(Index.IndexType.PRIMARY_KEY, indexes[0].type());
    assertArrayEquals(new String[] {"id"}, indexes[0].fieldNames()[0]);
    assertEquals("true", indexes[0].properties().get("enforced"));
  }

  @Test
  public void testMapsNullAndUnsignedLiteralsWithoutLosingTheirDeclaredType() {
    List<Column> columns =
        Arrays.asList(
            new Column(
                "empty",
                new DataType.NullType(),
                null,
                true,
                false,
                new Expression.Literal(null, new DataType.NullType())),
            new Column(
                "unsigned_byte",
                new DataType.ByteType(false),
                null,
                false,
                false,
                new Expression.Literal(255, new DataType.ByteType(false))),
            new Column(
                "unsigned_long",
                new DataType.LongType(false),
                null,
                false,
                false,
                new Expression.Literal("18446744073709551615", new DataType.LongType(false))),
            new Column(
                "binary",
                new DataType.BinaryType(),
                null,
                false,
                false,
                new Expression.Literal("0001ff", new DataType.BinaryType())));

    org.apache.gravitino.rel.Column[] mapped = TableRequestMapper.toColumns(columns);

    Literal<?> nullLiteral = assertInstanceOf(Literal.class, mapped[0].defaultValue());
    Literal<?> unsignedByte = assertInstanceOf(Literal.class, mapped[1].defaultValue());
    Literal<?> unsignedLong = assertInstanceOf(Literal.class, mapped[2].defaultValue());
    Literal<?> binary = assertInstanceOf(Literal.class, mapped[3].defaultValue());
    assertEquals(Type.Name.NULL, nullLiteral.dataType().name());
    assertEquals(255, ((Number) unsignedByte.value()).intValue());
    assertEquals(Type.Name.LONG, unsignedLong.dataType().name());
    assertArrayEquals(new byte[] {0, 1, (byte) 0xff}, (byte[]) binary.value());
  }

  @Test
  public void testRejectsWireFormsTheInternalDomainCannotRepresent() {
    Column boundReference =
        new Column(
            "bound",
            new DataType.IntegerType(true),
            null,
            true,
            false,
            Expression.Reference.bound(3));
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableRequestMapper.toColumns(Collections.singletonList(boundReference)));

    Column qualifiedFunction =
        new Column(
            "qualified",
            new DataType.StringType(),
            null,
            true,
            false,
            new Expression.Apply(
                Expression.FunctionReference.qualified(
                    "catalog", Collections.singletonList("lower")),
                Collections.singletonList(Expression.Reference.named("name"))));
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableRequestMapper.toColumns(Collections.singletonList(qualifiedFunction)));

    Column structuredLiteral =
        new Column(
            "items",
            new DataType.ListType(new DataType.IntegerType(true), false),
            null,
            true,
            false,
            new Expression.Literal(
                Arrays.asList(1, 2), new DataType.ListType(new DataType.IntegerType(true), false)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableRequestMapper.toColumns(Collections.singletonList(structuredLiteral)));

    Column invalidFixed =
        new Column(
            "hash",
            new DataType.FixedType(2),
            null,
            true,
            false,
            new Expression.Literal("ABC", new DataType.FixedType(2)));
    assertThrows(
        V1ClientInputException.class,
        () -> TableRequestMapper.toColumns(Collections.singletonList(invalidFixed)));
  }
}
