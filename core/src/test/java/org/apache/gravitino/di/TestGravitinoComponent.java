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
package org.apache.gravitino.di;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.stats.storage.MemoryPartitionStatsStorageFactory;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestGravitinoComponent {

  private Config config;
  private GravitinoComponent component;

  /**
   * Returns a configuration under which every object the component owns can be built against fake
   * leaves.
   *
   * @return the configuration
   */
  static Config testConfig() {
    Config config = new Config(false) {};
    config.set(
        Configs.PARTITION_STATS_STORAGE_FACTORY_CLASS,
        MemoryPartitionStatsStorageFactory.class.getCanonicalName());
    config.set(Configs.SERVICE_ADMINS, Collections.singletonList("admin"));
    return config;
  }

  @BeforeEach
  public void setUp() {
    config = testConfig();
    component = DaggerGravitinoComponent.factory().create(config);
  }

  @Test
  public void testConfigIsTheBoundInstance() {
    assertSame(config, component.config());
  }

  @Test
  public void testOwnedObjectsAreSingletons() {
    assertNotNull(component.lockManager());
    assertSame(component.lockManager(), component.lockManager());
    assertSame(component.idGenerator(), component.idGenerator());
    assertSame(component.metricsSystem(), component.metricsSystem());
    assertSame(component.secretManager(), component.secretManager());
    assertSame(component.kmsClientRegistry(), component.kmsClientRegistry());
    assertSame(component.eventBus(), component.eventBus());
    assertSame(component.eventListenerManager(), component.eventListenerManager());
    assertSame(component.auditLogManager(), component.auditLogManager());
  }

  @Test
  public void testOwnedObjectsMatchWhatGravitinoEnvBuilt() {
    assertInstanceOf(RandomIdGenerator.class, component.idGenerator());
    assertNotNull(component.metricsSystem().getMetricsSource(MetricsSource.JVM_METRIC_NAME));
  }

  @Test
  public void testSingletonsArePerComponent() {
    GravitinoComponent other = DaggerGravitinoComponent.factory().create(config);

    assertNotSame(component.lockManager(), other.lockManager());
    assertNotSame(component.idGenerator(), other.idGenerator());
    assertNotSame(component.metricsSystem(), other.metricsSystem());
    assertNotSame(component.secretManager(), other.secretManager());
    assertNotSame(component.eventBus(), other.eventBus());
  }
}
