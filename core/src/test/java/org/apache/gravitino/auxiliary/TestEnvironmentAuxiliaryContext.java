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
package org.apache.gravitino.auxiliary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.OwnerDispatcher;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class TestEnvironmentAuxiliaryContext {

  @Test
  public void testEachServiceIsReadFromTheEnvironment() {
    GravitinoEnv env = mock(GravitinoEnv.class);
    Config config = mock(Config.class);
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
    SecretPropertyOperationDispatcher secretPropertyOperationDispatcher =
        mock(SecretPropertyOperationDispatcher.class);
    SchemaDispatcher internalSchemaDispatcher = mock(SchemaDispatcher.class);
    TableDispatcher internalTableDispatcher = mock(TableDispatcher.class);
    ViewDispatcher internalViewDispatcher = mock(ViewDispatcher.class);
    OwnerDispatcher internalOwnerDispatcher = mock(OwnerDispatcher.class);
    when(env.config()).thenReturn(config);
    when(env.eventBus()).thenReturn(eventBus);
    when(env.metricsSystem()).thenReturn(metricsSystem);
    when(env.secretManager()).thenReturn(secretManager);
    when(env.kmsClientRegistry()).thenReturn(kmsClientRegistry);
    when(env.idGenerator()).thenReturn(idGenerator);
    when(env.lockManager()).thenReturn(lockManager);
    when(env.entityStore()).thenReturn(entityStore);
    when(env.catalogManager()).thenReturn(catalogManager);
    when(env.catalogDispatcher()).thenReturn(catalogDispatcher);
    when(env.schemaDispatcher()).thenReturn(schemaDispatcher);
    when(env.tableDispatcher()).thenReturn(tableDispatcher);
    when(env.policyDispatcher()).thenReturn(policyDispatcher);
    when(env.secretPropertyOperationDispatcher()).thenReturn(secretPropertyOperationDispatcher);
    when(env.internalSchemaDispatcher()).thenReturn(internalSchemaDispatcher);
    when(env.internalTableDispatcher()).thenReturn(internalTableDispatcher);
    when(env.internalViewDispatcher()).thenReturn(internalViewDispatcher);
    when(env.internalOwnerDispatcher()).thenReturn(internalOwnerDispatcher);

    // The context is taken before GravitinoEnv is stubbed: it reads each service when asked.
    AuxiliaryContext context = EnvironmentAuxiliaryContext.INSTANCE;
    try (MockedStatic<GravitinoEnv> envStatic = mockStatic(GravitinoEnv.class)) {
      envStatic.when(GravitinoEnv::getInstance).thenReturn(env);

      Assertions.assertSame(config, context.config());
      Assertions.assertSame(eventBus, context.eventBus());
      Assertions.assertSame(metricsSystem, context.metricsSystem());
      Assertions.assertSame(secretManager, context.secretManager());
      Assertions.assertSame(kmsClientRegistry, context.kmsClientRegistry());
      Assertions.assertSame(idGenerator, context.idGenerator());
      Assertions.assertSame(lockManager, context.lockManager());
      Assertions.assertSame(entityStore, context.entityStore());
      Assertions.assertSame(catalogManager, context.catalogManager());
      Assertions.assertSame(catalogDispatcher, context.catalogDispatcher());
      Assertions.assertSame(schemaDispatcher, context.schemaDispatcher());
      Assertions.assertSame(tableDispatcher, context.tableDispatcher());
      Assertions.assertSame(policyDispatcher, context.policyDispatcher());
      Assertions.assertSame(
          secretPropertyOperationDispatcher, context.secretPropertyOperationDispatcher());
      Assertions.assertSame(internalSchemaDispatcher, context.internalSchemaDispatcher());
      Assertions.assertSame(internalTableDispatcher, context.internalTableDispatcher());
      Assertions.assertSame(internalViewDispatcher, context.internalViewDispatcher());
      Assertions.assertSame(internalOwnerDispatcher, context.internalOwnerDispatcher());
    }
  }
}
