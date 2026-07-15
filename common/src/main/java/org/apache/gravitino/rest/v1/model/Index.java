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
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** A table index in the public Gravitino V1 wire contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class Index {

  @JsonProperty(value = "type", required = true)
  private final Type type;

  @Nullable
  @JsonProperty("name")
  private final String name;

  @JsonProperty(value = "fieldNames", required = true)
  private final List<List<String>> fieldNames;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  /**
   * Creates an index.
   *
   * @param type index type.
   * @param name optional index name.
   * @param fieldNames ordered indexed field-name paths.
   * @param properties index configuration properties.
   */
  @JsonCreator
  public Index(
      @JsonProperty(value = "type", required = true) Type type,
      @Nullable @JsonProperty("name") String name,
      @JsonProperty(value = "fieldNames", required = true) List<List<String>> fieldNames,
      @JsonProperty(value = "properties", required = true) Map<String, String> properties) {
    this.type = Objects.requireNonNull(type, "type cannot be null");
    this.name = name;
    this.fieldNames = ModelSupport.immutableFieldNames(fieldNames, "fieldNames");
    this.properties = ModelSupport.immutableMap(properties, "properties");
  }

  /**
   * @return index type.
   */
  public Type getType() {
    return type;
  }

  /**
   * @return optional index name.
   */
  @Nullable
  public String getName() {
    return name;
  }

  /**
   * @return ordered indexed field-name paths.
   */
  public List<List<String>> getFieldNames() {
    return fieldNames;
  }

  /**
   * @return immutable index configuration properties.
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  /** Index types supported by V1. */
  public enum Type {
    /** Primary key. */
    PRIMARY_KEY,
    /** Unique key. */
    UNIQUE_KEY,
    /** Scalar index. */
    SCALAR,
    /** B-tree index. */
    BTREE,
    /** Bitmap index. */
    BITMAP,
    /** Label-list index. */
    LABEL_LIST,
    /** Inverted index. */
    INVERTED,
    /** Vector index. */
    VECTOR,
    /** IVF flat vector index. */
    IVF_FLAT,
    /** IVF scalar-quantization vector index. */
    IVF_SQ,
    /** IVF product-quantization vector index. */
    IVF_PQ,
    /** IVF HNSW scalar-quantization vector index. */
    IVF_HNSW_SQ,
    /** IVF HNSW product-quantization vector index. */
    IVF_HNSW_PQ,
    /** Min-max data-skipping index. */
    DATA_SKIPPING_MINMAX,
    /** Bloom-filter data-skipping index. */
    DATA_SKIPPING_BLOOM_FILTER,
    /** Set data-skipping index. */
    DATA_SKIPPING_SET;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }
}
