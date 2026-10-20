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

import static org.apache.gravitino.di.DispatcherChains.assertChain;
import static org.apache.gravitino.di.DispatcherChains.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.hook.AccessControlHookDispatcher;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestAuthorizationModule {

  private TestGravitinoComponent.FakeLegacyBridge legacy;
  private GravitinoComponent component;

  @BeforeEach
  public void setUp() {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    legacy = new TestGravitinoComponent.FakeLegacyBridge();
    component = DaggerGravitinoComponent.factory().create(config, legacy);
  }

  @Test
  public void testAccessControlChains() throws Exception {
    assertChain(component.internalAccessControlDispatcher(), AccessControlManager.class);
    Object manager =
        assertChain(
            component.accessControlDispatcher(),
            AccessControlEventDispatcher.class,
            AccessControlHookDispatcher.class,
            AccessControlManager.class);
    assertSame(component.internalAccessControlDispatcher(), manager);
    assertSame(component.lockManager(), field(manager, "lockManager"));
    assertSame(legacy.eventBus, field(component.accessControlDispatcher(), "eventBus"));
  }

  @Test
  public void testManagersAreSingletons() {
    assertSame(component.bulkManager(), component.bulkManager());
    assertSame(component.futureGrantManager(), component.futureGrantManager());
  }

  @Test
  public void testFutureGrantManagerUsesTheInternalOwnerDispatcher() throws Exception {
    assertSame(
        component.internalOwnerDispatcher(),
        field(component.futureGrantManager(), "ownerDispatcher"));
    assertSame(legacy.entityStore, field(component.futureGrantManager(), "entityStore"));
  }

  @Test
  public void testBulkManagerReadsTheConfiguration() throws Exception {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.BULK_MAX_ITEMS, 7);
    GravitinoComponent configured = DaggerGravitinoComponent.factory().create(config, legacy);

    assertEquals(7, field(configured.bulkManager(), "maxBulkItems"));
  }
}
