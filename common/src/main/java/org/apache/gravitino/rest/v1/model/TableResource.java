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
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** A table resource returned directly by the public Gravitino V1 API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TableResource {

  @JsonProperty(value = "resourceName", required = true)
  private final String resourceName;

  @JsonProperty(value = "name", required = true)
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @JsonProperty(value = "columns", required = true)
  private final List<Column> columns;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  @JsonProperty(value = "partitioning", required = true)
  private final List<Transform> partitioning;

  @Nullable
  @JsonProperty("distribution")
  private final Distribution distribution;

  @JsonProperty(value = "sortOrders", required = true)
  private final List<SortOrder> sortOrders;

  @JsonProperty(value = "indexes", required = true)
  private final List<Index> indexes;

  @Nullable
  @JsonProperty("audit")
  private final Audit audit;

  /**
   * Creates a table resource.
   *
   * @param resourceName canonical resource name containing the full hierarchy.
   * @param name local table name.
   * @param comment optional table comment.
   * @param columns ordered columns.
   * @param properties public table properties.
   * @param partitioning physical partition transforms.
   * @param distribution optional physical data distribution.
   * @param sortOrders physical sort orders.
   * @param indexes table indexes.
   * @param audit optional, potentially partial audit record.
   */
  @JsonCreator
  public TableResource(
      @JsonProperty(value = "resourceName", required = true) String resourceName,
      @JsonProperty(value = "name", required = true) String name,
      @Nullable @JsonProperty("comment") String comment,
      @JsonProperty(value = "columns", required = true) List<Column> columns,
      @JsonProperty(value = "properties", required = true) Map<String, String> properties,
      @JsonProperty(value = "partitioning", required = true) List<Transform> partitioning,
      @Nullable @JsonProperty("distribution") Distribution distribution,
      @JsonProperty(value = "sortOrders", required = true) List<SortOrder> sortOrders,
      @JsonProperty(value = "indexes", required = true) List<Index> indexes,
      @Nullable @JsonProperty("audit") Audit audit) {
    this.resourceName = ModelSupport.requireNonEmpty(resourceName, "resourceName");
    this.name = ModelSupport.requireNonEmpty(name, "name");
    this.comment = comment;
    this.columns = ModelSupport.immutableList(columns, "columns");
    this.properties = ModelSupport.immutableMap(properties, "properties");
    this.partitioning = ModelSupport.immutableList(partitioning, "partitioning");
    this.distribution = distribution;
    this.sortOrders = ModelSupport.immutableList(sortOrders, "sortOrders");
    this.indexes = ModelSupport.immutableList(indexes, "indexes");
    this.audit = audit;
  }

  /**
   * @return canonical resource name containing the full hierarchy.
   */
  public String getResourceName() {
    return resourceName;
  }

  /**
   * @return local table name.
   */
  public String getName() {
    return name;
  }

  /**
   * @return optional table comment.
   */
  @Nullable
  public String getComment() {
    return comment;
  }

  /**
   * @return ordered columns.
   */
  public List<Column> getColumns() {
    return columns;
  }

  /**
   * @return immutable public table properties.
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  /**
   * @return physical partition transforms.
   */
  public List<Transform> getPartitioning() {
    return partitioning;
  }

  /**
   * @return optional physical data distribution.
   */
  @Nullable
  public Distribution getDistribution() {
    return distribution;
  }

  /**
   * @return physical sort orders.
   */
  public List<SortOrder> getSortOrders() {
    return sortOrders;
  }

  /**
   * @return table indexes.
   */
  public List<Index> getIndexes() {
    return indexes;
  }

  /**
   * @return optional, potentially partial audit record.
   */
  @Nullable
  public Audit getAudit() {
    return audit;
  }
}
