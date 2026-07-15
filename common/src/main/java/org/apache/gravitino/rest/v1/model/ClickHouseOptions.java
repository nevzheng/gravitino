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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/** Immutable ClickHouse table-engine and cluster options. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class ClickHouseOptions {

  private static final Set<String> SUPPORTED_ENGINES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "MergeTree",
                  "ReplacingMergeTree",
                  "SummingMergeTree",
                  "AggregatingMergeTree",
                  "CollapsingMergeTree",
                  "VersionedCollapsingMergeTree",
                  "GraphiteMergeTree",
                  "TinyLog",
                  "StripeLog",
                  "Log",
                  "ODBC",
                  "JDBC",
                  "MySQL",
                  "MongoDB",
                  "Redis",
                  "HDFS",
                  "S3",
                  "Kafka",
                  "EmbeddedRocksDB",
                  "RabbitMQ",
                  "PostgreSQL",
                  "S3Queue",
                  "TimeSeries",
                  "Distributed",
                  "Dictionary",
                  "Merge",
                  "File",
                  "Null",
                  "Set",
                  "Join",
                  "URL",
                  "View",
                  "Memory",
                  "Buffer",
                  "KeeperMap")));

  @Nullable
  @JsonProperty("engine")
  private final String engine;

  @Nullable
  @JsonProperty("onCluster")
  private final Boolean onCluster;

  @Nullable
  @JsonProperty("clusterName")
  private final String clusterName;

  @Nullable
  @JsonProperty("remoteDatabase")
  private final String remoteDatabase;

  @Nullable
  @JsonProperty("remoteTable")
  private final String remoteTable;

  @Nullable
  @JsonProperty("shardingKey")
  private final String shardingKey;

  /**
   * Creates public V1 ClickHouse options.
   *
   * @param engine optional supported ClickHouse table engine.
   * @param onCluster optional request to create on a ClickHouse cluster.
   * @param clusterName optional ClickHouse cluster name.
   * @param remoteDatabase optional remote database for a Distributed table.
   * @param remoteTable optional remote table for a Distributed table.
   * @param shardingKey optional sharding expression for a Distributed table.
   */
  @JsonCreator
  public ClickHouseOptions(
      @Nullable @JsonProperty("engine") String engine,
      @Nullable @JsonProperty("onCluster") Boolean onCluster,
      @Nullable @JsonProperty("clusterName") String clusterName,
      @Nullable @JsonProperty("remoteDatabase") String remoteDatabase,
      @Nullable @JsonProperty("remoteTable") String remoteTable,
      @Nullable @JsonProperty("shardingKey") String shardingKey) {
    if (engine != null && !SUPPORTED_ENGINES.contains(engine)) {
      throw new IllegalArgumentException("engine is not a supported ClickHouse engine");
    }
    this.engine = engine;
    this.onCluster = onCluster;
    this.clusterName =
        ModelSupport.requireNullableBoundedNonblankString(clusterName, "clusterName", 255);
    this.remoteDatabase =
        ModelSupport.requireNullableBoundedNonblankString(remoteDatabase, "remoteDatabase", 255);
    this.remoteTable =
        ModelSupport.requireNullableBoundedNonblankString(remoteTable, "remoteTable", 255);
    this.shardingKey =
        ModelSupport.requireNullableBoundedNonblankString(shardingKey, "shardingKey", 4096);
    if (engine == null
        && onCluster == null
        && this.clusterName == null
        && this.remoteDatabase == null
        && this.remoteTable == null
        && this.shardingKey == null) {
      throw new IllegalArgumentException("clickhouseOptions must contain at least one option");
    }
    if (Boolean.TRUE.equals(onCluster) && this.clusterName == null) {
      throw new IllegalArgumentException("clusterName is required when onCluster is true");
    }
    if ("Distributed".equals(engine)
        && (this.remoteDatabase == null || this.remoteTable == null || this.shardingKey == null)) {
      throw new IllegalArgumentException(
          "remoteDatabase, remoteTable, and shardingKey are required for the Distributed engine");
    }
  }

  /**
   * @return optional ClickHouse table engine.
   */
  @Nullable
  public String getEngine() {
    return engine;
  }

  /**
   * @return whether creation uses ClickHouse {@code ON CLUSTER}, when specified.
   */
  @Nullable
  public Boolean getOnCluster() {
    return onCluster;
  }

  /**
   * @return optional ClickHouse cluster name.
   */
  @Nullable
  public String getClusterName() {
    return clusterName;
  }

  /**
   * @return optional remote database for a Distributed table.
   */
  @Nullable
  public String getRemoteDatabase() {
    return remoteDatabase;
  }

  /**
   * @return optional remote table for a Distributed table.
   */
  @Nullable
  public String getRemoteTable() {
    return remoteTable;
  }

  /**
   * @return optional sharding expression for a Distributed table.
   */
  @Nullable
  public String getShardingKey() {
    return shardingKey;
  }
}
