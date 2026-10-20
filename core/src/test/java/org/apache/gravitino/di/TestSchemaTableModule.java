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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.catalog.PartitionNormalizeDispatcher;
import org.apache.gravitino.catalog.PartitionOperationDispatcher;
import org.apache.gravitino.catalog.SchemaNormalizeDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.catalog.ViewNormalizeDispatcher;
import org.apache.gravitino.catalog.ViewOperationDispatcher;
import org.apache.gravitino.hook.SchemaHookDispatcher;
import org.apache.gravitino.hook.TableHookDispatcher;
import org.apache.gravitino.hook.ViewHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.PartitionEventDispatcher;
import org.apache.gravitino.listener.SchemaEventDispatcher;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.ViewEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestSchemaTableModule {

  private TestGravitinoComponent.FakeLegacyBridge legacy;
  private GravitinoComponent component;

  @BeforeEach
  public void setUp() {
    legacy = new TestGravitinoComponent.FakeLegacyBridge();
    component =
        DaggerGravitinoComponent.factory().create(TestGravitinoComponent.testConfig(), legacy);
  }

  @Test
  public void testSchemaChainsShareOneOperationDispatcher() throws Exception {
    Object internalOperations =
        assertChain(
            component.internalSchemaDispatcher(),
            SchemaNormalizeDispatcher.class,
            SchemaOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.schemaDispatcher(),
            SchemaEventDispatcher.class,
            SchemaNormalizeDispatcher.class,
            SchemaHookDispatcher.class,
            SchemaOperationDispatcher.class);

    assertSame(component.schemaOperationDispatcher(), internalOperations);
    assertSame(component.schemaOperationDispatcher(), publicOperations);
    assertSame(legacy.internalFilesetDispatcher, field(internalOperations, "filesetDispatcher"));
    assertSame(component.lockManager(), lockManagerOf(internalOperations));
    Object hook = field(field(component.schemaDispatcher(), "dispatcher"), "dispatcher");
    assertSame(component.lockManager(), lockManagerOf(hook));
  }

  @Test
  public void testTableChains() throws Exception {
    Object internalOperations =
        assertChain(
            component.internalTableDispatcher(),
            TableNormalizeDispatcher.class,
            TableOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.tableDispatcher(),
            TableEventDispatcher.class,
            TableNormalizeDispatcher.class,
            TableHookDispatcher.class,
            TableOperationDispatcher.class);

    assertNotSame(internalOperations, publicOperations);
    for (Object operations : new Object[] {internalOperations, publicOperations}) {
      assertSame(component.internalSchemaDispatcher(), schemaDispatcherOf(operations));
      assertSame(component.lockManager(), lockManagerOf(operations));
    }
  }

  @Test
  public void testViewChains() throws Exception {
    Object internalOperations =
        assertChain(
            component.internalViewDispatcher(),
            ViewNormalizeDispatcher.class,
            ViewOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.viewDispatcher(),
            ViewEventDispatcher.class,
            ViewNormalizeDispatcher.class,
            ViewHookDispatcher.class,
            ViewOperationDispatcher.class);

    assertNotSame(internalOperations, publicOperations);
    for (Object operations : new Object[] {internalOperations, publicOperations}) {
      assertSame(component.internalSchemaDispatcher(), schemaDispatcherOf(operations));
      assertSame(component.lockManager(), lockManagerOf(operations));
    }
  }

  @Test
  public void testPartitionPublicChainWrapsTheInternalChain() throws Exception {
    assertChain(
        component.internalPartitionDispatcher(),
        PartitionNormalizeDispatcher.class,
        PartitionOperationDispatcher.class);
    assertChain(
        component.partitionDispatcher(),
        PartitionEventDispatcher.class,
        PartitionNormalizeDispatcher.class,
        PartitionOperationDispatcher.class);
    assertSame(
        component.internalPartitionDispatcher(),
        field(component.partitionDispatcher(), "dispatcher"));
  }

  @Test
  public void testHooksSkipOwnersWhenAuthorizationIsDisabled() throws Exception {
    assertNull(ownerSupplierOf(component.tableDispatcher()).get());
    assertNull(ownerSupplierOf(component.viewDispatcher()).get());
  }

  @Test
  public void testHooksUseTheOwnerManagerWhenAuthorizationIsEnabled() throws Exception {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    GravitinoComponent authorized = DaggerGravitinoComponent.factory().create(config, legacy);

    assertSame(
        authorized.internalOwnerDispatcher(), ownerSupplierOf(authorized.tableDispatcher()).get());
    assertSame(
        authorized.internalOwnerDispatcher(), ownerSupplierOf(authorized.viewDispatcher()).get());
  }

  @Test
  public void testInternalChainsDoNotNeedTheEventBus() {
    // The metadata profile has no event bus; its internal chains must still build.
    TestGravitinoComponent.FakeLegacyBridge noEventBus =
        new TestGravitinoComponent.FakeLegacyBridge() {
          @Override
          public EventBus eventBus() {
            throw new IllegalArgumentException("GravitinoEnv is not initialized.");
          }
        };
    GravitinoComponent metadataOnly =
        DaggerGravitinoComponent.factory().create(TestGravitinoComponent.testConfig(), noEventBus);

    metadataOnly.internalSchemaDispatcher();
    metadataOnly.internalTableDispatcher();
    metadataOnly.internalPartitionDispatcher();
    metadataOnly.internalViewDispatcher();
    assertThrows(IllegalArgumentException.class, metadataOnly::schemaDispatcher);
  }

  private static LockManager lockManagerOf(Object dispatcher) throws Exception {
    return (LockManager) ((Supplier<?>) field(dispatcher, "lockManagerSupplier")).get();
  }

  private static Object schemaDispatcherOf(Object operations) throws Exception {
    return ((Supplier<?>) field(operations, "schemaDispatcherSupplier")).get();
  }

  private static Supplier<?> ownerSupplierOf(Object publicChain) throws Exception {
    Object hook = field(field(publicChain, "dispatcher"), "dispatcher");
    return (Supplier<?>) field(hook, "ownerDispatcher");
  }
}
