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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** A data type in the public Gravitino V1 wire contract. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = DataType.BooleanType.class, name = "BOOLEAN"),
  @JsonSubTypes.Type(value = DataType.ByteType.class, name = "BYTE"),
  @JsonSubTypes.Type(value = DataType.ShortType.class, name = "SHORT"),
  @JsonSubTypes.Type(value = DataType.IntegerType.class, name = "INTEGER"),
  @JsonSubTypes.Type(value = DataType.LongType.class, name = "LONG"),
  @JsonSubTypes.Type(value = DataType.FloatType.class, name = "FLOAT"),
  @JsonSubTypes.Type(value = DataType.DoubleType.class, name = "DOUBLE"),
  @JsonSubTypes.Type(value = DataType.DecimalType.class, name = "DECIMAL"),
  @JsonSubTypes.Type(value = DataType.DateType.class, name = "DATE"),
  @JsonSubTypes.Type(value = DataType.TimeType.class, name = "TIME"),
  @JsonSubTypes.Type(value = DataType.TimestampType.class, name = "TIMESTAMP"),
  @JsonSubTypes.Type(value = DataType.IntervalYearType.class, name = "INTERVAL_YEAR"),
  @JsonSubTypes.Type(value = DataType.IntervalDayType.class, name = "INTERVAL_DAY"),
  @JsonSubTypes.Type(value = DataType.StringType.class, name = "STRING"),
  @JsonSubTypes.Type(value = DataType.VarCharType.class, name = "VARCHAR"),
  @JsonSubTypes.Type(value = DataType.FixedCharType.class, name = "FIXEDCHAR"),
  @JsonSubTypes.Type(value = DataType.UuidType.class, name = "UUID"),
  @JsonSubTypes.Type(value = DataType.FixedType.class, name = "FIXED"),
  @JsonSubTypes.Type(value = DataType.BinaryType.class, name = "BINARY"),
  @JsonSubTypes.Type(value = DataType.VariantType.class, name = "VARIANT"),
  @JsonSubTypes.Type(value = DataType.StructType.class, name = "STRUCT"),
  @JsonSubTypes.Type(value = DataType.ListType.class, name = "LIST"),
  @JsonSubTypes.Type(value = DataType.MapType.class, name = "MAP"),
  @JsonSubTypes.Type(value = DataType.UnionType.class, name = "UNION"),
  @JsonSubTypes.Type(value = DataType.NullType.class, name = "NULL"),
  @JsonSubTypes.Type(value = DataType.UnparsedType.class, name = "UNPARSED"),
  @JsonSubTypes.Type(value = DataType.ExternalType.class, name = "EXTERNAL")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public abstract class DataType {

  @JsonProperty(value = "kind", required = true)
  private final Kind kind;

  DataType(Kind kind) {
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
  }

  /**
   * @return the stable kind discriminator.
   */
  public Kind getKind() {
    return kind;
  }

  /** The data type kinds supported by the V1 table contract. */
  public enum Kind {
    /** Boolean. */
    BOOLEAN,
    /** Signed or unsigned byte. */
    BYTE,
    /** Signed or unsigned short. */
    SHORT,
    /** Signed or unsigned integer. */
    INTEGER,
    /** Signed or unsigned long. */
    LONG,
    /** Single-precision floating point. */
    FLOAT,
    /** Double-precision floating point. */
    DOUBLE,
    /** Fixed-precision decimal. */
    DECIMAL,
    /** Calendar date. */
    DATE,
    /** Time of day. */
    TIME,
    /** Timestamp, optionally with a time zone. */
    TIMESTAMP,
    /** Year-month interval. */
    INTERVAL_YEAR,
    /** Day-time interval. */
    INTERVAL_DAY,
    /** Unbounded string. */
    STRING,
    /** Bounded variable-length string. */
    VARCHAR,
    /** Fixed-length character string. */
    FIXEDCHAR,
    /** Universally unique identifier. */
    UUID,
    /** Fixed-length binary. */
    FIXED,
    /** Variable-length binary. */
    BINARY,
    /** Semi-structured variant. */
    VARIANT,
    /** Named struct. */
    STRUCT,
    /** Homogeneous list. */
    LIST,
    /** Key-value map. */
    MAP,
    /** Union. */
    UNION,
    /** Null. */
    NULL,
    /** Type text Gravitino has not parsed. */
    UNPARSED,
    /** Catalog-native external type. */
    EXTERNAL;

    /**
     * @return the stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }

  /** The V1 boolean type. */
  public static final class BooleanType extends DataType {
    /** Creates a boolean type. */
    @JsonCreator
    public BooleanType() {
      super(Kind.BOOLEAN);
    }
  }

  /** Base class for V1 integral types. */
  public abstract static class IntegralType extends DataType {
    @JsonProperty(value = "signed", required = true)
    private final boolean signed;

    IntegralType(Kind kind, boolean signed) {
      super(kind);
      this.signed = signed;
    }

    /**
     * @return whether the integral type is signed.
     */
    public boolean isSigned() {
      return signed;
    }
  }

  /** The V1 byte type. */
  public static final class ByteType extends IntegralType {
    /**
     * @param signed whether the byte is signed.
     */
    @JsonCreator
    public ByteType(@JsonProperty(value = "signed", required = true) boolean signed) {
      super(Kind.BYTE, signed);
    }
  }

  /** The V1 short type. */
  public static final class ShortType extends IntegralType {
    /**
     * @param signed whether the short is signed.
     */
    @JsonCreator
    public ShortType(@JsonProperty(value = "signed", required = true) boolean signed) {
      super(Kind.SHORT, signed);
    }
  }

  /** The V1 integer type. */
  public static final class IntegerType extends IntegralType {
    /**
     * @param signed whether the integer is signed.
     */
    @JsonCreator
    public IntegerType(@JsonProperty(value = "signed", required = true) boolean signed) {
      super(Kind.INTEGER, signed);
    }
  }

  /** The V1 long type. */
  public static final class LongType extends IntegralType {
    /**
     * @param signed whether the long is signed.
     */
    @JsonCreator
    public LongType(@JsonProperty(value = "signed", required = true) boolean signed) {
      super(Kind.LONG, signed);
    }
  }

  /** The V1 float type. */
  public static final class FloatType extends DataType {
    /** Creates a float type. */
    @JsonCreator
    public FloatType() {
      super(Kind.FLOAT);
    }
  }

  /** The V1 double type. */
  public static final class DoubleType extends DataType {
    /** Creates a double type. */
    @JsonCreator
    public DoubleType() {
      super(Kind.DOUBLE);
    }
  }

  /** The V1 decimal type. */
  public static final class DecimalType extends DataType {
    @JsonProperty(value = "precision", required = true)
    private final int precision;

    @JsonProperty(value = "scale", required = true)
    private final int scale;

    /**
     * Creates a decimal type.
     *
     * @param precision total decimal precision.
     * @param scale decimal scale.
     */
    @JsonCreator
    public DecimalType(
        @JsonProperty(value = "precision", required = true) int precision,
        @JsonProperty(value = "scale", required = true) int scale) {
      super(Kind.DECIMAL);
      if (precision < 1 || scale < 0 || scale > precision) {
        throw new IllegalArgumentException("Invalid decimal precision or scale");
      }
      this.precision = precision;
      this.scale = scale;
    }

    /**
     * @return total decimal precision.
     */
    public int getPrecision() {
      return precision;
    }

    /**
     * @return decimal scale.
     */
    public int getScale() {
      return scale;
    }
  }

  /** The V1 date type. */
  public static final class DateType extends DataType {
    /** Creates a date type. */
    @JsonCreator
    public DateType() {
      super(Kind.DATE);
    }
  }

  /** The V1 time type. */
  public static final class TimeType extends DataType {
    @Nullable
    @JsonProperty("precision")
    private final Integer precision;

    /**
     * @param precision optional fractional-second precision.
     */
    @JsonCreator
    public TimeType(@Nullable @JsonProperty("precision") Integer precision) {
      super(Kind.TIME);
      this.precision = precision;
    }

    /**
     * @return optional fractional-second precision.
     */
    @Nullable
    public Integer getPrecision() {
      return precision;
    }
  }

  /** The V1 timestamp type. */
  public static final class TimestampType extends DataType {
    @JsonProperty(value = "withTimeZone", required = true)
    private final boolean withTimeZone;

    @Nullable
    @JsonProperty("precision")
    private final Integer precision;

    /**
     * Creates a timestamp type.
     *
     * @param withTimeZone whether values include a time zone.
     * @param precision optional fractional-second precision.
     */
    @JsonCreator
    public TimestampType(
        @JsonProperty(value = "withTimeZone", required = true) boolean withTimeZone,
        @Nullable @JsonProperty("precision") Integer precision) {
      super(Kind.TIMESTAMP);
      this.withTimeZone = withTimeZone;
      this.precision = precision;
    }

    /**
     * @return whether values include a time zone.
     */
    public boolean isWithTimeZone() {
      return withTimeZone;
    }

    /**
     * @return optional fractional-second precision.
     */
    @Nullable
    public Integer getPrecision() {
      return precision;
    }
  }

  /** The V1 year-month interval type. */
  public static final class IntervalYearType extends DataType {
    /** Creates a year-month interval type. */
    @JsonCreator
    public IntervalYearType() {
      super(Kind.INTERVAL_YEAR);
    }
  }

  /** The V1 day-time interval type. */
  public static final class IntervalDayType extends DataType {
    /** Creates a day-time interval type. */
    @JsonCreator
    public IntervalDayType() {
      super(Kind.INTERVAL_DAY);
    }
  }

  /** The V1 unbounded string type. */
  public static final class StringType extends DataType {
    /** Creates a string type. */
    @JsonCreator
    public StringType() {
      super(Kind.STRING);
    }
  }

  /** Base class for V1 length-constrained types. */
  public abstract static class LengthType extends DataType {
    @JsonProperty(value = "length", required = true)
    private final int length;

    LengthType(Kind kind, int length) {
      super(kind);
      if (length < 1) {
        throw new IllegalArgumentException("length must be positive");
      }
      this.length = length;
    }

    /**
     * @return the declared length.
     */
    public int getLength() {
      return length;
    }
  }

  /** The V1 variable-length character type. */
  public static final class VarCharType extends LengthType {
    /**
     * @param length maximum character length.
     */
    @JsonCreator
    public VarCharType(@JsonProperty(value = "length", required = true) int length) {
      super(Kind.VARCHAR, length);
    }
  }

  /** The V1 fixed-length character type. */
  public static final class FixedCharType extends LengthType {
    /**
     * @param length fixed character length.
     */
    @JsonCreator
    public FixedCharType(@JsonProperty(value = "length", required = true) int length) {
      super(Kind.FIXEDCHAR, length);
    }
  }

  /** The V1 UUID type. */
  public static final class UuidType extends DataType {
    /** Creates a UUID type. */
    @JsonCreator
    public UuidType() {
      super(Kind.UUID);
    }
  }

  /** The V1 fixed-length binary type. */
  public static final class FixedType extends LengthType {
    /**
     * @param length fixed byte length.
     */
    @JsonCreator
    public FixedType(@JsonProperty(value = "length", required = true) int length) {
      super(Kind.FIXED, length);
    }
  }

  /** The V1 variable-length binary type. */
  public static final class BinaryType extends DataType {
    /** Creates a binary type. */
    @JsonCreator
    public BinaryType() {
      super(Kind.BINARY);
    }
  }

  /** The V1 variant type. */
  public static final class VariantType extends DataType {
    /** Creates a variant type. */
    @JsonCreator
    public VariantType() {
      super(Kind.VARIANT);
    }
  }

  /** A field contained by a V1 struct type. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class StructField {
    @JsonProperty(value = "name", required = true)
    private final String name;

    @JsonProperty(value = "type", required = true)
    private final DataType type;

    @JsonProperty(value = "nullable", required = true)
    private final boolean nullable;

    @Nullable
    @JsonProperty("comment")
    private final String comment;

    /**
     * Creates a struct field.
     *
     * @param name field name.
     * @param type field type.
     * @param nullable whether the field may contain null.
     * @param comment optional field comment.
     */
    @JsonCreator
    public StructField(
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "type", required = true) DataType type,
        @JsonProperty(value = "nullable", required = true) boolean nullable,
        @Nullable @JsonProperty("comment") String comment) {
      this.name = ModelSupport.requireNonEmpty(name, "name");
      this.type = Objects.requireNonNull(type, "type cannot be null");
      this.nullable = nullable;
      this.comment = comment;
    }

    /**
     * @return field name.
     */
    public String getName() {
      return name;
    }

    /**
     * @return field type.
     */
    public DataType getType() {
      return type;
    }

    /**
     * @return whether the field may contain null.
     */
    public boolean isNullable() {
      return nullable;
    }

    /**
     * @return optional field comment.
     */
    @Nullable
    public String getComment() {
      return comment;
    }
  }

  /** The V1 struct type. */
  public static final class StructType extends DataType {
    @JsonProperty(value = "fields", required = true)
    private final List<StructField> fields;

    /**
     * @param fields ordered struct fields.
     */
    @JsonCreator
    public StructType(@JsonProperty(value = "fields", required = true) List<StructField> fields) {
      super(Kind.STRUCT);
      this.fields = ModelSupport.immutableList(fields, "fields");
    }

    /**
     * @return ordered struct fields.
     */
    public List<StructField> getFields() {
      return fields;
    }
  }

  /** The V1 list type. */
  public static final class ListType extends DataType {
    @JsonProperty(value = "elementType", required = true)
    private final DataType elementType;

    @JsonProperty(value = "elementNullable", required = true)
    private final boolean elementNullable;

    /**
     * Creates a list type.
     *
     * @param elementType list element type.
     * @param elementNullable whether elements may contain null.
     */
    @JsonCreator
    public ListType(
        @JsonProperty(value = "elementType", required = true) DataType elementType,
        @JsonProperty(value = "elementNullable", required = true) boolean elementNullable) {
      super(Kind.LIST);
      this.elementType = Objects.requireNonNull(elementType, "elementType cannot be null");
      this.elementNullable = elementNullable;
    }

    /**
     * @return list element type.
     */
    public DataType getElementType() {
      return elementType;
    }

    /**
     * @return whether elements may contain null.
     */
    public boolean isElementNullable() {
      return elementNullable;
    }
  }

  /** The V1 map type. */
  public static final class MapType extends DataType {
    @JsonProperty(value = "keyType", required = true)
    private final DataType keyType;

    @JsonProperty(value = "valueType", required = true)
    private final DataType valueType;

    @JsonProperty(value = "valueNullable", required = true)
    private final boolean valueNullable;

    /**
     * Creates a map type.
     *
     * @param keyType map key type.
     * @param valueType map value type.
     * @param valueNullable whether values may contain null.
     */
    @JsonCreator
    public MapType(
        @JsonProperty(value = "keyType", required = true) DataType keyType,
        @JsonProperty(value = "valueType", required = true) DataType valueType,
        @JsonProperty(value = "valueNullable", required = true) boolean valueNullable) {
      super(Kind.MAP);
      this.keyType = Objects.requireNonNull(keyType, "keyType cannot be null");
      this.valueType = Objects.requireNonNull(valueType, "valueType cannot be null");
      this.valueNullable = valueNullable;
    }

    /**
     * @return map key type.
     */
    public DataType getKeyType() {
      return keyType;
    }

    /**
     * @return map value type.
     */
    public DataType getValueType() {
      return valueType;
    }

    /**
     * @return whether values may contain null.
     */
    public boolean isValueNullable() {
      return valueNullable;
    }
  }

  /** The V1 union type. */
  public static final class UnionType extends DataType {
    @JsonProperty(value = "types", required = true)
    private final List<DataType> types;

    /**
     * @param types union member types.
     */
    @JsonCreator
    public UnionType(@JsonProperty(value = "types", required = true) List<DataType> types) {
      super(Kind.UNION);
      this.types = ModelSupport.immutableList(types, "types");
    }

    /**
     * @return union member types.
     */
    public List<DataType> getTypes() {
      return types;
    }
  }

  /** The V1 null type. */
  public static final class NullType extends DataType {
    /** Creates a null type. */
    @JsonCreator
    public NullType() {
      super(Kind.NULL);
    }
  }

  /** The V1 unparsed type. */
  public static final class UnparsedType extends DataType {
    @JsonProperty(value = "unparsedType", required = true)
    private final String unparsedType;

    /**
     * @param unparsedType original unparsed type text.
     */
    @JsonCreator
    public UnparsedType(
        @JsonProperty(value = "unparsedType", required = true) String unparsedType) {
      super(Kind.UNPARSED);
      this.unparsedType = ModelSupport.requireNonEmpty(unparsedType, "unparsedType");
    }

    /**
     * @return original unparsed type text.
     */
    public String getUnparsedType() {
      return unparsedType;
    }
  }

  /** The V1 catalog-native external type. */
  public static final class ExternalType extends DataType {
    @JsonProperty(value = "catalogString", required = true)
    private final String catalogString;

    /**
     * @param catalogString type text used by the external catalog.
     */
    @JsonCreator
    public ExternalType(
        @JsonProperty(value = "catalogString", required = true) String catalogString) {
      super(Kind.EXTERNAL);
      this.catalogString = ModelSupport.requireNonEmpty(catalogString, "catalogString");
    }

    /**
     * @return type text used by the external catalog.
     */
    public String getCatalogString() {
      return catalogString;
    }
  }
}
