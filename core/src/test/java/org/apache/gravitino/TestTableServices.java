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
package org.apache.gravitino;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLock;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests the Dagger composition boundary for the authorization-independent table graph. */
public class TestTableServices {

  private static final NameIdentifier TABLE_IDENTIFIER =
      NameIdentifier.of(Namespace.of("metalake", "catalog", "schema"), "table");
  private static final NameIdentifier CATALOG_IDENTIFIER = NameIdentifier.of("metalake", "catalog");
  private static final NameIdentifier SCHEMA_IDENTIFIER =
      NameIdentifier.of("metalake", "catalog", "schema");

  @Test
  public void testBuildsStableAndIsolatedTableRoots() throws Exception {
    GraphFixture fixture = GraphFixture.create();

    TableDispatcher publicRoot = fixture.services.tableDispatcher();
    TableDispatcher internalRoot = fixture.services.internalTableDispatcher();

    Assertions.assertSame(publicRoot, fixture.services.tableDispatcher());
    Assertions.assertSame(internalRoot, fixture.services.internalTableDispatcher());
    Assertions.assertNotSame(publicRoot, internalRoot);
    Assertions.assertInstanceOf(TableEventDispatcher.class, publicRoot);
    Assertions.assertInstanceOf(TableNormalizeDispatcher.class, internalRoot);
  }

  @Test
  public void testRoutesPublicAndInternalTablesThroughTheirBoundSchemas() throws Exception {
    GraphFixture fixture = GraphFixture.create();
    NoSuchSchemaException publicFailure = new NoSuchSchemaException("public schema route");
    NoSuchSchemaException internalFailure = new NoSuchSchemaException("internal schema route");
    doThrow(publicFailure).when(fixture.publicSchemaDispatcher).loadSchema(SCHEMA_IDENTIFIER);
    doThrow(internalFailure).when(fixture.internalSchemaDispatcher).loadSchema(SCHEMA_IDENTIFIER);

    NoSuchSchemaException actualPublicFailure =
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> createTable(fixture.services.tableDispatcher()));
    NoSuchSchemaException actualInternalFailure =
        Assertions.assertThrows(
            NoSuchSchemaException.class,
            () -> createTable(fixture.services.internalTableDispatcher()));

    Assertions.assertSame(publicFailure, actualPublicFailure);
    Assertions.assertSame(internalFailure, actualInternalFailure);
    verify(fixture.publicSchemaDispatcher).loadSchema(SCHEMA_IDENTIFIER);
    verify(fixture.internalSchemaDispatcher).loadSchema(SCHEMA_IDENTIFIER);
    verify(fixture.catalogManager, times(2)).loadCatalogAndWrap(CATALOG_IDENTIFIER);
    verify(fixture.eventBus, times(2)).dispatchEvent(any());
    verifyNoInteractions(fixture.entityStore, fixture.idGenerator);
  }

  @Test
  public void testRoutesBothTablePathsThroughTheBoundLockManager() throws Exception {
    GraphFixture fixture = GraphFixture.create();
    TreeLock treeLock = mock(TreeLock.class);
    when(fixture.lockManager.createTreeLock(SCHEMA_IDENTIFIER)).thenReturn(treeLock);

    fixture.services.tableDispatcher().listTables(TABLE_IDENTIFIER.namespace());
    fixture.services.internalTableDispatcher().listTables(TABLE_IDENTIFIER.namespace());

    verify(fixture.lockManager, times(2)).createTreeLock(SCHEMA_IDENTIFIER);
    verify(treeLock, times(2)).lock(LockType.READ);
    verify(treeLock, times(2)).unlock();
  }

  private static void createTable(TableDispatcher dispatcher) {
    dispatcher.createTable(
        TABLE_IDENTIFIER,
        new Column[0],
        "comment",
        Collections.emptyMap(),
        new Transform[0],
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);
  }

  private static final class GraphFixture {
    private final CatalogManager catalogManager;
    private final EntityStore entityStore;
    private final IdGenerator idGenerator;
    private final EventBus eventBus;
    private final LockManager lockManager;
    private final SchemaDispatcher publicSchemaDispatcher;
    private final SchemaDispatcher internalSchemaDispatcher;
    private final TableServices services;

    private GraphFixture(
        CatalogManager catalogManager,
        EntityStore entityStore,
        IdGenerator idGenerator,
        EventBus eventBus,
        LockManager lockManager,
        SchemaDispatcher publicSchemaDispatcher,
        SchemaDispatcher internalSchemaDispatcher,
        TableServices services) {
      this.catalogManager = catalogManager;
      this.entityStore = entityStore;
      this.idGenerator = idGenerator;
      this.eventBus = eventBus;
      this.lockManager = lockManager;
      this.publicSchemaDispatcher = publicSchemaDispatcher;
      this.internalSchemaDispatcher = internalSchemaDispatcher;
      this.services = services;
    }

    private static GraphFixture create() throws Exception {
      CatalogManager catalogManager = mock(CatalogManager.class);
      CatalogManager.CatalogWrapper catalogWrapper = mock(CatalogManager.CatalogWrapper.class);
      when(catalogWrapper.capabilities()).thenReturn(Capability.DEFAULT);
      when(catalogManager.loadCatalogAndWrap(CATALOG_IDENTIFIER)).thenReturn(catalogWrapper);
      EntityStore entityStore = mock(EntityStore.class);
      IdGenerator idGenerator = mock(IdGenerator.class);
      EventBus eventBus = mock(EventBus.class);
      LockManager lockManager = mock(LockManager.class);
      SchemaDispatcher publicSchemaDispatcher = mock(SchemaDispatcher.class);
      SchemaDispatcher internalSchemaDispatcher = mock(SchemaDispatcher.class);
      TableServices services =
          TableServices.create(
              catalogManager,
              entityStore,
              idGenerator,
              eventBus,
              lockManager,
              publicSchemaDispatcher,
              internalSchemaDispatcher);
      return new GraphFixture(
          catalogManager,
          entityStore,
          idGenerator,
          eventBus,
          lockManager,
          publicSchemaDispatcher,
          internalSchemaDispatcher,
          services);
    }
  }
}
