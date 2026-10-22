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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestRelationalStoreSeeds {

  private MetricsSystem previousMetricsSystem;
  private boolean previousCacheEnabled;
  private IdGenerator previousIdGenerator;

  @BeforeEach
  public void remember() {
    previousMetricsSystem = RelationalStoreSeeds.metricsSystem();
    previousCacheEnabled = RelationalStoreSeeds.cacheEnabled();
    previousIdGenerator = RelationalStoreSeeds.idGenerator();
  }

  @AfterEach
  public void restore() {
    RelationalStoreSeeds.seed(previousMetricsSystem, previousCacheEnabled, previousIdGenerator);
  }

  @Test
  public void testSeedsFromTheConfiguration() {
    Config config = new Config(false) {};
    config.set(Configs.CACHE_ENABLED, false);
    MetricsSystem metricsSystem = Mockito.mock(MetricsSystem.class);
    IdGenerator idGenerator = Mockito.mock(IdGenerator.class);

    RelationalStoreSeeds.seed(config, metricsSystem, idGenerator);

    Assertions.assertSame(metricsSystem, RelationalStoreSeeds.metricsSystem());
    Assertions.assertFalse(RelationalStoreSeeds.cacheEnabled());
    Assertions.assertSame(idGenerator, RelationalStoreSeeds.idGenerator());
  }

  @Test
  public void testResetPutsBackTheValuesOfAnUnseededStore() {
    RelationalStoreSeeds.seed(
        Mockito.mock(MetricsSystem.class), false, Mockito.mock(IdGenerator.class));

    RelationalStoreSeeds.reset();

    Assertions.assertNull(RelationalStoreSeeds.metricsSystem());
    Assertions.assertTrue(RelationalStoreSeeds.cacheEnabled());
    Assertions.assertNull(RelationalStoreSeeds.idGenerator());
  }
}
