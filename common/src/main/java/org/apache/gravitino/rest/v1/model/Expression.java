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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A typed value expression in the public Gravitino V1 wire contract.
 *
 * <p>The grammar follows the Apache Iceberg expressions specification. Gravitino emits canonical
 * object literals. Optional shorthand representations are normalized by the Java model without
 * changing their expression semantics.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Expression.Literal.class, name = "literal"),
  @JsonSubTypes.Type(value = Expression.Reference.class, name = "reference"),
  @JsonSubTypes.Type(value = Expression.Apply.class, name = "apply")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public abstract class Expression implements ValueExpression {

  @JsonProperty(value = "type", required = true)
  private final Type type;

  Expression(Type type) {
    this.type = Objects.requireNonNull(type, "type cannot be null");
  }

  /**
   * @return stable lower-case expression type discriminator.
   */
  public Type getType() {
    return type;
  }

  /** Value-expression types supported by the V1 contract. */
  public enum Type {
    /** A typed constant value. */
    LITERAL("literal"),
    /** A bound or unbound field reference. */
    REFERENCE("reference"),
    /** A function application. */
    APPLY("apply");

    private final String value;

    Type(String value) {
      this.value = value;
    }

    /**
     * @return stable lower-case JSON value.
     */
    @JsonValue
    public String value() {
      return value;
    }
  }

  /** A canonical typed literal. */
  public static final class Literal extends Expression {
    @JsonProperty(value = "value", required = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final Object value;

    @Nullable
    @JsonProperty("data-type")
    private final DataType dataType;

    /**
     * Creates a typed literal.
     *
     * @param value an Iceberg JSON single value, including a struct, list, map, or null.
     * @param dataType optional explicit literal data type.
     */
    @JsonCreator
    public Literal(
        @Nullable @JsonProperty(value = "value", required = true) Object value,
        @Nullable @JsonProperty("data-type") DataType dataType) {
      super(Type.LITERAL);
      if (dataType != null && ((dataType.getKind() == DataType.Kind.NULL) != (value == null))) {
        throw new IllegalArgumentException("Explicit NULL type and null value must occur together");
      }
      this.value = ModelSupport.immutableJsonValue(value, "value");
      this.dataType = dataType;
    }

    /**
     * @return immutable Iceberg JSON single value, including null.
     */
    @Nullable
    public Object getValue() {
      return value;
    }

    /**
     * @return optional explicit literal data type.
     */
    @Nullable
    @JsonProperty("data-type")
    public DataType getDataType() {
      return dataType;
    }
  }

  /** A bound ID reference or unbound named reference. */
  public static final class Reference extends Expression {
    @Nullable
    @JsonProperty("id")
    private final Integer id;

    @Nullable
    @JsonProperty("name")
    private final String name;

    /**
     * Creates a reference. Exactly one of {@code id} and {@code name} must be present.
     *
     * @param id bound schema field ID.
     * @param name unbound field name.
     */
    @JsonCreator
    public Reference(
        @Nullable @JsonProperty("id") Integer id, @Nullable @JsonProperty("name") String name) {
      super(Type.REFERENCE);
      if ((id == null) == (name == null)) {
        throw new IllegalArgumentException("Exactly one of id and name is required");
      }
      if (id != null && id < 0) {
        throw new IllegalArgumentException("id cannot be negative");
      }
      this.id = id;
      this.name = name == null ? null : ModelSupport.requireNonEmpty(name, "name");
    }

    /**
     * @param id bound schema field ID. @return a bound reference.
     */
    public static Reference bound(int id) {
      return new Reference(id, null);
    }

    /**
     * @param name unbound field name.
     * @return an unbound reference.
     */
    public static Reference named(String name) {
      return new Reference(null, name);
    }

    /**
     * @return bound field ID, absent for an unbound reference.
     */
    @Nullable
    public Integer getId() {
      return id;
    }

    /**
     * @return unbound field name, absent for a bound reference.
     */
    @Nullable
    public String getName() {
      return name;
    }
  }

  /** A fully resolved function reference using any Iceberg JSON reference form. */
  public static final class FunctionReference {
    @Nullable private final String catalog;
    private final List<String> identifier;
    private final Form form;

    private FunctionReference(@Nullable String catalog, List<String> identifier, Form form) {
      this.catalog = catalog;
      this.identifier = ModelSupport.immutableList(identifier, "identifier");
      if (this.identifier.isEmpty()) {
        throw new IllegalArgumentException("identifier cannot be empty");
      }
      this.form = Objects.requireNonNull(form, "form cannot be null");
      if (catalog != null && form != Form.OBJECT) {
        throw new IllegalArgumentException("A catalog requires the object function form");
      }
    }

    /**
     * @param name one-part function identifier. @return a string-form function reference.
     */
    public static FunctionReference named(String name) {
      return new FunctionReference(
          null, Collections.singletonList(ModelSupport.requireNonEmpty(name, "name")), Form.STRING);
    }

    /**
     * @param identifier function identifier path.
     * @return an array-form function reference.
     */
    public static FunctionReference identified(List<String> identifier) {
      return new FunctionReference(null, identifier, Form.ARRAY);
    }

    /**
     * @param catalog optional catalog name.
     * @param identifier function identifier path.
     * @return an object-form function reference.
     */
    public static FunctionReference qualified(@Nullable String catalog, List<String> identifier) {
      return new FunctionReference(catalog, identifier, Form.OBJECT);
    }

    /**
     * @return optional catalog name.
     */
    @Nullable
    @JsonIgnore
    public String getCatalog() {
      return catalog;
    }

    /**
     * @return immutable function identifier path.
     */
    @JsonIgnore
    public List<String> getIdentifier() {
      return identifier;
    }

    /**
     * @return one of the valid Iceberg function-reference JSON forms.
     */
    @JsonValue
    public Object toJson() {
      if (form == Form.STRING) {
        return identifier.get(0);
      }
      if (form == Form.ARRAY) {
        return identifier;
      }
      Map<String, Object> value = new LinkedHashMap<>();
      if (catalog != null) {
        value.put("catalog", catalog);
      }
      value.put("identifier", identifier);
      return value;
    }

    /**
     * Reads any valid Iceberg function-reference JSON form.
     *
     * @param value string, string array, or object function reference.
     * @return parsed function reference.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FunctionReference fromJson(Object value) {
      if (value instanceof String) {
        return named((String) value);
      }
      if (value instanceof List) {
        return identified(stringList((List<?>) value, "function identifier"));
      }
      if (value instanceof Map) {
        Map<?, ?> object = (Map<?, ?>) value;
        for (Object key : object.keySet()) {
          if (!"catalog".equals(key) && !"identifier".equals(key)) {
            throw new IllegalArgumentException("Unknown function-reference field: " + key);
          }
        }
        Object catalog = object.get("catalog");
        if (catalog != null && !(catalog instanceof String)) {
          throw new IllegalArgumentException("catalog must be a string");
        }
        Object identifier = object.get("identifier");
        if (!(identifier instanceof List)) {
          throw new IllegalArgumentException("identifier must be a string list");
        }
        return qualified((String) catalog, stringList((List<?>) identifier, "function identifier"));
      }
      throw new IllegalArgumentException("Unsupported function-reference JSON value");
    }

    private static List<String> stringList(List<?> values, String name) {
      ArrayList<String> strings = new ArrayList<>(values.size());
      for (Object value : values) {
        if (!(value instanceof String)) {
          throw new IllegalArgumentException(name + " must contain only strings");
        }
        strings.add(ModelSupport.requireNonEmpty((String) value, name + " part"));
      }
      return strings;
    }

    private enum Form {
      STRING,
      ARRAY,
      OBJECT
    }
  }

  /** A function applied to zero or more value expressions or predicates. */
  public static final class Apply extends Expression {
    @JsonProperty(value = "function", required = true)
    private final FunctionReference function;

    @JsonProperty(value = "arguments", required = true)
    private final List<ExpressionNode> arguments;

    /**
     * Creates a function application.
     *
     * @param function unambiguous function reference.
     * @param arguments ordered value-expression or predicate arguments.
     */
    @JsonCreator
    public Apply(
        @JsonProperty(value = "function", required = true) FunctionReference function,
        @JsonProperty(value = "arguments", required = true) List<ExpressionNode> arguments) {
      super(Type.APPLY);
      this.function = Objects.requireNonNull(function, "function cannot be null");
      this.arguments = ModelSupport.immutableList(arguments, "arguments");
    }

    /**
     * @return unambiguous function reference.
     */
    public FunctionReference getFunction() {
      return function;
    }

    /**
     * @return ordered value-expression or predicate arguments.
     */
    public List<ExpressionNode> getArguments() {
      return arguments;
    }
  }
}
