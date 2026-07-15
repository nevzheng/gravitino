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
import javax.annotation.Nullable;

/** A request to create a relational table through the public Gravitino V1 API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class TableCreateRequest {

  @JsonProperty(value = "name", required = true)
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @JsonProperty(value = "columns", required = true)
  private final List<Column> columns;

  @Nullable
  @JsonProperty("storage")
  private final TableStorage storage;

  @Nullable
  @JsonProperty("icebergOptions")
  private final IcebergOptions icebergOptions;

  @Nullable
  @JsonProperty("hiveOptions")
  private final HiveOptions hiveOptions;

  @Nullable
  @JsonProperty("clickhouseOptions")
  private final ClickHouseOptions clickhouseOptions;

  @Nullable
  @JsonProperty("mysqlOptions")
  private final MysqlOptions mysqlOptions;

  @JsonProperty(value = "partitioning", required = true)
  private final List<Transform> partitioning;

  @Nullable
  @JsonProperty("distribution")
  private final Distribution distribution;

  @JsonProperty(value = "sortOrders", required = true)
  private final List<SortOrder> sortOrders;

  @JsonProperty(value = "indexes", required = true)
  private final List<Index> indexes;

  /**
   * Creates a public V1 table-create request.
   *
   * @param name local table name.
   * @param comment optional table comment.
   * @param columns ordered table columns; use an empty list when the connector owns schema
   *     discovery.
   * @param storage optional portable table storage intent.
   * @param icebergOptions optional immutable Iceberg-specific state.
   * @param hiveOptions optional immutable Hive-specific state.
   * @param clickhouseOptions optional immutable ClickHouse-specific state.
   * @param mysqlOptions optional immutable MySQL-specific state.
   * @param partitioning physical partition transforms; use an empty list when unpartitioned.
   * @param distribution optional physical data distribution.
   * @param sortOrders physical sort orders; use an empty list when unsorted.
   * @param indexes table indexes; use an empty list when none are declared.
   */
  @JsonCreator
  public TableCreateRequest(
      @JsonProperty(value = "name", required = true) String name,
      @Nullable @JsonProperty("comment") String comment,
      @JsonProperty(value = "columns", required = true) List<Column> columns,
      @Nullable @JsonProperty("storage") TableStorage storage,
      @Nullable @JsonProperty("icebergOptions") IcebergOptions icebergOptions,
      @Nullable @JsonProperty("hiveOptions") HiveOptions hiveOptions,
      @Nullable @JsonProperty("clickhouseOptions") ClickHouseOptions clickhouseOptions,
      @Nullable @JsonProperty("mysqlOptions") MysqlOptions mysqlOptions,
      @JsonProperty(value = "partitioning", required = true) List<Transform> partitioning,
      @Nullable @JsonProperty("distribution") Distribution distribution,
      @JsonProperty(value = "sortOrders", required = true) List<SortOrder> sortOrders,
      @JsonProperty(value = "indexes", required = true) List<Index> indexes) {
    this.name = ModelSupport.requireIdentifier(name, "name");
    this.comment = ModelSupport.requireNullableComment(comment, "comment");
    this.columns = ModelSupport.immutableBoundedRequestList(columns, "columns", 10_000);
    ModelSupport.requireAtMostOneNonNull(
        "provider options", icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    this.storage = storage;
    this.icebergOptions = icebergOptions;
    this.hiveOptions = hiveOptions;
    this.clickhouseOptions = clickhouseOptions;
    this.mysqlOptions = mysqlOptions;
    this.partitioning =
        ModelSupport.immutableBoundedRequestList(partitioning, "partitioning", 1_024);
    this.distribution = distribution;
    this.sortOrders = ModelSupport.immutableBoundedRequestList(sortOrders, "sortOrders", 1_024);
    this.indexes = ModelSupport.immutableBoundedRequestList(indexes, "indexes", 1_024);
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
   * @return ordered table columns, potentially empty when the connector owns schema discovery.
   */
  public List<Column> getColumns() {
    return columns;
  }

  /**
   * @return optional portable table storage intent.
   */
  @Nullable
  public TableStorage getStorage() {
    return storage;
  }

  /**
   * @return optional immutable Iceberg-specific state.
   */
  @Nullable
  public IcebergOptions getIcebergOptions() {
    return icebergOptions;
  }

  /**
   * @return optional immutable Hive-specific state.
   */
  @Nullable
  public HiveOptions getHiveOptions() {
    return hiveOptions;
  }

  /**
   * @return optional immutable ClickHouse-specific state.
   */
  @Nullable
  public ClickHouseOptions getClickhouseOptions() {
    return clickhouseOptions;
  }

  /**
   * @return optional immutable MySQL-specific state.
   */
  @Nullable
  public MysqlOptions getMysqlOptions() {
    return mysqlOptions;
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
}
