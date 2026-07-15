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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/** A sort order in the public Gravitino V1 wire contract. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class SortOrder {

  @JsonProperty(value = "expression", required = true)
  private final ValueExpression expression;

  @JsonProperty(value = "direction", required = true)
  private final Direction direction;

  @JsonProperty(value = "nullOrdering", required = true)
  private final NullOrdering nullOrdering;

  /**
   * Creates a sort order.
   *
   * @param expression expression being sorted.
   * @param direction ascending or descending direction.
   * @param nullOrdering explicit null ordering.
   */
  @JsonCreator
  public SortOrder(
      @JsonProperty(value = "expression", required = true) ValueExpression expression,
      @JsonProperty(value = "direction", required = true) Direction direction,
      @JsonProperty(value = "nullOrdering", required = true) NullOrdering nullOrdering) {
    this.expression = Objects.requireNonNull(expression, "expression cannot be null");
    this.direction = Objects.requireNonNull(direction, "direction cannot be null");
    this.nullOrdering = Objects.requireNonNull(nullOrdering, "nullOrdering cannot be null");
  }

  /**
   * @return expression being sorted.
   */
  public ValueExpression getExpression() {
    return expression;
  }

  /**
   * @return ascending or descending direction.
   */
  public Direction getDirection() {
    return direction;
  }

  /**
   * @return explicit null ordering.
   */
  public NullOrdering getNullOrdering() {
    return nullOrdering;
  }

  /** Sort directions supported by V1. */
  public enum Direction {
    /** Ascending. */
    ASC,
    /** Descending. */
    DESC;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }

  /** Null ordering choices supported by V1. */
  public enum NullOrdering {
    /** Null values precede non-null values. */
    NULLS_FIRST,
    /** Null values follow non-null values. */
    NULLS_LAST;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }
}
