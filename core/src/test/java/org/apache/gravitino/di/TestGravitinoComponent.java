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
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestGravitinoComponent {

  private Config config;
  private FakeLegacyBridge legacy;
  private GravitinoComponent component;

  @BeforeEach
  public void setUp() {
    config = new Config(false) {};
    legacy = new FakeLegacyBridge();
    component = DaggerGravitinoComponent.factory().create(config, legacy);
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
  }

  @Test
  public void testOwnedObjectsMatchWhatGravitinoEnvBuilt() {
    assertInstanceOf(RandomIdGenerator.class, component.idGenerator());
    assertNotNull(component.metricsSystem().getMetricsSource(MetricsSource.JVM_METRIC_NAME));
  }

  @Test
  public void testSingletonsArePerComponent() {
    GravitinoComponent other = DaggerGravitinoComponent.factory().create(config, legacy);

    assertNotSame(component.lockManager(), other.lockManager());
    assertNotSame(component.idGenerator(), other.idGenerator());
    assertNotSame(component.metricsSystem(), other.metricsSystem());
  }

  @Test
  public void testLegacyObjectsComeFromTheBridge() {
    assertSame(legacy.entityStore, component.entityStore());
    assertSame(legacy.catalogManager, component.catalogManager());
    assertSame(legacy.secretManager, component.secretManager());
    assertSame(legacy.eventBus, component.eventBus());
  }

  static class FakeLegacyBridge implements LegacyBridge {
    EntityStore entityStore = mock(EntityStore.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    SecretManager secretManager = mock(SecretManager.class);
    EventBus eventBus = mock(EventBus.class);

    @Override
    public EntityStore entityStore() {
      return entityStore;
    }

    @Override
    public CatalogManager catalogManager() {
      return catalogManager;
    }

    @Override
    public SecretManager secretManager() {
      return secretManager;
    }

    @Override
    public EventBus eventBus() {
      return eventBus;
    }
  }
}
