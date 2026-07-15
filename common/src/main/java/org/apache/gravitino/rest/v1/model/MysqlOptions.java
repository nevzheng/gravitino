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

/** Immutable MySQL table-engine options. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class MysqlOptions {

  private static final Set<String> SUPPORTED_ENGINES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "NDBCLUSTER",
                  "FEDERATED",
                  "MEMORY",
                  "InnoDB",
                  "PERFORMANCE_SCHEMA",
                  "MyISAM",
                  "NDBINFO",
                  "MRG_MYISAM",
                  "BLACKHOLE",
                  "CSV",
                  "ARCHIVE")));

  @Nullable
  @JsonProperty("engine")
  private final String engine;

  @Nullable
  @JsonProperty("autoIncrementOffset")
  private final Integer autoIncrementOffset;

  /**
   * Creates public V1 MySQL options.
   *
   * @param engine optional supported MySQL storage engine.
   * @param autoIncrementOffset optional initial value for an auto-increment column.
   */
  @JsonCreator
  public MysqlOptions(
      @Nullable @JsonProperty("engine") String engine,
      @Nullable @JsonProperty("autoIncrementOffset") Integer autoIncrementOffset) {
    if (engine != null && !SUPPORTED_ENGINES.contains(engine)) {
      throw new IllegalArgumentException("engine is not a supported MySQL engine");
    }
    if (autoIncrementOffset != null && autoIncrementOffset < 1) {
      throw new IllegalArgumentException("autoIncrementOffset must be positive");
    }
    if (engine == null && autoIncrementOffset == null) {
      throw new IllegalArgumentException("mysqlOptions must contain at least one option");
    }
    this.engine = engine;
    this.autoIncrementOffset = autoIncrementOffset;
  }

  /**
   * @return optional MySQL storage engine.
   */
  @Nullable
  public String getEngine() {
    return engine;
  }

  /**
   * @return optional initial value for an auto-increment column.
   */
  @Nullable
  public Integer getAutoIncrementOffset() {
    return autoIncrementOffset;
  }
}
