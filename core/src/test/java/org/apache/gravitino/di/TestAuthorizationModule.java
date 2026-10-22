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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.hook.AccessControlHookDispatcher;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestAuthorizationModule {

  private FakeLeaves leaves;
  private ModuleTestComponent component;

  @BeforeEach
  public void setUp() {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    leaves = new FakeLeaves();
    component = ModuleTestComponent.create(config, leaves);
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
    assertSame(leaves.eventBus, field(component.accessControlDispatcher(), "eventBus"));
  }

  @Test
  public void testAuthorizationServicesReachTheGraph() {
    AuthorizationServices services = component.authorizationServices();

    assertSame(services, component.authorizationServices());
    assertTrue(services.authorizationEnabled());
    assertSame(
        component.internalAccessControlDispatcher(), services.internalAccessControlDispatcher());
    assertSame(component.internalOwnerDispatcher(), services.internalOwnerDispatcher());
    assertSame(component.futureGrantManager(), services.futureGrantManager());
    assertSame(component.catalogManager(), services.catalogManager());
    assertSame(component.internalCatalogDispatcher(), services.internalCatalogDispatcher());
    assertSame(component.internalSchemaDispatcher(), services.internalSchemaDispatcher());
    assertSame(component.internalTableDispatcher(), services.internalTableDispatcher());
    assertSame(component.internalFilesetDispatcher(), services.internalFilesetDispatcher());
  }

  @Test
  public void testAuthorizationServicesAreEmptyWhileAuthorizationIsDisabled() {
    ModuleTestComponent disabled =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), leaves);
    AuthorizationServices services = disabled.authorizationServices();

    assertFalse(services.authorizationEnabled());
    assertNull(services.internalAccessControlDispatcher());
    assertNull(services.internalOwnerDispatcher());
    assertNull(services.futureGrantManager());
    // The authorizer is the component's own.
    assertSame(NoopGravitinoAuthorizer.INSTANCE, services.gravitinoAuthorizer());
    assertSame(disabled.gravitinoAuthorizer(), services.gravitinoAuthorizer());
  }

  @Test
  public void testProvisionMethodsBuildWhileAuthorizationIsDisabled() throws Exception {
    // The owner manager would need a relational store; nothing here may build it.
    FakeLeaves plainStore = new FakeLeaves();
    plainStore.entityStore = mock(EntityStore.class);
    ModuleTestComponent disabled =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), plainStore);

    assertSame(NoopOwnerDispatcher.INSTANCE, disabled.internalOwnerDispatcher());
    assertSame(NoopOwnerDispatcher.INSTANCE, disabled.ownerDispatcher());
    assertNotNull(disabled.internalAccessControlDispatcher());
    assertNotNull(disabled.accessControlDispatcher());
    assertNotNull(disabled.bulkManager());
    assertSame(
        NoopOwnerDispatcher.INSTANCE, field(disabled.futureGrantManager(), "ownerDispatcher"));
    // The services the hooks read keep their contract: nothing while authorization is disabled.
    assertNull(disabled.authorizationServices().internalOwnerDispatcher());
    assertNull(disabled.auxiliaryContext().internalOwnerDispatcher());
  }

  @Test
  public void testManagersUseTheComponentAuthorizationServices() throws Exception {
    Object accessControlManager = component.internalAccessControlDispatcher();
    assertSame(
        component.authorizationServices(),
        field(field(accessControlManager, "roleManager"), "authorizationServices"));
    assertSame(
        component.authorizationServices(),
        field(field(accessControlManager, "permissionManager"), "authorizationServices"));
    assertSame(
        component.authorizationServices(),
        field(component.internalOwnerDispatcher(), "authorizationServices"));
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
    assertSame(leaves.entityStore, field(component.futureGrantManager(), "entityStore"));
  }

  @Test
  public void testBulkManagerReadsTheConfiguration() throws Exception {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.BULK_MAX_ITEMS, 7);
    ModuleTestComponent configured = ModuleTestComponent.create(config, leaves);

    assertEquals(7, field(configured.bulkManager(), "maxBulkItems"));
  }
}
