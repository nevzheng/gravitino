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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.function.Supplier;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.hook.MetalakeHookDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestCatalogModule {

  private FakeLeaves leaves;
  private ModuleTestComponent component;

  @BeforeEach
  public void setUp() {
    leaves = new FakeLeaves();
    component = ModuleTestComponent.create(TestGravitinoComponent.testConfig(), leaves);
  }

  @AfterEach
  public void tearDown() {
    component.metalakeManager().close();
    component.catalogManager().close();
  }

  @Test
  public void testManagersShareTheGraph() throws Exception {
    CatalogManager catalogManager = component.catalogManager();
    assertSame(catalogManager, component.catalogManager());
    assertSame(leaves.entityStore, field(catalogManager, "store"));
    assertSame(component.lockManager(), field(catalogManager, "lockManager"));
    assertSame(catalogManager, component.catalogAccess());

    MetalakeManager metalakeManager = component.metalakeManager();
    assertSame(metalakeManager, component.metalakeManager());
    // A force drop removes the child catalogs through the public chain, so their events and hook
    // run; the catalog manager marks the catalogs of an enabled or disabled metalake.
    assertSame(
        component.catalogDispatcher(),
        ((Supplier<?>) field(metalakeManager, "catalogDispatcher")).get());
    assertSame(catalogManager, field(metalakeManager, "catalogManager"));
  }

  @Test
  public void testInjectConstructedDispatchersShareTheGraph() throws Exception {
    // Built from their @Inject constructors rather than from a module method.
    CredentialOperationDispatcher credentials = component.credentialOperationDispatcher();
    assertSame(credentials, component.credentialOperationDispatcher());
    assertSame(component.catalogManager(), field(credentials, "catalogManager"));
    assertSame(leaves.entityStore, field(credentials, "store"));
    assertSame(component.secretManager(), field(credentials, "secretManager"));

    SecretPropertyOperationDispatcher secrets = component.secretPropertyOperationDispatcher();
    assertSame(secrets, component.secretPropertyOperationDispatcher());
    assertSame(component.catalogManager(), field(secrets, "catalogManager"));
    assertSame(component.idGenerator(), field(secrets, "idGenerator"));
  }

  @Test
  public void testMetalakeChains() throws Exception {
    Object internal =
        assertChain(
            component.internalMetalakeDispatcher(),
            MetalakeNormalizeDispatcher.class,
            MetalakeManager.class);
    Object publicManager =
        assertChain(
            component.metalakeDispatcher(),
            MetalakeEventDispatcher.class,
            MetalakeNormalizeDispatcher.class,
            MetalakeHookDispatcher.class,
            MetalakeManager.class);
    assertSame(component.metalakeManager(), internal);
    assertSame(component.metalakeManager(), publicManager);
    assertNotSame(component.metalakeDispatcher(), component.internalMetalakeDispatcher());
  }

  @Test
  public void testCatalogChains() throws Exception {
    Object internal =
        assertChain(
            component.internalCatalogDispatcher(),
            CatalogNormalizeDispatcher.class,
            CatalogManager.class);
    Object publicManager =
        assertChain(
            component.catalogDispatcher(),
            CatalogEventDispatcher.class,
            CatalogNormalizeDispatcher.class,
            CatalogHookDispatcher.class,
            CatalogManager.class);
    assertSame(component.catalogManager(), internal);
    assertSame(component.catalogManager(), publicManager);
    assertSame(leaves.eventBus, field(component.catalogDispatcher(), "eventBus"));
  }

  @Test
  public void testAuthorizationPluginsGetTheInternalChains() throws Exception {
    AuthorizationPluginContext context =
        (AuthorizationPluginContext)
            field(component.catalogManager(), "authorizationPluginContext");

    assertFalse(context.authorizationEnabled());
    assertSame(component.internalCatalogDispatcher(), context.internalCatalogDispatcher());
    assertSame(component.internalSchemaDispatcher(), context.internalSchemaDispatcher());
    assertSame(component.internalTableDispatcher(), context.internalTableDispatcher());
    assertSame(component.internalFilesetDispatcher(), context.internalFilesetDispatcher());
  }

  @Test
  public void testCredentialAndSecretPropertyDispatchersAreSingletons() {
    assertSame(
        component.credentialOperationDispatcher(), component.credentialOperationDispatcher());
    assertSame(
        component.secretPropertyOperationDispatcher(),
        component.secretPropertyOperationDispatcher());
  }
}
