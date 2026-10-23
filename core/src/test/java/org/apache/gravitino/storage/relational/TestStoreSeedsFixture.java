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

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestStoreSeedsFixture {

  @Test
  public void testSeedsTheValuesAndRestoresThePreviousOnes() {
    MetricsSystem previousMetricsSystem = RelationalStoreSeeds.metricsSystem();
    boolean previousCacheEnabled = RelationalStoreSeeds.cacheEnabled();
    IdGenerator previousIdGenerator = RelationalStoreSeeds.idGenerator();
    String previousSeparator = HierarchicalSchemaUtil.schemaSeparator();
    Config config = new Config(false) {};
    config.set(Configs.CACHE_ENABLED, !previousCacheEnabled);
    config.set(Configs.SCHEMA_SEPARATOR, "|");
    IdGenerator idGenerator = Mockito.mock(IdGenerator.class);
    MetricsSystem metricsSystem = Mockito.mock(MetricsSystem.class);

    try (StoreSeedsFixture ignored =
        new StoreSeedsFixture()
            .withConfig(config)
            .withIdGenerator(idGenerator)
            .withMetricsSystem(metricsSystem)) {
      Assertions.assertEquals(!previousCacheEnabled, RelationalStoreSeeds.cacheEnabled());
      Assertions.assertEquals("|", HierarchicalSchemaUtil.schemaSeparator());
      Assertions.assertSame(idGenerator, RelationalStoreSeeds.idGenerator());
      Assertions.assertSame(metricsSystem, RelationalStoreSeeds.metricsSystem());
    }

    Assertions.assertEquals(previousCacheEnabled, RelationalStoreSeeds.cacheEnabled());
    Assertions.assertEquals(previousSeparator, HierarchicalSchemaUtil.schemaSeparator());
    Assertions.assertSame(previousIdGenerator, RelationalStoreSeeds.idGenerator());
    Assertions.assertSame(previousMetricsSystem, RelationalStoreSeeds.metricsSystem());
  }

  @Test
  public void testReseedingRestoresTheOriginalValue() {
    IdGenerator previousIdGenerator = RelationalStoreSeeds.idGenerator();
    IdGenerator first = Mockito.mock(IdGenerator.class);
    IdGenerator second = Mockito.mock(IdGenerator.class);

    StoreSeedsFixture binding = new StoreSeedsFixture().withIdGenerator(first);
    binding.withIdGenerator(second);
    Assertions.assertSame(second, RelationalStoreSeeds.idGenerator());

    binding.close();
    Assertions.assertSame(previousIdGenerator, RelationalStoreSeeds.idGenerator());
  }

  @Test
  public void testNullConfigSeedsTheDefaultsAndCloseIsIdempotent() {
    boolean previousCacheEnabled = RelationalStoreSeeds.cacheEnabled();
    String previousSeparator = HierarchicalSchemaUtil.schemaSeparator();

    StoreSeedsFixture binding = new StoreSeedsFixture().withConfig(null);
    Assertions.assertTrue(RelationalStoreSeeds.cacheEnabled());
    Assertions.assertEquals(
        Configs.SCHEMA_SEPARATOR.getDefaultValue(), HierarchicalSchemaUtil.schemaSeparator());

    binding.close();
    binding.close();
    Assertions.assertEquals(previousCacheEnabled, RelationalStoreSeeds.cacheEnabled());
    Assertions.assertEquals(previousSeparator, HierarchicalSchemaUtil.schemaSeparator());
  }
}
