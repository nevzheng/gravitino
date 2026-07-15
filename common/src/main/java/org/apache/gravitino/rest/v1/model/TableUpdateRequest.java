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

/** The full desired mutable state of a table for a public Gravitino V1 PUT request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class TableUpdateRequest {

  @Nullable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @JsonProperty(value = "comment", required = true)
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
   * Creates a public V1 table desired-state update.
   *
   * @param comment replacement comment, or null to clear it.
   * @param columns complete desired table columns, potentially empty for connector-owned discovery.
   * @param storage complete desired portable table storage intent, potentially absent.
   * @param icebergOptions complete desired Iceberg-specific state, potentially absent.
   * @param hiveOptions complete desired Hive-specific state, potentially absent.
   * @param clickhouseOptions complete desired ClickHouse-specific state, potentially absent.
   * @param mysqlOptions complete desired MySQL-specific state, potentially absent.
   * @param partitioning complete desired partition transforms, potentially empty.
   * @param distribution optional desired physical data distribution.
   * @param sortOrders complete desired sort orders, potentially empty.
   * @param indexes complete desired table indexes, potentially empty.
   */
  @JsonCreator
  public TableUpdateRequest(
      @Nullable @JsonProperty(value = "comment", required = true) String comment,
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
   * @return replacement comment, or null when the comment should be cleared.
   */
  @Nullable
  public String getComment() {
    return comment;
  }

  /**
   * @return immutable complete desired table columns.
   */
  public List<Column> getColumns() {
    return columns;
  }

  /**
   * @return complete desired portable table storage intent, potentially absent.
   */
  @Nullable
  public TableStorage getStorage() {
    return storage;
  }

  /**
   * @return complete desired Iceberg-specific state, potentially absent.
   */
  @Nullable
  public IcebergOptions getIcebergOptions() {
    return icebergOptions;
  }

  /**
   * @return complete desired Hive-specific state, potentially absent.
   */
  @Nullable
  public HiveOptions getHiveOptions() {
    return hiveOptions;
  }

  /**
   * @return complete desired ClickHouse-specific state, potentially absent.
   */
  @Nullable
  public ClickHouseOptions getClickhouseOptions() {
    return clickhouseOptions;
  }

  /**
   * @return complete desired MySQL-specific state, potentially absent.
   */
  @Nullable
  public MysqlOptions getMysqlOptions() {
    return mysqlOptions;
  }

  /**
   * @return immutable complete desired partition transforms.
   */
  public List<Transform> getPartitioning() {
    return partitioning;
  }

  /**
   * @return optional desired physical data distribution.
   */
  @Nullable
  public Distribution getDistribution() {
    return distribution;
  }

  /**
   * @return immutable complete desired sort orders.
   */
  public List<SortOrder> getSortOrders() {
    return sortOrders;
  }

  /**
   * @return immutable complete desired table indexes.
   */
  public List<Index> getIndexes() {
    return indexes;
  }
}
