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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Test;

public class TestAuxiliaryModule {

  @Test
  public void testComponentContextResolvesEachServiceWhenAsked() {
    Config config = new Config(false) {};
    EventBus eventBus = mock(EventBus.class);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    SecretManager secretManager = mock(SecretManager.class);
    KmsClientRegistry kmsClientRegistry = mock(KmsClientRegistry.class);
    IdGenerator idGenerator = mock(IdGenerator.class);
    LockManager lockManager = mock(LockManager.class);
    EntityStore entityStore = mock(EntityStore.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
    SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);
    TableDispatcher tableDispatcher = mock(TableDispatcher.class);
    PolicyDispatcher policyDispatcher = mock(PolicyDispatcher.class);
    SecretPropertyOperationDispatcher secretDispatcher =
        mock(SecretPropertyOperationDispatcher.class);
    SchemaDispatcher internalSchemaDispatcher = mock(SchemaDispatcher.class);
    TableDispatcher internalTableDispatcher = mock(TableDispatcher.class);
    ViewDispatcher internalViewDispatcher = mock(ViewDispatcher.class);
    OwnerDispatcher internalOwnerDispatcher = mock(OwnerDispatcher.class);

    AuxiliaryContext context =
        new ComponentAuxiliaryContext(
            config,
            () -> eventBus,
            () -> metricsSystem,
            () -> secretManager,
            () -> kmsClientRegistry,
            () -> idGenerator,
            () -> lockManager,
            () -> entityStore,
            () -> catalogManager,
            () -> catalogDispatcher,
            () -> schemaDispatcher,
            () -> tableDispatcher,
            () -> policyDispatcher,
            () -> secretDispatcher,
            () -> internalSchemaDispatcher,
            () -> internalTableDispatcher,
            () -> internalViewDispatcher,
            /* authorizationEnabled= */ true,
            () -> internalOwnerDispatcher);

    assertSame(config, context.config());
    assertSame(eventBus, context.eventBus());
    assertSame(metricsSystem, context.metricsSystem());
    assertSame(secretManager, context.secretManager());
    assertSame(kmsClientRegistry, context.kmsClientRegistry());
    assertSame(idGenerator, context.idGenerator());
    assertSame(lockManager, context.lockManager());
    assertSame(entityStore, context.entityStore());
    assertSame(catalogManager, context.catalogAccess());
    assertSame(catalogDispatcher, context.catalogDispatcher());
    assertSame(schemaDispatcher, context.schemaDispatcher());
    assertSame(tableDispatcher, context.tableDispatcher());
    assertSame(policyDispatcher, context.policyDispatcher());
    assertSame(secretDispatcher, context.secretPropertyOperationDispatcher());
    assertSame(internalSchemaDispatcher, context.internalSchemaDispatcher());
    assertSame(internalTableDispatcher, context.internalTableDispatcher());
    assertSame(internalViewDispatcher, context.internalViewDispatcher());
    assertSame(internalOwnerDispatcher, context.internalOwnerDispatcher());
  }

  @Test
  public void testComponentContextIsBuiltFromTheComponent() {
    Config config = TestGravitinoComponent.testConfig();
    GravitinoComponent component =
        DaggerGravitinoComponent.factory()
            .create(config, new TestGravitinoComponent.FakeLegacyBridge());

    AuxiliaryContext context = component.auxiliaryContext();

    assertSame(context, component.auxiliaryContext());
    assertSame(config, context.config());
    assertSame(component.idGenerator(), context.idGenerator());
    assertSame(component.lockManager(), context.lockManager());
    assertSame(component.metricsSystem(), context.metricsSystem());
    // Authorization is disabled, so there is no owner to set, as for the server's hooks.
    assertNull(context.internalOwnerDispatcher());
  }

  @Test
  public void testManagerIsRecordedAndInitializedWithTheContext() {
    Config config = TestGravitinoComponent.testConfig();
    BuiltServices built = new BuiltServices();
    AuxiliaryContext context = mock(AuxiliaryContext.class);

    AuxiliaryServiceManager manager =
        AuxiliaryModule.auxiliaryServiceManager(config, context, built);

    assertSame(manager, built.get(BuiltServices.AUXILIARY_SERVICE_MANAGER));
    // No auxiliary service is configured.
    assertFalse(manager.isAuxServiceRegistered("iceberg-rest"));
  }

  @Test
  public void testComponentBuildsOneManager() {
    GravitinoComponent component =
        DaggerGravitinoComponent.factory()
            .create(
                TestGravitinoComponent.testConfig(), new TestGravitinoComponent.FakeLegacyBridge());

    assertSame(component.auxiliaryServiceManager(), component.auxiliaryServiceManager());
  }
}
