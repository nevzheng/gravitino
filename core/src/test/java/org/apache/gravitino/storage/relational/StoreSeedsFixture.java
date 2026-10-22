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
import org.apache.gravitino.utils.HierarchicalSchemaUtil;

/**
 * Seeds the static values the relational store reads, and puts the previous values back on {@link
 * #close()}.
 *
 * <p>The meta services, {@code POStorageReadRouting}, the MyBatis session factory and {@link
 * RelationalEntityStore} are not part of the component: they read the cache flag, the ID generator
 * (nested schema ancestors) and the metrics system from {@link RelationalStoreSeeds}, and the
 * schema separator from {@link HierarchicalSchemaUtil}. The component seeds them when it builds the
 * entity store. Store tests that build a store without the component seed them here, and only here,
 * so that every seed is undone and no test depends on what an earlier one left behind. This fixture
 * goes away once the meta services become instances.
 *
 * <pre>{@code
 * try (StoreSeedsFixture env = new StoreSeedsFixture().withConfig(config)) {
 *   ...
 * }
 * }</pre>
 */
public final class StoreSeedsFixture implements AutoCloseable {

  private final MetricsSystem previousMetricsSystem = RelationalStoreSeeds.metricsSystem();
  private final boolean previousCacheEnabled = RelationalStoreSeeds.cacheEnabled();
  private final IdGenerator previousIdGenerator = RelationalStoreSeeds.idGenerator();
  private final String previousSeparator = HierarchicalSchemaUtil.schemaSeparator();

  /** Creates a binding that has not changed anything yet. */
  public StoreSeedsFixture() {}

  /**
   * Seeds the cache flag and the schema separator a server configuration sets.
   *
   * @param config the configuration, or null for the values of a store without one (cache on,
   *     default separator)
   * @return this binding
   */
  public StoreSeedsFixture withConfig(@Nullable Config config) {
    RelationalStoreSeeds.seed(
        RelationalStoreSeeds.metricsSystem(),
        config == null || config.get(Configs.CACHE_ENABLED),
        RelationalStoreSeeds.idGenerator());
    HierarchicalSchemaUtil.seedSchemaSeparator(config);
    return this;
  }

  /**
   * Seeds the ID generator the store reads.
   *
   * @param idGenerator the ID generator, or null to seed none
   * @return this binding
   */
  public StoreSeedsFixture withIdGenerator(@Nullable IdGenerator idGenerator) {
    RelationalStoreSeeds.seed(
        RelationalStoreSeeds.metricsSystem(), RelationalStoreSeeds.cacheEnabled(), idGenerator);
    return this;
  }

  /**
   * Seeds the metrics system the store registers its sources with.
   *
   * @param metricsSystem the metrics system, or null to seed none
   * @return this binding
   */
  public StoreSeedsFixture withMetricsSystem(@Nullable MetricsSystem metricsSystem) {
    RelationalStoreSeeds.seed(
        metricsSystem, RelationalStoreSeeds.cacheEnabled(), RelationalStoreSeeds.idGenerator());
    return this;
  }

  /** Puts back the values seeded when this binding was created. */
  @Override
  public void close() {
    RelationalStoreSeeds.seed(previousMetricsSystem, previousCacheEnabled, previousIdGenerator);
    Config separator = new Config(false) {};
    separator.set(Configs.SCHEMA_SEPARATOR, previousSeparator);
    HierarchicalSchemaUtil.seedSchemaSeparator(separator);
  }
}
