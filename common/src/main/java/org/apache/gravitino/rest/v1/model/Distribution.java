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
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** The physical data distribution in the public Gravitino V1 wire contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class Distribution {

  @JsonProperty(value = "strategy", required = true)
  private final Strategy strategy;

  @Nullable
  @JsonProperty("bucketCount")
  private final Integer bucketCount;

  @JsonProperty(value = "expressions", required = true)
  private final List<ValueExpression> expressions;

  /**
   * Creates a distribution.
   *
   * @param strategy distribution strategy.
   * @param bucketCount explicit positive bucket count, absent when automatic or unused.
   * @param expressions ordered distribution expressions.
   */
  @JsonCreator
  public Distribution(
      @JsonProperty(value = "strategy", required = true) Strategy strategy,
      @Nullable @JsonProperty("bucketCount") Integer bucketCount,
      @JsonProperty(value = "expressions", required = true) List<ValueExpression> expressions) {
    this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
    if (bucketCount != null && bucketCount < 1) {
      throw new IllegalArgumentException("bucketCount must be positive");
    }
    if (strategy == Strategy.NONE && bucketCount != null) {
      throw new IllegalArgumentException("NONE distribution cannot declare bucketCount");
    }
    this.bucketCount = bucketCount;
    this.expressions = ModelSupport.immutableList(expressions, "expressions");
  }

  /**
   * @return distribution strategy.
   */
  public Strategy getStrategy() {
    return strategy;
  }

  /**
   * @return explicit bucket count, absent when automatic or unused.
   */
  @Nullable
  public Integer getBucketCount() {
    return bucketCount;
  }

  /**
   * @return ordered distribution expressions.
   */
  public List<ValueExpression> getExpressions() {
    return expressions;
  }

  /** Distribution strategies supported by V1. */
  public enum Strategy {
    /** No explicit distribution. */
    NONE,
    /** Hash distribution. */
    HASH,
    /** Range distribution. */
    RANGE,
    /** Even distribution. */
    EVEN;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }
}
