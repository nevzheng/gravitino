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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.secret.SecretManager;
import org.junit.jupiter.api.Test;

/** Checks how {@link LegacyModule} exposes the objects of a {@link LegacyBridge}. */
public class TestLegacyBridge {

  @Test
  public void testBindingsReadTheBridgeOnEveryRequest() {
    TestGravitinoComponent.FakeLegacyBridge legacy = new TestGravitinoComponent.FakeLegacyBridge();
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, legacy);
    EntityStore first = component.entityStore();

    EntityStore replacement = mock(EntityStore.class);
    legacy.entityStore = replacement;
    legacy.catalogManager = mock(CatalogManager.class);
    legacy.secretManager = mock(SecretManager.class);
    legacy.eventBus = mock(EventBus.class);

    // The bridge owns these objects, so the graph must not cache what it read before.
    assertSame(replacement, component.entityStore());
    assertSame(legacy.catalogManager, component.catalogManager());
    assertSame(legacy.secretManager, component.secretManager());
    assertSame(legacy.eventBus, component.eventBus());
    assertNotSame(first, component.entityStore());
  }

  @Test
  public void testBridgeFailurePropagates() {
    LegacyBridge uninitialized =
        new TestGravitinoComponent.FakeLegacyBridge() {
          @Override
          public EntityStore entityStore() {
            throw new IllegalArgumentException("GravitinoEnv is not initialized.");
          }
        };
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, uninitialized);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, component::entityStore);
    assertEquals("GravitinoEnv is not initialized.", exception.getMessage());
  }

  @Test
  public void testBridgeIsNotReadUntilRequested() {
    LegacyBridge failing =
        new TestGravitinoComponent.FakeLegacyBridge() {
          @Override
          public EntityStore entityStore() {
            throw new IllegalStateException("must not be read");
          }
        };

    // Building the component and the objects it owns must not touch the legacy objects.
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, failing);
    component.lockManager();
    component.idGenerator();
    component.metricsSystem();
  }
}
