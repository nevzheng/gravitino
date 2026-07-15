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
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** A preassigned partition encoded by a V1 table partition transform. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PartitionAssignment.Identity.class, name = "IDENTITY"),
  @JsonSubTypes.Type(value = PartitionAssignment.ListAssignment.class, name = "LIST"),
  @JsonSubTypes.Type(value = PartitionAssignment.Range.class, name = "RANGE")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public abstract class PartitionAssignment {

  @JsonProperty(value = "kind", required = true)
  private final Kind kind;

  @Nullable
  @JsonProperty("name")
  private final String name;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  PartitionAssignment(Kind kind, @Nullable String name, Map<String, String> properties) {
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
    this.name = name;
    this.properties = ModelSupport.immutableMap(properties, "properties");
  }

  /**
   * @return stable assignment kind discriminator.
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * @return optional catalog partition name.
   */
  @Nullable
  public String getName() {
    return name;
  }

  /**
   * @return immutable partition properties.
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  /** The preassigned partition kinds supported by V1. */
  public enum Kind {
    /** Identity partition. */
    IDENTITY,
    /** List partition. */
    LIST,
    /** Range partition. */
    RANGE;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }

  /** A partition whose values map directly to field names. */
  public static final class Identity extends PartitionAssignment {
    @JsonProperty(value = "fieldNames", required = true)
    private final List<List<String>> fieldNames;

    @JsonProperty(value = "values", required = true)
    private final List<Expression.Literal> values;

    /**
     * Creates an identity assignment.
     *
     * @param name optional partition name.
     * @param properties partition properties.
     * @param fieldNames ordered field-name paths.
     * @param values ordered values corresponding to the fields.
     */
    @JsonCreator
    public Identity(
        @Nullable @JsonProperty("name") String name,
        @JsonProperty(value = "properties", required = true) Map<String, String> properties,
        @JsonProperty(value = "fieldNames", required = true) List<List<String>> fieldNames,
        @JsonProperty(value = "values", required = true) List<Expression.Literal> values) {
      super(Kind.IDENTITY, name, properties);
      this.fieldNames = ModelSupport.immutableFieldNames(fieldNames, "fieldNames");
      this.values = ModelSupport.immutableList(values, "values");
      if (this.fieldNames.size() != this.values.size()) {
        throw new IllegalArgumentException("fieldNames and values must have equal size");
      }
    }

    /**
     * @return ordered field-name paths.
     */
    public List<List<String>> getFieldNames() {
      return fieldNames;
    }

    /**
     * @return ordered values corresponding to the fields.
     */
    public List<Expression.Literal> getValues() {
      return values;
    }
  }

  /** A partition containing one or more tuples of literal values. */
  public static final class ListAssignment extends PartitionAssignment {
    @JsonProperty(value = "values", required = true)
    private final List<List<Expression.Literal>> values;

    /**
     * Creates a list assignment.
     *
     * @param name optional partition name.
     * @param properties partition properties.
     * @param values ordered literal tuples.
     */
    @JsonCreator
    public ListAssignment(
        @Nullable @JsonProperty("name") String name,
        @JsonProperty(value = "properties", required = true) Map<String, String> properties,
        @JsonProperty(value = "values", required = true) List<List<Expression.Literal>> values) {
      super(Kind.LIST, name, properties);
      this.values = ModelSupport.immutableNestedList(values, "values");
    }

    /**
     * @return ordered literal tuples.
     */
    public List<List<Expression.Literal>> getValues() {
      return values;
    }
  }

  /** A partition bounded by optional lower and upper literals. */
  public static final class Range extends PartitionAssignment {
    @Nullable
    @JsonProperty("lower")
    private final Expression.Literal lower;

    @Nullable
    @JsonProperty("upper")
    private final Expression.Literal upper;

    /**
     * Creates a range assignment.
     *
     * @param name optional partition name.
     * @param properties partition properties.
     * @param lower optional inclusive lower bound.
     * @param upper optional exclusive upper bound.
     */
    @JsonCreator
    public Range(
        @Nullable @JsonProperty("name") String name,
        @JsonProperty(value = "properties", required = true) Map<String, String> properties,
        @Nullable @JsonProperty("lower") Expression.Literal lower,
        @Nullable @JsonProperty("upper") Expression.Literal upper) {
      super(Kind.RANGE, name, properties);
      this.lower = lower;
      this.upper = upper;
    }

    /**
     * @return optional inclusive lower bound.
     */
    @Nullable
    public Expression.Literal getLower() {
      return lower;
    }

    /**
     * @return optional exclusive upper bound.
     */
    @Nullable
    public Expression.Literal getUpper() {
      return upper;
    }
  }
}
