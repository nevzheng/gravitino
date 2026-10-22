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
package org.apache.gravitino.storage.relational;

import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The server values the static parts of the relational store read: the metrics system the
 * connection pool and the change-log metrics register with, the entity cache flag the meta services
 * route their reads by, and the ID generator nested schema ancestors take their IDs from.
 *
 * <p>The MyBatis session factory and the {@code *MetaService} singletons are static, so they cannot
 * take these values by constructor. The component seeds them once, when it builds the entity store
 * ({@code StorageModule}); nothing reads them from {@code GravitinoEnv}. Until the store is seeded
 * the cache flag is on and the metrics system and ID generator are absent, which is what the store
 * read from an uninitialized environment before.
 */
public final class RelationalStoreSeeds {

  @Nullable private static volatile MetricsSystem metricsSystem;
  private static volatile boolean cacheEnabled = true;
  @Nullable private static volatile IdGenerator idGenerator;

  private RelationalStoreSeeds() {}

  /**
   * Seeds the store with the values of a server.
   *
   * @param config the server configuration, which sets the entity cache flag
   * @param metricsSystem the metrics system, or null to register no metrics
   * @param idGenerator the ID generator, or null when there is none
   */
  public static void seed(
      Config config, @Nullable MetricsSystem metricsSystem, @Nullable IdGenerator idGenerator) {
    seed(metricsSystem, config.get(Configs.CACHE_ENABLED), idGenerator);
  }

  /**
   * Seeds the store with the given values.
   *
   * @param metricsSystem the metrics system, or null to register no metrics
   * @param cacheEnabled whether the entity cache is enabled
   * @param idGenerator the ID generator, or null when there is none
   */
  public static void seed(
      @Nullable MetricsSystem metricsSystem,
      boolean cacheEnabled,
      @Nullable IdGenerator idGenerator) {
    RelationalStoreSeeds.metricsSystem = metricsSystem;
    RelationalStoreSeeds.cacheEnabled = cacheEnabled;
    RelationalStoreSeeds.idGenerator = idGenerator;
  }

  /** Puts back the values of an unseeded store. */
  public static void reset() {
    seed(null, true, null);
  }

  /**
   * Returns the metrics system the store registers its metrics sources with.
   *
   * @return the metrics system, or null when the store was seeded without one
   */
  @Nullable
  public static MetricsSystem metricsSystem() {
    return metricsSystem;
  }

  /**
   * Returns whether the entity cache is enabled, which makes the meta services read by parent ID.
   *
   * @return whether the entity cache is enabled; true until the store is seeded
   */
  public static boolean cacheEnabled() {
    return cacheEnabled;
  }

  /**
   * Returns the ID generator nested schema ancestors take their IDs from.
   *
   * @return the ID generator, or null when the store was seeded without one
   */
  @Nullable
  public static IdGenerator idGenerator() {
    return idGenerator;
  }
}
