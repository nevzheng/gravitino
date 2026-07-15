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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Audit;
import org.apache.gravitino.dto.rel.expressions.LiteralDTO;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.NamedReference;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.distributions.Strategy;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.sorts.NullOrdering;
import org.apache.gravitino.rel.expressions.sorts.SortDirection;
import org.apache.gravitino.rel.expressions.sorts.SortOrders;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.IdentityPartition;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.Partition;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.v1.model.Column;
import org.apache.gravitino.rest.v1.model.DataType;
import org.apache.gravitino.rest.v1.model.Distribution;
import org.apache.gravitino.rest.v1.model.Expression;
import org.apache.gravitino.rest.v1.model.ExpressionNode;
import org.apache.gravitino.rest.v1.model.Index;
import org.apache.gravitino.rest.v1.model.PartitionAssignment;
import org.apache.gravitino.rest.v1.model.SortOrder;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.rest.v1.model.ValueExpression;

/** Maps internal table domain objects to the explicit public Gravitino V1 wire contract. */
public final class TableMapper {

  /**
   * Maps a table through its public accessors. This boundary intentionally does not inspect
   * connector-specific or {@code EntityCombinedTable} backing objects.
   *
   * @param resourceName canonical V1 resource name supplied by the request path.
   * @param table internal table returned by the dispatcher.
   * @return immutable V1 table resource.
   */
  public static TableResource toResource(String resourceName, Table table) {
    return toResource(resourceName, table, TableOptionsMapper.UNPROFILED_PROVIDER);
  }

  /**
   * Maps a table through its public accessors and the configured catalog provider's V1 profile.
   *
   * <p>The provider is required because legacy connector property names are not portable. In
   * particular, {@code format} means a table format for generic lakehouse but a file-format
   * description for native Iceberg.
   *
   * @param resourceName canonical V1 resource name supplied by the request path.
   * @param table internal table returned by the dispatcher.
   * @param catalogProvider configured catalog provider returned by the catalog dispatcher.
   * @return immutable V1 table resource.
   */
  public static TableResource toResource(String resourceName, Table table, String catalogProvider) {
    if (table == null) {
      throw new IllegalArgumentException("table cannot be null");
    }

    TableOptionsMapper.PublicTableState options =
        TableOptionsMapper.toPublic(catalogProvider, table.properties());

    return new TableResource(
        resourceName,
        table.name(),
        table.comment(),
        mapColumns(table.columns()),
        options.storage(),
        options.icebergOptions(),
        options.hiveOptions(),
        options.clickhouseOptions(),
        options.mysqlOptions(),
        mapTransforms(table.partitioning()),
        mapDistribution(table.distribution()),
        mapSortOrders(table.sortOrder()),
        mapIndexes(table.index()),
        mapAudit(table.auditInfo()));
  }

  private static List<Column> mapColumns(org.apache.gravitino.rel.Column[] columns) {
    if (columns == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(columns).map(TableMapper::mapColumn).collect(Collectors.toList());
  }

  private static Column mapColumn(org.apache.gravitino.rel.Column column) {
    if (column == null) {
      throw unsupported("column", null);
    }
    org.apache.gravitino.rel.expressions.Expression defaultValue = column.defaultValue();
    Expression mappedDefault =
        defaultValue == null
                || defaultValue == org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET
            ? null
            : mapExpression(defaultValue);
    return new Column(
        column.name(),
        mapDataType(column.dataType()),
        column.comment(),
        column.nullable(),
        column.autoIncrement(),
        mappedDefault);
  }

  private static DataType mapDataType(Type type) {
    if (type == null || type.name() == null) {
      throw unsupported("data type", type);
    }

    switch (type.name()) {
      case BOOLEAN:
        requireImplementation(type, Types.BooleanType.class);
        return new DataType.BooleanType();
      case BYTE:
        return new DataType.ByteType(requireImplementation(type, Types.ByteType.class).signed());
      case SHORT:
        return new DataType.ShortType(requireImplementation(type, Types.ShortType.class).signed());
      case INTEGER:
        return new DataType.IntegerType(
            requireImplementation(type, Types.IntegerType.class).signed());
      case LONG:
        return new DataType.LongType(requireImplementation(type, Types.LongType.class).signed());
      case FLOAT:
        requireImplementation(type, Types.FloatType.class);
        return new DataType.FloatType();
      case DOUBLE:
        requireImplementation(type, Types.DoubleType.class);
        return new DataType.DoubleType();
      case DECIMAL:
        Types.DecimalType decimal = requireImplementation(type, Types.DecimalType.class);
        return new DataType.DecimalType(decimal.precision(), decimal.scale());
      case DATE:
        requireImplementation(type, Types.DateType.class);
        return new DataType.DateType();
      case TIME:
        Types.TimeType time = requireImplementation(type, Types.TimeType.class);
        return new DataType.TimeType(time.hasPrecisionSet() ? time.precision() : null);
      case TIMESTAMP:
        Types.TimestampType timestamp = requireImplementation(type, Types.TimestampType.class);
        return new DataType.TimestampType(
            timestamp.hasTimeZone(), timestamp.hasPrecisionSet() ? timestamp.precision() : null);
      case INTERVAL_YEAR:
        requireImplementation(type, Types.IntervalYearType.class);
        return new DataType.IntervalYearType();
      case INTERVAL_DAY:
        requireImplementation(type, Types.IntervalDayType.class);
        return new DataType.IntervalDayType();
      case STRING:
        requireImplementation(type, Types.StringType.class);
        return new DataType.StringType();
      case VARCHAR:
        return new DataType.VarCharType(
            requireImplementation(type, Types.VarCharType.class).length());
      case FIXEDCHAR:
        return new DataType.FixedCharType(
            requireImplementation(type, Types.FixedCharType.class).length());
      case UUID:
        requireImplementation(type, Types.UUIDType.class);
        return new DataType.UuidType();
      case FIXED:
        return new DataType.FixedType(requireImplementation(type, Types.FixedType.class).length());
      case BINARY:
        requireImplementation(type, Types.BinaryType.class);
        return new DataType.BinaryType();
      case VARIANT:
        requireImplementation(type, Types.VariantType.class);
        return new DataType.VariantType();
      case STRUCT:
        Types.StructType struct = requireImplementation(type, Types.StructType.class);
        return new DataType.StructType(
            Arrays.stream(struct.fields())
                .map(TableMapper::mapStructField)
                .collect(Collectors.toList()));
      case LIST:
        Types.ListType list = requireImplementation(type, Types.ListType.class);
        return new DataType.ListType(mapDataType(list.elementType()), list.elementNullable());
      case MAP:
        Types.MapType map = requireImplementation(type, Types.MapType.class);
        return new DataType.MapType(
            mapDataType(map.keyType()), mapDataType(map.valueType()), map.valueNullable());
      case UNION:
        Types.UnionType union = requireImplementation(type, Types.UnionType.class);
        return new DataType.UnionType(
            Arrays.stream(union.types())
                .map(TableMapper::mapDataType)
                .collect(Collectors.toList()));
      case NULL:
        requireImplementation(type, Types.NullType.class);
        return new DataType.NullType();
      case UNPARSED:
        return new DataType.UnparsedType(
            requireImplementation(type, Types.UnparsedType.class).unparsedType());
      case EXTERNAL:
        return new DataType.ExternalType(
            requireImplementation(type, Types.ExternalType.class).catalogString());
      default:
        throw unsupported("data type", type);
    }
  }

  private static DataType.StructField mapStructField(Types.StructType.Field field) {
    if (field == null) {
      throw unsupported("struct field", null);
    }
    return new DataType.StructField(
        field.name(), mapDataType(field.type()), field.nullable(), field.comment());
  }

  private static Expression mapExpression(
      org.apache.gravitino.rel.expressions.Expression expression) {
    if (expression instanceof NamedReference) {
      if (expression instanceof NamedReference.MetadataField) {
        throw new IllegalArgumentException(
            "Metadata references are not part of the V1 Iceberg expression grammar");
      }
      String[] fieldName = ((NamedReference) expression).fieldName();
      if (fieldName.length != 1) {
        throw new IllegalArgumentException(
            "Nested Gravitino references are not part of the V1 Iceberg expression grammar");
      }
      return Expression.Reference.named(fieldName[0]);
    }
    if (expression instanceof Literal) {
      return mapLiteral((Literal<?>) expression);
    }
    if (expression instanceof FunctionExpression) {
      FunctionExpression function = (FunctionExpression) expression;
      return new Expression.Apply(
          Expression.FunctionReference.named(function.functionName()),
          Arrays.stream(function.arguments())
              .map(argument -> (ExpressionNode) mapExpression(argument))
              .collect(Collectors.toList()));
    }
    throw unsupported("expression", expression);
  }

  private static Expression.Literal mapLiteral(
      @Nullable Object value, Type type, boolean stringEncodedDto) {
    DataType mappedType = mapDataType(type);
    if (mappedType.getKind() == DataType.Kind.NULL) {
      if (value != null && !"NULL".equalsIgnoreCase(value.toString())) {
        throw new IllegalArgumentException("NULL literal cannot contain a value");
      }
      return new Expression.Literal(null, mappedType);
    }
    if (value == null) {
      throw new IllegalArgumentException("Non-NULL literal must contain a value");
    }
    // The public grammar supports Iceberg structured values. Current Gravitino literals do not
    // expose stable struct field IDs, and LiteralDTO flattens values to strings. Reject those
    // internal shapes until a lossless domain adapter exists rather than inventing a wire value.
    if (type.name() == Type.Name.STRUCT
        || type.name() == Type.Name.LIST
        || type.name() == Type.Name.MAP
        || type.name() == Type.Name.UNION) {
      throw unsupported("structured literal value", value);
    }
    Object wireValue =
        stringEncodedDto ? dtoLiteralValue((String) value, type) : literalValue(value);
    return new Expression.Literal(wireValue, mappedType);
  }

  private static Expression.Literal mapLiteral(Literal<?> literal) {
    return mapLiteral(literal.value(), literal.dataType(), literal instanceof LiteralDTO);
  }

  private static Object dtoLiteralValue(String value, Type type) {
    try {
      switch (type.name()) {
        case BOOLEAN:
          if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Boolean literal must be true or false");
          }
          return Boolean.valueOf(value);
        case BYTE:
          Types.ByteType byteType = requireImplementation(type, Types.ByteType.class);
          return parseIntegral(
              value,
              byteType.signed() ? BigInteger.valueOf(Byte.MIN_VALUE) : BigInteger.ZERO,
              byteType.signed() ? BigInteger.valueOf(Byte.MAX_VALUE) : BigInteger.valueOf(255),
              Integer.class);
        case SHORT:
          Types.ShortType shortType = requireImplementation(type, Types.ShortType.class);
          return parseIntegral(
              value,
              shortType.signed() ? BigInteger.valueOf(Short.MIN_VALUE) : BigInteger.ZERO,
              shortType.signed() ? BigInteger.valueOf(Short.MAX_VALUE) : BigInteger.valueOf(65_535),
              Integer.class);
        case INTEGER:
          Types.IntegerType integerType = requireImplementation(type, Types.IntegerType.class);
          return parseIntegral(
              value,
              integerType.signed() ? BigInteger.valueOf(Integer.MIN_VALUE) : BigInteger.ZERO,
              integerType.signed()
                  ? BigInteger.valueOf(Integer.MAX_VALUE)
                  : new BigInteger("4294967295"),
              integerType.signed() ? Integer.class : Long.class);
        case LONG:
          Types.LongType longType = requireImplementation(type, Types.LongType.class);
          return parseIntegral(
              value,
              longType.signed() ? BigInteger.valueOf(Long.MIN_VALUE) : BigInteger.ZERO,
              longType.signed()
                  ? BigInteger.valueOf(Long.MAX_VALUE)
                  : new BigInteger("18446744073709551615"),
              longType.signed() ? Long.class : BigInteger.class);
        case FLOAT:
          return finiteFloat(value);
        case DOUBLE:
          return finiteDouble(value);
        case FIXED:
        case BINARY:
          validateLowercaseHex(value, type);
          return value;
        default:
          return value;
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid V1 " + type.name() + " literal value", e);
    }
  }

  private static Number parseIntegral(
      String value, BigInteger minimum, BigInteger maximum, Class<? extends Number> outputType) {
    BigInteger parsed = new BigInteger(value);
    if (parsed.compareTo(minimum) < 0 || parsed.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("Integral literal is outside its declared type range");
    }
    if (outputType == Integer.class) {
      return parsed.intValue();
    }
    if (outputType == Long.class) {
      return parsed.longValue();
    }
    return parsed;
  }

  private static Float finiteFloat(String value) {
    Float parsed = Float.valueOf(value);
    if (!Float.isFinite(parsed)) {
      throw new IllegalArgumentException("Float literal must be finite");
    }
    return parsed;
  }

  private static Double finiteDouble(String value) {
    Double parsed = Double.valueOf(value);
    if (!Double.isFinite(parsed)) {
      throw new IllegalArgumentException("Double literal must be finite");
    }
    return parsed;
  }

  private static void validateLowercaseHex(String value, Type type) {
    if (!value.matches("(?:[0-9a-f]{2})*")) {
      throw new IllegalArgumentException(
          "Fixed and binary literals must use lowercase hexadecimal");
    }
    if (type.name() == Type.Name.FIXED
        && value.length() != requireImplementation(type, Types.FixedType.class).length() * 2) {
      throw new IllegalArgumentException("Fixed literal length does not match its declared type");
    }
  }

  private static Object literalValue(Object value) {
    if (value instanceof byte[]) {
      return lowercaseHex((byte[]) value);
    }
    if (value instanceof ByteBuffer) {
      ByteBuffer bytes = ((ByteBuffer) value).asReadOnlyBuffer();
      byte[] copy = new byte[bytes.remaining()];
      bytes.get(copy);
      return lowercaseHex(copy);
    }
    if (value instanceof Decimal
        || value instanceof UUID
        || value instanceof java.time.temporal.TemporalAccessor
        || value instanceof Duration
        || value instanceof Period) {
      return value.toString();
    }
    if ((value instanceof Double && !Double.isFinite((Double) value))
        || (value instanceof Float && !Float.isFinite((Float) value))) {
      throw new IllegalArgumentException("Non-finite values are not valid JSON numbers");
    }
    if (value instanceof Number || value instanceof Boolean || value instanceof String) {
      return value;
    }
    throw unsupported("literal value", value);
  }

  private static String lowercaseHex(byte[] value) {
    char[] digits = "0123456789abcdef".toCharArray();
    char[] hex = new char[value.length * 2];
    for (int index = 0; index < value.length; index++) {
      int unsigned = value[index] & 0xff;
      hex[index * 2] = digits[unsigned >>> 4];
      hex[index * 2 + 1] = digits[unsigned & 0x0f];
    }
    return new String(hex);
  }

  private static List<org.apache.gravitino.rest.v1.model.Transform> mapTransforms(
      Transform[] transforms) {
    if (transforms == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(transforms).map(TableMapper::mapTransform).collect(Collectors.toList());
  }

  private static org.apache.gravitino.rest.v1.model.Transform mapTransform(Transform transform) {
    if (transform instanceof Transforms.IdentityTransform) {
      return new org.apache.gravitino.rest.v1.model.Transform.Identity(
          fieldName(((Transforms.IdentityTransform) transform).fieldName()));
    }
    if (transform instanceof Transforms.YearTransform) {
      return new org.apache.gravitino.rest.v1.model.Transform.Year(
          fieldName(((Transforms.YearTransform) transform).fieldName()));
    }
    if (transform instanceof Transforms.MonthTransform) {
      return new org.apache.gravitino.rest.v1.model.Transform.Month(
          fieldName(((Transforms.MonthTransform) transform).fieldName()));
    }
    if (transform instanceof Transforms.DayTransform) {
      return new org.apache.gravitino.rest.v1.model.Transform.Day(
          fieldName(((Transforms.DayTransform) transform).fieldName()));
    }
    if (transform instanceof Transforms.HourTransform) {
      return new org.apache.gravitino.rest.v1.model.Transform.Hour(
          fieldName(((Transforms.HourTransform) transform).fieldName()));
    }
    if (transform instanceof Transforms.BucketTransform) {
      Transforms.BucketTransform bucket = (Transforms.BucketTransform) transform;
      return new org.apache.gravitino.rest.v1.model.Transform.Bucket(
          bucket.numBuckets(), fieldNames(bucket.fieldNames()));
    }
    if (transform instanceof Transforms.TruncateTransform) {
      Transforms.TruncateTransform truncate = (Transforms.TruncateTransform) transform;
      return new org.apache.gravitino.rest.v1.model.Transform.Truncate(
          truncate.width(), fieldName(truncate.fieldName()));
    }
    if (transform instanceof Transforms.ListTransform) {
      Transforms.ListTransform list = (Transforms.ListTransform) transform;
      return new org.apache.gravitino.rest.v1.model.Transform.ListTransform(
          fieldNames(list.fieldNames()), mapAssignments(list.assignments()));
    }
    if (transform instanceof Transforms.RangeTransform) {
      Transforms.RangeTransform range = (Transforms.RangeTransform) transform;
      return new org.apache.gravitino.rest.v1.model.Transform.Range(
          fieldName(range.fieldName()), mapAssignments(range.assignments()));
    }
    if (transform instanceof Transforms.ApplyTransform) {
      Transforms.ApplyTransform apply = (Transforms.ApplyTransform) transform;
      return new org.apache.gravitino.rest.v1.model.Transform.Apply(
          apply.name(),
          Arrays.stream(apply.arguments())
              .map(argument -> (ValueExpression) mapExpression(argument))
              .collect(Collectors.toList()));
    }
    throw unsupported("partition transform", transform);
  }

  private static List<PartitionAssignment> mapAssignments(Partition[] assignments) {
    if (assignments == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(assignments).map(TableMapper::mapAssignment).collect(Collectors.toList());
  }

  private static PartitionAssignment mapAssignment(Partition assignment) {
    if (assignment instanceof IdentityPartition) {
      IdentityPartition identity = (IdentityPartition) assignment;
      return new PartitionAssignment.Identity(
          identity.name(),
          publicProperties(identity.properties()),
          fieldNames(identity.fieldNames()),
          Arrays.stream(identity.values())
              .map(TableMapper::mapLiteral)
              .collect(Collectors.toList()));
    }
    if (assignment instanceof ListPartition) {
      ListPartition list = (ListPartition) assignment;
      List<List<Expression.Literal>> values = new ArrayList<>();
      for (org.apache.gravitino.rel.expressions.literals.Literal<?>[] tuple : list.lists()) {
        values.add(Arrays.stream(tuple).map(TableMapper::mapLiteral).collect(Collectors.toList()));
      }
      return new PartitionAssignment.ListAssignment(
          list.name(), publicProperties(list.properties()), values);
    }
    if (assignment instanceof RangePartition) {
      RangePartition range = (RangePartition) assignment;
      return new PartitionAssignment.Range(
          range.name(),
          publicProperties(range.properties()),
          range.lower() == null ? null : mapLiteral(range.lower()),
          range.upper() == null ? null : mapLiteral(range.upper()));
    }
    throw unsupported("partition assignment", assignment);
  }

  @Nullable
  private static Distribution mapDistribution(
      @Nullable org.apache.gravitino.rel.expressions.distributions.Distribution distribution) {
    if (distribution == null) {
      return null;
    }
    if (!(distribution instanceof Distributions.DistributionImpl)) {
      throw unsupported("distribution", distribution);
    }
    Integer bucketCount;
    if (distribution.number() > 0) {
      bucketCount = distribution.number();
    } else if (distribution.number() == 0 || distribution.number() == Distributions.AUTO) {
      bucketCount = null;
    } else {
      throw new IllegalArgumentException("Unsupported distribution bucket count");
    }
    return new Distribution(
        mapDistributionStrategy(distribution.strategy()),
        bucketCount,
        Arrays.stream(distribution.expressions())
            .map(expression -> (ValueExpression) mapExpression(expression))
            .collect(Collectors.toList()));
  }

  private static Distribution.Strategy mapDistributionStrategy(Strategy strategy) {
    if (strategy == null) {
      throw unsupported("distribution strategy", null);
    }
    switch (strategy) {
      case NONE:
        return Distribution.Strategy.NONE;
      case HASH:
        return Distribution.Strategy.HASH;
      case RANGE:
        return Distribution.Strategy.RANGE;
      case EVEN:
        return Distribution.Strategy.EVEN;
      default:
        throw unsupported("distribution strategy", strategy);
    }
  }

  private static List<SortOrder> mapSortOrders(
      org.apache.gravitino.rel.expressions.sorts.SortOrder[] sortOrders) {
    if (sortOrders == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(sortOrders).map(TableMapper::mapSortOrder).collect(Collectors.toList());
  }

  private static SortOrder mapSortOrder(
      org.apache.gravitino.rel.expressions.sorts.SortOrder sortOrder) {
    if (!(sortOrder instanceof SortOrders.SortImpl)) {
      throw unsupported("sort order", sortOrder);
    }
    return new SortOrder(
        mapExpression(sortOrder.expression()),
        mapSortDirection(sortOrder.direction()),
        mapNullOrdering(sortOrder.nullOrdering()));
  }

  private static SortOrder.Direction mapSortDirection(SortDirection direction) {
    if (direction == SortDirection.ASCENDING) {
      return SortOrder.Direction.ASC;
    }
    if (direction == SortDirection.DESCENDING) {
      return SortOrder.Direction.DESC;
    }
    throw unsupported("sort direction", direction);
  }

  private static SortOrder.NullOrdering mapNullOrdering(NullOrdering nullOrdering) {
    if (nullOrdering == NullOrdering.NULLS_FIRST) {
      return SortOrder.NullOrdering.NULLS_FIRST;
    }
    if (nullOrdering == NullOrdering.NULLS_LAST) {
      return SortOrder.NullOrdering.NULLS_LAST;
    }
    throw unsupported("null ordering", nullOrdering);
  }

  private static List<Index> mapIndexes(org.apache.gravitino.rel.indexes.Index[] indexes) {
    if (indexes == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(indexes).map(TableMapper::mapIndex).collect(Collectors.toList());
  }

  private static Index mapIndex(org.apache.gravitino.rel.indexes.Index index) {
    if (!(index instanceof Indexes.IndexImpl)) {
      throw unsupported("index", index);
    }
    return new Index(
        mapIndexType(index.type()),
        index.name() == null || index.name().isEmpty() ? null : index.name(),
        fieldNames(index.fieldNames()),
        publicProperties(index.properties()));
  }

  private static Index.Type mapIndexType(
      org.apache.gravitino.rel.indexes.Index.IndexType indexType) {
    if (indexType == null) {
      throw unsupported("index type", null);
    }
    try {
      return Index.Type.valueOf(indexType.name());
    } catch (IllegalArgumentException exception) {
      throw unsupported("index type", indexType);
    }
  }

  @Nullable
  private static org.apache.gravitino.rest.v1.model.Audit mapAudit(@Nullable Audit audit) {
    if (audit == null) {
      return null;
    }
    String creator = audit.creator();
    Instant createTime = audit.createTime();
    String lastModifier = audit.lastModifier();
    Instant lastModifiedTime = audit.lastModifiedTime();
    if (creator == null && createTime == null && lastModifier == null && lastModifiedTime == null) {
      return null;
    }
    return new org.apache.gravitino.rest.v1.model.Audit(
        creator, createTime, lastModifier, lastModifiedTime);
  }

  private static List<String> fieldName(String[] fieldName) {
    if (fieldName == null) {
      throw unsupported("field name", null);
    }
    return Arrays.asList(fieldName);
  }

  private static List<List<String>> fieldNames(String[][] fieldNames) {
    if (fieldNames == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(fieldNames).map(TableMapper::fieldName).collect(Collectors.toList());
  }

  private static Map<String, String> publicProperties(@Nullable Map<String, String> properties) {
    if (properties == null || properties.isEmpty()) {
      return Collections.emptyMap();
    }
    TreeMap<String, String> result = new TreeMap<>();
    properties.forEach(
        (key, value) -> {
          if (key != null && value != null) {
            result.put(key, value);
          }
        });
    return Collections.unmodifiableMap(result);
  }

  private static <T> T requireImplementation(Object value, Class<T> implementation) {
    if (!implementation.isInstance(value)) {
      throw unsupported(implementation.getSimpleName(), value);
    }
    return implementation.cast(value);
  }

  private static IllegalArgumentException unsupported(String category, @Nullable Object value) {
    String implementation = value == null ? "null" : value.getClass().getName();
    return new IllegalArgumentException("Unsupported " + category + ": " + implementation);
  }

  private TableMapper() {}
}
