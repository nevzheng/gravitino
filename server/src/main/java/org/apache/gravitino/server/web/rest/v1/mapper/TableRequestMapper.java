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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.NamedReference;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.distributions.Strategy;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.NullOrdering;
import org.apache.gravitino.rel.expressions.sorts.SortDirection;
import org.apache.gravitino.rel.expressions.sorts.SortOrders;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.v1.model.Column;
import org.apache.gravitino.rest.v1.model.DataType;
import org.apache.gravitino.rest.v1.model.Distribution;
import org.apache.gravitino.rest.v1.model.ExpressionNode;
import org.apache.gravitino.rest.v1.model.Index;
import org.apache.gravitino.rest.v1.model.LiteralValue;
import org.apache.gravitino.rest.v1.model.PartitionAssignment;
import org.apache.gravitino.rest.v1.model.SortOrder;
import org.apache.gravitino.rest.v1.model.Transform;
import org.apache.gravitino.rest.v1.model.ValueExpression;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;

/**
 * Maps V1 public table request models to Gravitino's internal table domain.
 *
 * <p>The mapper is deliberately one-way: V1 handlers translate wire objects here before calling a
 * dispatcher, while {@link TableMapper} translates dispatcher results back into the V1 wire
 * representation. Unsupported wire forms are rejected explicitly rather than silently dropping
 * client intent.
 */
public final class TableRequestMapper {

  private static final BigInteger SIGNED_BYTE_MINIMUM = BigInteger.valueOf(Byte.MIN_VALUE);
  private static final BigInteger SIGNED_BYTE_MAXIMUM = BigInteger.valueOf(Byte.MAX_VALUE);
  private static final BigInteger UNSIGNED_BYTE_MAXIMUM = BigInteger.valueOf(255);
  private static final BigInteger SIGNED_SHORT_MINIMUM = BigInteger.valueOf(Short.MIN_VALUE);
  private static final BigInteger SIGNED_SHORT_MAXIMUM = BigInteger.valueOf(Short.MAX_VALUE);
  private static final BigInteger UNSIGNED_SHORT_MAXIMUM = BigInteger.valueOf(65_535);
  private static final BigInteger SIGNED_INTEGER_MINIMUM = BigInteger.valueOf(Integer.MIN_VALUE);
  private static final BigInteger SIGNED_INTEGER_MAXIMUM = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger UNSIGNED_INTEGER_MAXIMUM = new BigInteger("4294967295");
  private static final BigInteger SIGNED_LONG_MINIMUM = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger SIGNED_LONG_MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger UNSIGNED_LONG_MAXIMUM = new BigInteger("18446744073709551615");

  /**
   * Converts public V1 columns to internal Gravitino columns.
   *
   * @param columns public columns in request order.
   * @return internal columns in request order.
   * @throws V1ClientInputException if a column cannot be represented safely.
   */
  public static org.apache.gravitino.rel.Column[] toColumns(List<Column> columns) {
    requireNonNull(columns, "columns", "Must be present.");
    try {
      org.apache.gravitino.rel.Column[] mapped =
          new org.apache.gravitino.rel.Column[columns.size()];
      for (int index = 0; index < columns.size(); index++) {
        Column column = columns.get(index);
        if (column == null) {
          throw invalid("columns", "Must not contain null values.");
        }
        Expression defaultValue =
            column.getDefaultValue() == null
                ? org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET
                : toExpression(column.getDefaultValue(), "columns[" + index + "].defaultValue");
        mapped[index] =
            org.apache.gravitino.rel.Column.of(
                column.getName(),
                toDataType(column.getType(), "columns[" + index + "].type"),
                column.getComment(),
                column.isNullable(),
                column.isAutoIncrement(),
                defaultValue);
      }
      return mapped;
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid("columns", "Contains an invalid column definition.");
    }
  }

  /**
   * Converts public V1 partition transforms to internal Gravitino transforms.
   *
   * @param transforms public physical partition transforms in request order.
   * @return internal physical partition transforms in request order.
   * @throws V1ClientInputException if a transform cannot be represented safely.
   */
  public static org.apache.gravitino.rel.expressions.transforms.Transform[] toTransforms(
      List<Transform> transforms) {
    requireNonNull(transforms, "partitioning", "Must be present.");
    try {
      org.apache.gravitino.rel.expressions.transforms.Transform[] mapped =
          new org.apache.gravitino.rel.expressions.transforms.Transform[transforms.size()];
      for (int index = 0; index < transforms.size(); index++) {
        mapped[index] = toTransform(transforms.get(index), "partitioning[" + index + "]");
      }
      return mapped;
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid("partitioning", "Contains an invalid partition transform.");
    }
  }

  /**
   * Converts an optional V1 distribution to an internal Gravitino distribution.
   *
   * @param distribution optional public physical data distribution.
   * @return an internal distribution, or null when no distribution was requested.
   * @throws V1ClientInputException if a distribution cannot be represented safely.
   */
  @Nullable
  public static org.apache.gravitino.rel.expressions.distributions.Distribution toDistribution(
      @Nullable Distribution distribution) {
    if (distribution == null) {
      return null;
    }
    try {
      Expression[] expressions =
          toExpressions(distribution.getExpressions(), "distribution.expressions");
      if (distribution.getStrategy() == Distribution.Strategy.NONE) {
        if (distribution.getBucketCount() != null || expressions.length != 0) {
          throw invalid(
              "distribution",
              "NONE distribution must not declare a bucket count or distribution expressions.");
        }
        return Distributions.NONE;
      }

      Strategy strategy = toDistributionStrategy(distribution.getStrategy());
      Integer bucketCount = distribution.getBucketCount();
      return bucketCount == null
          ? Distributions.auto(strategy, expressions)
          : Distributions.of(strategy, bucketCount, expressions);
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid("distribution", "Contains an invalid distribution definition.");
    }
  }

  /**
   * Converts public V1 sort orders to internal Gravitino sort orders.
   *
   * @param sortOrders public sort orders in request order.
   * @return internal sort orders in request order.
   * @throws V1ClientInputException if a sort order cannot be represented safely.
   */
  public static org.apache.gravitino.rel.expressions.sorts.SortOrder[] toSortOrders(
      List<SortOrder> sortOrders) {
    requireNonNull(sortOrders, "sortOrders", "Must be present.");
    try {
      org.apache.gravitino.rel.expressions.sorts.SortOrder[] mapped =
          new org.apache.gravitino.rel.expressions.sorts.SortOrder[sortOrders.size()];
      for (int index = 0; index < sortOrders.size(); index++) {
        SortOrder sortOrder = sortOrders.get(index);
        if (sortOrder == null) {
          throw invalid("sortOrders", "Must not contain null values.");
        }
        mapped[index] =
            SortOrders.of(
                toExpression(sortOrder.getExpression(), "sortOrders[" + index + "].expression"),
                toSortDirection(sortOrder.getDirection()),
                toNullOrdering(sortOrder.getNullOrdering()));
      }
      return mapped;
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid("sortOrders", "Contains an invalid sort order.");
    }
  }

  /**
   * Converts public V1 indexes to internal Gravitino indexes.
   *
   * @param indexes public indexes in request order.
   * @return internal indexes in request order.
   * @throws V1ClientInputException if an index cannot be represented safely.
   */
  public static org.apache.gravitino.rel.indexes.Index[] toIndexes(List<Index> indexes) {
    requireNonNull(indexes, "indexes", "Must be present.");
    try {
      org.apache.gravitino.rel.indexes.Index[] mapped =
          new org.apache.gravitino.rel.indexes.Index[indexes.size()];
      for (int index = 0; index < indexes.size(); index++) {
        Index value = indexes.get(index);
        if (value == null) {
          throw invalid("indexes", "Must not contain null values.");
        }
        mapped[index] =
            Indexes.of(
                toIndexType(value.getType()),
                value.getName(),
                toFieldNames(value.getFieldNames(), "indexes[" + index + "].fieldNames"),
                value.getProperties());
      }
      return mapped;
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid("indexes", "Contains an invalid index definition.");
    }
  }

  private static org.apache.gravitino.rel.expressions.transforms.Transform toTransform(
      @Nullable Transform transform, String field) {
    if (transform == null) {
      throw invalid(field, "Must not be null.");
    }
    if (transform instanceof Transform.Identity) {
      return Transforms.identity(
          toFieldName(((Transform.Identity) transform).getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.Year) {
      return Transforms.year(
          toFieldName(((Transform.Year) transform).getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.Month) {
      return Transforms.month(
          toFieldName(((Transform.Month) transform).getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.Day) {
      return Transforms.day(
          toFieldName(((Transform.Day) transform).getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.Hour) {
      return Transforms.hour(
          toFieldName(((Transform.Hour) transform).getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.Bucket) {
      Transform.Bucket bucket = (Transform.Bucket) transform;
      return Transforms.bucket(
          bucket.getNumBuckets(), toFieldNames(bucket.getFieldNames(), field + ".fieldNames"));
    }
    if (transform instanceof Transform.Truncate) {
      Transform.Truncate truncate = (Transform.Truncate) transform;
      return Transforms.truncate(
          truncate.getWidth(), toFieldName(truncate.getFieldName(), field + ".fieldName"));
    }
    if (transform instanceof Transform.ListTransform) {
      Transform.ListTransform list = (Transform.ListTransform) transform;
      return Transforms.list(
          toFieldNames(list.getFieldNames(), field + ".fieldNames"),
          toListPartitions(list.getAssignments(), field + ".assignments"));
    }
    if (transform instanceof Transform.Range) {
      Transform.Range range = (Transform.Range) transform;
      return Transforms.range(
          toFieldName(range.getFieldName(), field + ".fieldName"),
          toRangePartitions(range.getAssignments(), field + ".assignments"));
    }
    if (transform instanceof Transform.Apply) {
      Transform.Apply apply = (Transform.Apply) transform;
      return Transforms.apply(
          apply.getName(), toExpressions(apply.getArguments(), field + ".arguments"));
    }
    throw unsupported("This partition transform is not supported by the internal API.");
  }

  private static ListPartition[] toListPartitions(
      List<PartitionAssignment> assignments, String field) {
    requireNonNull(assignments, field, "Must be present.");
    ListPartition[] mapped = new ListPartition[assignments.size()];
    for (int index = 0; index < assignments.size(); index++) {
      PartitionAssignment assignment = assignments.get(index);
      if (!(assignment instanceof PartitionAssignment.ListAssignment)) {
        throw invalid(field, "LIST transforms require LIST partition assignments.");
      }
      PartitionAssignment.ListAssignment list = (PartitionAssignment.ListAssignment) assignment;
      List<List<org.apache.gravitino.rest.v1.model.Expression.Literal>> tuples = list.getValues();
      Literal<?>[][] values = new Literal<?>[tuples.size()][];
      for (int tupleIndex = 0; tupleIndex < tuples.size(); tupleIndex++) {
        List<org.apache.gravitino.rest.v1.model.Expression.Literal> tuple = tuples.get(tupleIndex);
        requireNonNull(tuple, field, "Must not contain null tuples.");
        values[tupleIndex] = new Literal<?>[tuple.size()];
        for (int valueIndex = 0; valueIndex < tuple.size(); valueIndex++) {
          values[tupleIndex][valueIndex] =
              toLiteral(tuple.get(valueIndex), field + "[" + index + "].values");
        }
      }
      mapped[index] = Partitions.list(list.getName(), values, list.getProperties());
    }
    return mapped;
  }

  private static RangePartition[] toRangePartitions(
      List<PartitionAssignment> assignments, String field) {
    requireNonNull(assignments, field, "Must be present.");
    RangePartition[] mapped = new RangePartition[assignments.size()];
    for (int index = 0; index < assignments.size(); index++) {
      PartitionAssignment assignment = assignments.get(index);
      if (!(assignment instanceof PartitionAssignment.Range)) {
        throw invalid(field, "RANGE transforms require RANGE partition assignments.");
      }
      PartitionAssignment.Range range = (PartitionAssignment.Range) assignment;
      Literal<?> upper =
          range.getUpper() == null
              ? null
              : toLiteral(range.getUpper(), field + "[" + index + "].upper");
      Literal<?> lower =
          range.getLower() == null
              ? null
              : toLiteral(range.getLower(), field + "[" + index + "].lower");
      mapped[index] = Partitions.range(range.getName(), upper, lower, range.getProperties());
    }
    return mapped;
  }

  private static Expression[] toExpressions(
      List<? extends ValueExpression> expressions, String field) {
    requireNonNull(expressions, field, "Must be present.");
    Expression[] mapped = new Expression[expressions.size()];
    for (int index = 0; index < expressions.size(); index++) {
      mapped[index] = toExpression(expressions.get(index), field + "[" + index + "]");
    }
    return mapped;
  }

  private static Expression toExpression(@Nullable ValueExpression expression, String field) {
    if (expression == null) {
      throw invalid(field, "Must not be null.");
    }
    if (expression instanceof LiteralValue) {
      return toInferredLiteral(((LiteralValue) expression).getValue(), field);
    }
    if (expression instanceof org.apache.gravitino.rest.v1.model.Expression.Literal) {
      return toLiteral((org.apache.gravitino.rest.v1.model.Expression.Literal) expression, field);
    }
    if (expression instanceof org.apache.gravitino.rest.v1.model.Expression.Reference) {
      org.apache.gravitino.rest.v1.model.Expression.Reference reference =
          (org.apache.gravitino.rest.v1.model.Expression.Reference) expression;
      if (reference.getId() != null) {
        throw unsupported(
            "Bound field IDs cannot be represented by the internal table API without schema"
                + " binding.");
      }
      if (reference.getName() == null) {
        throw invalid(field, "A reference must contain a name.");
      }
      return NamedReference.field(reference.getName());
    }
    if (expression instanceof org.apache.gravitino.rest.v1.model.Expression.Apply) {
      org.apache.gravitino.rest.v1.model.Expression.Apply apply =
          (org.apache.gravitino.rest.v1.model.Expression.Apply) expression;
      org.apache.gravitino.rest.v1.model.Expression.FunctionReference function =
          apply.getFunction();
      if (function.getCatalog() != null || function.getIdentifier().size() != 1) {
        throw unsupported(
            "Qualified or multi-part function references cannot be represented by the internal"
                + " table API.");
      }
      Expression[] arguments = new Expression[apply.getArguments().size()];
      for (int index = 0; index < apply.getArguments().size(); index++) {
        ExpressionNode argument = apply.getArguments().get(index);
        if (!(argument instanceof ValueExpression)) {
          throw unsupported("Predicate arguments cannot be represented by the internal table API.");
        }
        arguments[index] =
            toExpression((ValueExpression) argument, field + ".arguments[" + index + "]");
      }
      return FunctionExpression.of(function.getIdentifier().get(0), arguments);
    }
    throw unsupported("This value expression is not supported by the internal table API.");
  }

  private static Literal<?> toLiteral(
      @Nullable org.apache.gravitino.rest.v1.model.Expression.Literal literal, String field) {
    if (literal == null) {
      throw invalid(field, "Must not be null.");
    }
    if (literal.getDataType() == null) {
      return toInferredLiteral(literal.getValue(), field);
    }
    DataType dataType = literal.getDataType();
    if (dataType.getKind() == DataType.Kind.NULL) {
      if (literal.getValue() != null) {
        throw invalid(field, "A NULL literal must contain a null value.");
      }
      return Literals.NULL;
    }
    if (literal.getValue() == null) {
      throw invalid(field, "A non-NULL literal must contain a value.");
    }
    Type internalType = toDataType(dataType, field + ".data-type");
    return Literals.of(toLiteralValue(literal.getValue(), dataType, field), internalType);
  }

  private static Literal<?> toInferredLiteral(@Nullable Object value, String field) {
    if (value == null) {
      return Literals.NULL;
    }
    if (value instanceof Boolean) {
      return Literals.booleanLiteral((Boolean) value);
    }
    if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
      return Literals.integerLiteral(((Number) value).intValue());
    }
    if (value instanceof Long) {
      return Literals.longLiteral((Long) value);
    }
    if (value instanceof Float || value instanceof Double) {
      double numericValue = ((Number) value).doubleValue();
      if (!Double.isFinite(numericValue)) {
        throw invalid(field, "Floating-point literal values must be finite.");
      }
      return Literals.doubleLiteral(numericValue);
    }
    if (value instanceof String) {
      return Literals.stringLiteral((String) value);
    }
    throw unsupported(
        "Structured and high-precision literal values require an explicit data-type declaration.");
  }

  private static Object toLiteralValue(Object value, DataType dataType, String field) {
    try {
      switch (dataType.getKind()) {
        case BOOLEAN:
          if (!(value instanceof Boolean)) {
            throw invalid(field, "A BOOLEAN literal must use a JSON boolean value.");
          }
          return value;
        case BYTE:
          return toByteValue(value, (DataType.IntegralType) dataType, field);
        case SHORT:
          return toShortValue(value, (DataType.IntegralType) dataType, field);
        case INTEGER:
          return toIntegerValue(value, (DataType.IntegralType) dataType, field);
        case LONG:
          return toLongValue(value, (DataType.IntegralType) dataType, field);
        case FLOAT:
          return toFiniteFloat(value, field);
        case DOUBLE:
          return toFiniteDouble(value, field);
        case DECIMAL:
          DataType.DecimalType decimalType = (DataType.DecimalType) dataType;
          return toDecimal(value, decimalType.getPrecision(), decimalType.getScale(), field);
        case DATE:
          return LocalDate.parse(requireString(value, field));
        case TIME:
          return LocalTime.parse(requireString(value, field));
        case TIMESTAMP:
          DataType.TimestampType timestampType = (DataType.TimestampType) dataType;
          return timestampType.isWithTimeZone()
              ? OffsetDateTime.parse(requireString(value, field))
              : LocalDateTime.parse(requireString(value, field));
        case INTERVAL_YEAR:
          return Period.parse(requireString(value, field));
        case INTERVAL_DAY:
          return Duration.parse(requireString(value, field));
        case STRING:
        case VARCHAR:
        case FIXEDCHAR:
        case UNPARSED:
        case EXTERNAL:
          return requireString(value, field);
        case UUID:
          return UUID.fromString(requireString(value, field));
        case FIXED:
          DataType.FixedType fixedType = (DataType.FixedType) dataType;
          return decodeHex(requireString(value, field), fixedType.getLength(), field);
        case BINARY:
          return decodeHex(requireString(value, field), null, field);
        case VARIANT:
          return toScalarVariant(value);
        case STRUCT:
        case LIST:
        case MAP:
        case UNION:
          throw unsupported(
              "Structured literal values cannot yet be represented losslessly by the internal table"
                  + " API.");
        case NULL:
          throw invalid(field, "A NULL literal must contain a null value.");
        default:
          throw unsupported("This literal data type is not supported by the internal table API.");
      }
    } catch (V1ClientInputException | UnsupportedOperationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field, "Literal value is not valid for its declared data-type.");
    }
  }

  private static Object toScalarVariant(Object value) {
    if (value instanceof String || value instanceof Boolean || value instanceof Number) {
      return value;
    }
    throw unsupported(
        "Structured VARIANT literal values are not supported by the internal table API.");
  }

  private static Object toByteValue(Object value, DataType.IntegralType dataType, String field) {
    BigInteger integer =
        checkedIntegral(
            value,
            dataType.isSigned() ? SIGNED_BYTE_MINIMUM : BigInteger.ZERO,
            dataType.isSigned() ? SIGNED_BYTE_MAXIMUM : UNSIGNED_BYTE_MAXIMUM,
            field);
    return dataType.isSigned() ? integer.byteValueExact() : integer.shortValueExact();
  }

  private static Object toShortValue(Object value, DataType.IntegralType dataType, String field) {
    BigInteger integer =
        checkedIntegral(
            value,
            dataType.isSigned() ? SIGNED_SHORT_MINIMUM : BigInteger.ZERO,
            dataType.isSigned() ? SIGNED_SHORT_MAXIMUM : UNSIGNED_SHORT_MAXIMUM,
            field);
    return dataType.isSigned() ? integer.shortValueExact() : integer.intValueExact();
  }

  private static Object toIntegerValue(Object value, DataType.IntegralType dataType, String field) {
    BigInteger integer =
        checkedIntegral(
            value,
            dataType.isSigned() ? SIGNED_INTEGER_MINIMUM : BigInteger.ZERO,
            dataType.isSigned() ? SIGNED_INTEGER_MAXIMUM : UNSIGNED_INTEGER_MAXIMUM,
            field);
    return dataType.isSigned() ? integer.intValueExact() : integer.longValueExact();
  }

  private static Object toLongValue(Object value, DataType.IntegralType dataType, String field) {
    BigInteger integer =
        checkedIntegral(
            value,
            dataType.isSigned() ? SIGNED_LONG_MINIMUM : BigInteger.ZERO,
            dataType.isSigned() ? SIGNED_LONG_MAXIMUM : UNSIGNED_LONG_MAXIMUM,
            field);
    return dataType.isSigned() ? integer.longValueExact() : Decimal.of(new BigDecimal(integer));
  }

  private static BigInteger checkedIntegral(
      Object value, BigInteger minimum, BigInteger maximum, String field) {
    BigInteger integer;
    try {
      if (!(value instanceof Number) && !(value instanceof String)) {
        throw invalid(
            field, "An integral literal must use a JSON number or canonical decimal string.");
      }
      integer = new BigDecimal(value.toString()).toBigIntegerExact();
    } catch (V1ClientInputException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field, "An integral literal must contain an integer value.");
    }
    if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
      throw invalid(field, "Integral literal value is outside its declared data-type range.");
    }
    return integer;
  }

  private static Float toFiniteFloat(Object value, String field) {
    try {
      if (!(value instanceof Number) && !(value instanceof String)) {
        throw invalid(field, "A FLOAT literal must use a JSON number or canonical decimal string.");
      }
      float parsed = Float.parseFloat(value.toString());
      if (!Float.isFinite(parsed)) {
        throw invalid(field, "Floating-point literal values must be finite.");
      }
      return parsed;
    } catch (V1ClientInputException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field, "Literal value is not valid for FLOAT.");
    }
  }

  private static Double toFiniteDouble(Object value, String field) {
    try {
      if (!(value instanceof Number) && !(value instanceof String)) {
        throw invalid(
            field, "A DOUBLE literal must use a JSON number or canonical decimal string.");
      }
      double parsed = Double.parseDouble(value.toString());
      if (!Double.isFinite(parsed)) {
        throw invalid(field, "Floating-point literal values must be finite.");
      }
      return parsed;
    } catch (V1ClientInputException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field, "Literal value is not valid for DOUBLE.");
    }
  }

  private static Decimal toDecimal(Object value, int precision, int scale, String field) {
    try {
      if (!(value instanceof Number) && !(value instanceof String)) {
        throw invalid(
            field, "A DECIMAL literal must use a JSON number or canonical decimal string.");
      }
      BigDecimal decimal = new BigDecimal(value.toString());
      BigDecimal normalized = decimal.setScale(scale, RoundingMode.UNNECESSARY);
      if (normalized.precision() > precision) {
        throw invalid(field, "DECIMAL literal value exceeds its declared precision.");
      }
      return Decimal.of(normalized, precision, scale);
    } catch (V1ClientInputException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field, "Literal value is not valid for DECIMAL.");
    }
  }

  private static byte[] decodeHex(String value, @Nullable Integer fixedLength, String field) {
    if (!value.matches("(?:[0-9a-f]{2})*")) {
      throw invalid(field, "FIXED and BINARY literals must use lowercase hexadecimal.");
    }
    if (fixedLength != null && value.length() != fixedLength * 2) {
      throw invalid(field, "FIXED literal length must match its declared data-type length.");
    }
    byte[] bytes = new byte[value.length() / 2];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
    }
    return bytes;
  }

  private static Type toDataType(@Nullable DataType dataType, String field) {
    if (dataType == null) {
      throw invalid(field, "Must be present.");
    }
    try {
      if (dataType instanceof DataType.BooleanType) {
        return Types.BooleanType.get();
      }
      if (dataType instanceof DataType.ByteType) {
        return ((DataType.ByteType) dataType).isSigned()
            ? Types.ByteType.get()
            : Types.ByteType.unsigned();
      }
      if (dataType instanceof DataType.ShortType) {
        return ((DataType.ShortType) dataType).isSigned()
            ? Types.ShortType.get()
            : Types.ShortType.unsigned();
      }
      if (dataType instanceof DataType.IntegerType) {
        return ((DataType.IntegerType) dataType).isSigned()
            ? Types.IntegerType.get()
            : Types.IntegerType.unsigned();
      }
      if (dataType instanceof DataType.LongType) {
        return ((DataType.LongType) dataType).isSigned()
            ? Types.LongType.get()
            : Types.LongType.unsigned();
      }
      if (dataType instanceof DataType.FloatType) {
        return Types.FloatType.get();
      }
      if (dataType instanceof DataType.DoubleType) {
        return Types.DoubleType.get();
      }
      if (dataType instanceof DataType.DecimalType) {
        DataType.DecimalType decimal = (DataType.DecimalType) dataType;
        return Types.DecimalType.of(decimal.getPrecision(), decimal.getScale());
      }
      if (dataType instanceof DataType.DateType) {
        return Types.DateType.get();
      }
      if (dataType instanceof DataType.TimeType) {
        Integer precision = ((DataType.TimeType) dataType).getPrecision();
        return precision == null ? Types.TimeType.get() : Types.TimeType.of(precision);
      }
      if (dataType instanceof DataType.TimestampType) {
        DataType.TimestampType timestamp = (DataType.TimestampType) dataType;
        if (timestamp.getPrecision() == null) {
          return timestamp.isWithTimeZone()
              ? Types.TimestampType.withTimeZone()
              : Types.TimestampType.withoutTimeZone();
        }
        return timestamp.isWithTimeZone()
            ? Types.TimestampType.withTimeZone(timestamp.getPrecision())
            : Types.TimestampType.withoutTimeZone(timestamp.getPrecision());
      }
      if (dataType instanceof DataType.IntervalYearType) {
        return Types.IntervalYearType.get();
      }
      if (dataType instanceof DataType.IntervalDayType) {
        return Types.IntervalDayType.get();
      }
      if (dataType instanceof DataType.StringType) {
        return Types.StringType.get();
      }
      if (dataType instanceof DataType.VarCharType) {
        return Types.VarCharType.of(((DataType.VarCharType) dataType).getLength());
      }
      if (dataType instanceof DataType.FixedCharType) {
        return Types.FixedCharType.of(((DataType.FixedCharType) dataType).getLength());
      }
      if (dataType instanceof DataType.UuidType) {
        return Types.UUIDType.get();
      }
      if (dataType instanceof DataType.FixedType) {
        return Types.FixedType.of(((DataType.FixedType) dataType).getLength());
      }
      if (dataType instanceof DataType.BinaryType) {
        return Types.BinaryType.get();
      }
      if (dataType instanceof DataType.VariantType) {
        return Types.VariantType.get();
      }
      if (dataType instanceof DataType.StructType) {
        return toStructType((DataType.StructType) dataType, field);
      }
      if (dataType instanceof DataType.ListType) {
        DataType.ListType list = (DataType.ListType) dataType;
        return Types.ListType.of(
            toDataType(list.getElementType(), field + ".elementType"), list.isElementNullable());
      }
      if (dataType instanceof DataType.MapType) {
        DataType.MapType map = (DataType.MapType) dataType;
        return Types.MapType.of(
            toDataType(map.getKeyType(), field + ".keyType"),
            toDataType(map.getValueType(), field + ".valueType"),
            map.isValueNullable());
      }
      if (dataType instanceof DataType.UnionType) {
        DataType.UnionType union = (DataType.UnionType) dataType;
        Type[] memberTypes = new Type[union.getTypes().size()];
        for (int index = 0; index < memberTypes.length; index++) {
          memberTypes[index] =
              toDataType(union.getTypes().get(index), field + ".types[" + index + "]");
        }
        return Types.UnionType.of(memberTypes);
      }
      if (dataType instanceof DataType.NullType) {
        return Types.NullType.get();
      }
      if (dataType instanceof DataType.UnparsedType) {
        return Types.UnparsedType.of(((DataType.UnparsedType) dataType).getUnparsedType());
      }
      if (dataType instanceof DataType.ExternalType) {
        return Types.ExternalType.of(((DataType.ExternalType) dataType).getCatalogString());
      }
    } catch (V1ClientInputException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalid(field, "Data type is invalid or unsupported by the internal type system.");
    }
    throw unsupported("This V1 data-type variant is not supported by the internal type system.");
  }

  private static Types.StructType toStructType(DataType.StructType struct, String field) {
    List<DataType.StructField> fields = struct.getFields();
    Types.StructType.Field[] mapped = new Types.StructType.Field[fields.size()];
    for (int index = 0; index < fields.size(); index++) {
      DataType.StructField value = fields.get(index);
      if (value == null) {
        throw invalid(field, "STRUCT fields must not contain null values.");
      }
      mapped[index] =
          Types.StructType.Field.of(
              value.getName(),
              toDataType(value.getType(), field + ".fields[" + index + "].type"),
              value.isNullable(),
              value.getComment());
    }
    return Types.StructType.of(mapped);
  }

  private static Strategy toDistributionStrategy(Distribution.Strategy strategy) {
    if (strategy == null) {
      throw invalid("distribution.strategy", "Must be present.");
    }
    switch (strategy) {
      case HASH:
        return Strategy.HASH;
      case RANGE:
        return Strategy.RANGE;
      case EVEN:
        return Strategy.EVEN;
      case NONE:
        return Strategy.NONE;
      default:
        throw unsupported("This distribution strategy is not supported by the internal API.");
    }
  }

  private static SortDirection toSortDirection(SortOrder.Direction direction) {
    if (direction == SortOrder.Direction.ASC) {
      return SortDirection.ASCENDING;
    }
    if (direction == SortOrder.Direction.DESC) {
      return SortDirection.DESCENDING;
    }
    throw invalid("sortOrders.direction", "Must be ASC or DESC.");
  }

  private static NullOrdering toNullOrdering(SortOrder.NullOrdering nullOrdering) {
    if (nullOrdering == SortOrder.NullOrdering.NULLS_FIRST) {
      return NullOrdering.NULLS_FIRST;
    }
    if (nullOrdering == SortOrder.NullOrdering.NULLS_LAST) {
      return NullOrdering.NULLS_LAST;
    }
    throw invalid("sortOrders.nullOrdering", "Must be NULLS_FIRST or NULLS_LAST.");
  }

  private static org.apache.gravitino.rel.indexes.Index.IndexType toIndexType(Index.Type type) {
    if (type == null) {
      throw invalid("indexes.type", "Must be present.");
    }
    try {
      return org.apache.gravitino.rel.indexes.Index.IndexType.valueOf(type.name());
    } catch (IllegalArgumentException exception) {
      throw unsupported("This index type is not supported by the internal table API.");
    }
  }

  private static String[] toFieldName(List<String> fieldName, String field) {
    requireNonNull(fieldName, field, "Must be present.");
    if (fieldName.isEmpty()) {
      throw invalid(field, "Must contain at least one field-name part.");
    }
    String[] mapped = new String[fieldName.size()];
    for (int index = 0; index < fieldName.size(); index++) {
      String part = fieldName.get(index);
      if (part == null || part.isEmpty()) {
        throw invalid(field, "Must contain only non-empty field-name parts.");
      }
      mapped[index] = part;
    }
    return mapped;
  }

  private static String[][] toFieldNames(List<List<String>> fieldNames, String field) {
    requireNonNull(fieldNames, field, "Must be present.");
    if (fieldNames.isEmpty()) {
      throw invalid(field, "Must contain at least one field-name path.");
    }
    String[][] mapped = new String[fieldNames.size()][];
    for (int index = 0; index < fieldNames.size(); index++) {
      mapped[index] = toFieldName(fieldNames.get(index), field + "[" + index + "]");
    }
    return mapped;
  }

  private static String requireString(Object value, String field) {
    if (!(value instanceof String)) {
      throw invalid(field, "Literal value must be a string for its declared data-type.");
    }
    return (String) value;
  }

  private static void requireNonNull(@Nullable Object value, String field, String description) {
    if (value == null) {
      throw invalid(field, description);
    }
  }

  private static V1ClientInputException invalid(String field, String description) {
    return new V1ClientInputException(field, description);
  }

  private static UnsupportedOperationException unsupported(String description) {
    return new UnsupportedOperationException(description);
  }

  private TableRequestMapper() {}
}
