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
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A two-valued predicate in the public Gravitino V1 wire contract.
 *
 * <p>The node grammar and JSON operator names follow Appendix B of the Apache Iceberg expressions
 * specification.
 */
public abstract class Predicate implements ExpressionNode {

  /** A bare JSON boolean predicate. */
  public static final class Constant extends Predicate {
    private final boolean value;

    /**
     * @param value predicate value.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Constant(boolean value) {
      this.value = value;
    }

    /**
     * @return the bare JSON boolean predicate value.
     */
    @JsonValue
    public boolean getValue() {
      return value;
    }
  }

  /** Logical negation. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class Not extends Predicate {
    @JsonProperty(value = "type", required = true)
    private final String type = "not";

    @JsonProperty(value = "child", required = true)
    private final Predicate child;

    /**
     * @param child predicate to negate.
     */
    @JsonCreator
    public Not(@JsonProperty(value = "child", required = true) Predicate child) {
      this.child = Objects.requireNonNull(child, "child cannot be null");
    }

    /**
     * @return {@code not}.
     */
    public String getType() {
      return type;
    }

    /**
     * @return predicate to negate.
     */
    public Predicate getChild() {
      return child;
    }
  }

  /** A binary logical AND or OR predicate. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class Logical extends Predicate {
    @JsonProperty(value = "type", required = true)
    private final LogicalOperator type;

    @JsonProperty(value = "left", required = true)
    private final Predicate left;

    @JsonProperty(value = "right", required = true)
    private final Predicate right;

    /**
     * Creates a binary logical predicate.
     *
     * @param type AND or OR.
     * @param left left predicate.
     * @param right right predicate.
     */
    @JsonCreator
    public Logical(
        @JsonProperty(value = "type", required = true) LogicalOperator type,
        @JsonProperty(value = "left", required = true) Predicate left,
        @JsonProperty(value = "right", required = true) Predicate right) {
      this.type = Objects.requireNonNull(type, "type cannot be null");
      this.left = Objects.requireNonNull(left, "left cannot be null");
      this.right = Objects.requireNonNull(right, "right cannot be null");
    }

    /**
     * @return AND or OR.
     */
    public LogicalOperator getType() {
      return type;
    }

    /**
     * @return left predicate.
     */
    public Predicate getLeft() {
      return left;
    }

    /**
     * @return right predicate.
     */
    public Predicate getRight() {
      return right;
    }
  }

  /** A unary null or NaN test. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class UnaryTest extends Predicate {
    @JsonProperty(value = "type", required = true)
    private final UnaryOperator type;

    @JsonProperty(value = "child", required = true)
    private final ValueExpression child;

    /**
     * Creates a unary test.
     *
     * @param type null or NaN test operator.
     * @param child value expression being tested.
     */
    @JsonCreator
    public UnaryTest(
        @JsonProperty(value = "type", required = true) UnaryOperator type,
        @JsonProperty(value = "child", required = true) ValueExpression child) {
      this.type = Objects.requireNonNull(type, "type cannot be null");
      this.child = Objects.requireNonNull(child, "child cannot be null");
    }

    /**
     * @return null or NaN test operator.
     */
    public UnaryOperator getType() {
      return type;
    }

    /**
     * @return value expression being tested.
     */
    public ValueExpression getChild() {
      return child;
    }
  }

  /** A null-safe comparison of two value expressions. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class Comparison extends Predicate {
    @JsonProperty(value = "type", required = true)
    private final ComparisonOperator type;

    @JsonProperty(value = "left", required = true)
    private final ValueExpression left;

    @JsonProperty(value = "right", required = true)
    private final ValueExpression right;

    /**
     * Creates a comparison.
     *
     * @param type comparison operator.
     * @param left left value expression.
     * @param right right value expression.
     */
    @JsonCreator
    public Comparison(
        @JsonProperty(value = "type", required = true) ComparisonOperator type,
        @JsonProperty(value = "left", required = true) ValueExpression left,
        @JsonProperty(value = "right", required = true) ValueExpression right) {
      this.type = Objects.requireNonNull(type, "type cannot be null");
      this.left = Objects.requireNonNull(left, "left cannot be null");
      this.right = Objects.requireNonNull(right, "right cannot be null");
    }

    /**
     * @return comparison operator.
     */
    public ComparisonOperator getType() {
      return type;
    }

    /**
     * @return left value expression.
     */
    public ValueExpression getLeft() {
      return left;
    }

    /**
     * @return right value expression.
     */
    public ValueExpression getRight() {
      return right;
    }
  }

  /** An IN or NOT IN test against a homogeneous constant set. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class SetTest extends Predicate {
    @JsonProperty(value = "type", required = true)
    private final SetOperator type;

    @JsonProperty(value = "child", required = true)
    private final ValueExpression child;

    @JsonProperty(value = "values", required = true)
    private final LiteralCollection values;

    /**
     * Creates a set test.
     *
     * @param type IN or NOT IN.
     * @param child value expression being tested.
     * @param values homogeneous non-null, non-NaN constant set.
     */
    @JsonCreator
    public SetTest(
        @JsonProperty(value = "type", required = true) SetOperator type,
        @JsonProperty(value = "child", required = true) ValueExpression child,
        @JsonProperty(value = "values", required = true) LiteralCollection values) {
      this.type = Objects.requireNonNull(type, "type cannot be null");
      this.child = Objects.requireNonNull(child, "child cannot be null");
      this.values = Objects.requireNonNull(values, "values cannot be null");
    }

    /**
     * @return IN or NOT IN.
     */
    public SetOperator getType() {
      return type;
    }

    /**
     * @return value expression being tested.
     */
    public ValueExpression getChild() {
      return child;
    }

    /**
     * @return homogeneous constant set.
     */
    public LiteralCollection getValues() {
      return values;
    }
  }

  /** Object or shorthand-array literal collection accepted by an Iceberg set predicate. */
  public interface LiteralCollection {}

  /** A shorthand array-form literal collection. */
  public static final class LiteralList implements LiteralCollection {
    private final List<ValueExpression> values;

    /**
     * @param values raw or object-form literals.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public LiteralList(List<ValueExpression> values) {
      List<ValueExpression> copy = ModelSupport.immutableList(values, "values");
      ModelSupport.requireJsonContainerSize(copy.size(), "values");
      for (ValueExpression value : copy) {
        if (!(value instanceof LiteralValue) && !(value instanceof Expression.Literal)) {
          throw new IllegalArgumentException("Literal list may contain only literals");
        }
      }
      this.values = copy;
    }

    /**
     * @return raw or object-form literals.
     */
    @JsonValue
    public List<ValueExpression> getValues() {
      return values;
    }
  }

  /** A canonical object-form homogeneous literal set. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class LiteralSet implements LiteralCollection {
    @JsonProperty(value = "type", required = true)
    private final String type = "literals";

    @JsonProperty(value = "values", required = true)
    private final List<Object> values;

    @JsonProperty(value = "data-type", required = true)
    private final DataType dataType;

    /**
     * Creates a homogeneous literal set.
     *
     * @param values non-null, non-NaN Iceberg JSON single values.
     * @param dataType explicit element data type.
     */
    @JsonCreator
    public LiteralSet(
        @JsonProperty(value = "values", required = true) List<Object> values,
        @JsonProperty(value = "data-type", required = true) DataType dataType) {
      Objects.requireNonNull(values, "values cannot be null");
      ModelSupport.requireJsonContainerSize(values.size(), "values");
      ArrayList<Object> copy = new ArrayList<>(values.size());
      for (int index = 0; index < values.size(); index++) {
        Object value = values.get(index);
        if (value == null) {
          throw new IllegalArgumentException("Set literal cannot be null");
        }
        copy.add(ModelSupport.immutableJsonValue(value, "values[" + index + "]"));
      }
      this.values = Collections.unmodifiableList(copy);
      this.dataType = Objects.requireNonNull(dataType, "dataType cannot be null");
    }

    /**
     * @return {@code literals}.
     */
    public String getType() {
      return type;
    }

    /**
     * @return homogeneous non-null, non-NaN Iceberg JSON single values.
     */
    public List<Object> getValues() {
      return values;
    }

    /**
     * @return explicit element data type.
     */
    @JsonProperty("data-type")
    public DataType getDataType() {
      return dataType;
    }
  }

  /** Binary logical operators. */
  public enum LogicalOperator {
    /** Conjunction. */
    AND("and"),
    /** Disjunction. */
    OR("or");

    private final String value;

    LogicalOperator(String value) {
      this.value = value;
    }

    /**
     * @return stable Iceberg JSON operator name.
     */
    @JsonValue
    public String value() {
      return value;
    }
  }

  /** Unary test operators. */
  public enum UnaryOperator {
    /** Is null. */
    IS_NULL("is-null"),
    /** Is not null. */
    NOT_NULL("not-null"),
    /** Is IEEE 754 NaN. */
    IS_NAN("is-nan"),
    /** Is not IEEE 754 NaN. */
    NOT_NAN("not-nan");

    private final String value;

    UnaryOperator(String value) {
      this.value = value;
    }

    /**
     * @return stable Iceberg JSON operator name.
     */
    @JsonValue
    public String value() {
      return value;
    }
  }

  /** Comparison operators. */
  public enum ComparisonOperator {
    /** Less than. */
    LT("lt"),
    /** Less than or equal. */
    LT_EQ("lt-eq"),
    /** Greater than. */
    GT("gt"),
    /** Greater than or equal. */
    GT_EQ("gt-eq"),
    /** Null-safe equality. */
    EQ("eq"),
    /** Null-safe inequality. */
    NOT_EQ("not-eq"),
    /** String starts with. */
    STARTS_WITH("starts-with"),
    /** String does not start with. */
    NOT_STARTS_WITH("not-starts-with");

    private final String value;

    ComparisonOperator(String value) {
      this.value = value;
    }

    /**
     * @return stable Iceberg JSON operator name.
     */
    @JsonValue
    public String value() {
      return value;
    }
  }

  /** Set test operators. */
  public enum SetOperator {
    /** Membership. */
    IN("in"),
    /** Non-membership. */
    NOT_IN("not-in");

    private final String value;

    SetOperator(String value) {
      this.value = value;
    }

    /**
     * @return stable Iceberg JSON operator name.
     */
    @JsonValue
    public String value() {
      return value;
    }
  }
}
