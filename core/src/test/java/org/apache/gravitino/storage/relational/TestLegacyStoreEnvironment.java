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
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestLegacyStoreEnvironment {

  @Test
  public void testBindsTheValuesAndRestoresThePreviousOnes() {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Config previousConfig = env.config();
    IdGenerator previousIdGenerator = env.idGenerator();
    MetricsSystem previousMetricsSystem = env.metricsSystem();
    Config config = Mockito.mock(Config.class);
    IdGenerator idGenerator = Mockito.mock(IdGenerator.class);
    MetricsSystem metricsSystem = Mockito.mock(MetricsSystem.class);

    try (LegacyStoreEnvironment ignored =
        new LegacyStoreEnvironment()
            .withConfig(config)
            .withIdGenerator(idGenerator)
            .withMetricsSystem(metricsSystem)) {
      Assertions.assertSame(config, env.config());
      Assertions.assertSame(idGenerator, env.idGenerator());
      Assertions.assertSame(metricsSystem, env.metricsSystem());
    }

    Assertions.assertSame(previousConfig, env.config());
    Assertions.assertSame(previousIdGenerator, env.idGenerator());
    Assertions.assertSame(previousMetricsSystem, env.metricsSystem());
  }

  @Test
  public void testRebindingAFieldRestoresTheOriginalValue() {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Config previousConfig = env.config();
    Config first = Mockito.mock(Config.class);
    Config second = Mockito.mock(Config.class);

    LegacyStoreEnvironment binding = new LegacyStoreEnvironment().withConfig(first);
    binding.withConfig(second);
    Assertions.assertSame(second, env.config());

    binding.close();
    Assertions.assertSame(previousConfig, env.config());
  }

  @Test
  public void testBindsNullAndCloseIsIdempotent() {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Config previousConfig = env.config();

    LegacyStoreEnvironment binding = new LegacyStoreEnvironment().withConfig(null);
    Assertions.assertNull(env.config());

    binding.close();
    binding.close();
    Assertions.assertSame(previousConfig, env.config());
  }
}
