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
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergHookGraph.Dispatchers;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.IcebergListTableEvent;
import org.apache.gravitino.listener.api.event.IcebergListTablePreEvent;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.lock.LockManager;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

public class TestIcebergHookGraph {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final String SCHEMA_SEPARATOR = ":";

  @Test
  public void testBaseGraphOwnsStableEventDecorators() {
    EventBus eventBus = mock(EventBus.class);

    Dispatchers graph =
        baseGraph(
            eventBus,
            mock(IcebergNamespaceOperationDispatcher.class),
            mock(IcebergTableOperationDispatcher.class),
            mock(IcebergViewOperationDispatcher.class));

    assertInstanceOf(IcebergNamespaceEventDispatcher.class, graph.namespaceDispatcher());
    assertInstanceOf(IcebergTableEventDispatcher.class, graph.tableDispatcher());
    assertInstanceOf(IcebergViewEventDispatcher.class, graph.viewDispatcher());
    assertSame(graph.tableDispatcher(), graph.tableDispatcher());
  }

  @Test
  public void testAuxiliaryGraphOrdersHookOutsideEventOutsideOperation() {
    EventBus eventBus = mock(EventBus.class);
    IcebergTableOperationDispatcher tableOperations = mock(IcebergTableOperationDispatcher.class);
    IcebergNamespaceOperationDispatcher namespaceOperations =
        mock(IcebergNamespaceOperationDispatcher.class);
    IcebergViewOperationDispatcher viewOperations = mock(IcebergViewOperationDispatcher.class);
    IcebergRequestContext context = mock(IcebergRequestContext.class);
    Namespace namespace = Namespace.of("schema");
    ListTablesResponse response = ListTablesResponse.builder().build();
    when(context.catalogName()).thenReturn(CATALOG);
    when(tableOperations.listTable(context, namespace)).thenReturn(response);

    Dispatchers graph =
        auxiliaryGraph(eventBus, namespaceOperations, tableOperations, viewOperations);

    assertInstanceOf(IcebergNamespaceHookDispatcher.class, graph.namespaceDispatcher());
    assertInstanceOf(IcebergTableHookDispatcher.class, graph.tableDispatcher());
    assertInstanceOf(IcebergViewHookDispatcher.class, graph.viewDispatcher());
    assertSame(graph.tableDispatcher(), graph.tableDispatcher());
    assertSame(response, graph.tableDispatcher().listTable(context, namespace));

    InOrder order = inOrder(eventBus, tableOperations);
    order.verify(eventBus).dispatchEvent(isA(IcebergListTablePreEvent.class));
    order.verify(tableOperations).listTable(context, namespace);
    order.verify(eventBus).dispatchEvent(isA(IcebergListTableEvent.class));
  }

  @Test
  public void testGraphsDoNotShareDispatchersOrInputs() {
    EventBus firstEventBus = mock(EventBus.class);
    EventBus secondEventBus = mock(EventBus.class);
    IcebergTableOperationDispatcher firstTable = mock(IcebergTableOperationDispatcher.class);
    IcebergTableOperationDispatcher secondTable = mock(IcebergTableOperationDispatcher.class);
    IcebergRequestContext context = mock(IcebergRequestContext.class);
    Namespace namespace = Namespace.of("schema");
    when(context.catalogName()).thenReturn(CATALOG);
    when(firstTable.listTable(context, namespace)).thenReturn(ListTablesResponse.builder().build());

    Dispatchers first =
        baseGraph(
            firstEventBus,
            mock(IcebergNamespaceOperationDispatcher.class),
            firstTable,
            mock(IcebergViewOperationDispatcher.class));
    Dispatchers second =
        baseGraph(
            secondEventBus,
            mock(IcebergNamespaceOperationDispatcher.class),
            secondTable,
            mock(IcebergViewOperationDispatcher.class));

    assertNotSame(first.tableDispatcher(), second.tableDispatcher());
    first.tableDispatcher().listTable(context, namespace);
    verify(firstTable).listTable(context, namespace);
    verify(secondTable, never()).listTable(context, namespace);
    verify(secondEventBus, never()).dispatchEvent(isA(IcebergListTablePreEvent.class));
  }

  @Test
  public void testAuxiliaryGraphRetainsExactDependencyBindings() throws Exception {
    IcebergTableOperationDispatcher tableOperations = mock(IcebergTableOperationDispatcher.class);
    EntityStore entityStore = mock(EntityStore.class);
    EntityStore legacyEntityStore = mock(EntityStore.class);
    GravitinoEnv legacyEnvironment = mock(GravitinoEnv.class);
    when(legacyEnvironment.entityStore()).thenReturn(legacyEntityStore);
    IcebergRequestContext context = mock(IcebergRequestContext.class);
    TableIdentifier identifier = TableIdentifier.of(Namespace.of("schema"), "table");
    when(context.catalogName()).thenReturn(CATALOG);

    try (MockedStatic<GravitinoEnv> environment = mockStatic(GravitinoEnv.class)) {
      environment.when(GravitinoEnv::getInstance).thenReturn(legacyEnvironment);
      Dispatchers graph =
          IcebergHookGraph.createAuxiliary(
              mock(IcebergNamespaceOperationDispatcher.class),
              tableOperations,
              mock(IcebergViewOperationDispatcher.class),
              new EventBus(Collections.emptyList()),
              METALAKE,
              entityStore,
              mock(LockManager.class),
              mock(SchemaDispatcher.class),
              mock(TableDispatcher.class),
              mock(ViewDispatcher.class),
              mock(OwnerDispatcher.class),
              SCHEMA_SEPARATOR);

      graph.tableDispatcher().dropTable(context, identifier, false);

      verify(entityStore).delete(any(), eq(Entity.EntityType.TABLE));
      verify(legacyEntityStore, never()).delete(any(), any());
    }
  }

  private static Dispatchers baseGraph(
      EventBus eventBus,
      IcebergNamespaceOperationDispatcher namespaceOperations,
      IcebergTableOperationDispatcher tableOperations,
      IcebergViewOperationDispatcher viewOperations) {
    return IcebergHookGraph.createBase(
        namespaceOperations, tableOperations, viewOperations, eventBus, METALAKE);
  }

  private static Dispatchers auxiliaryGraph(
      EventBus eventBus,
      IcebergNamespaceOperationDispatcher namespaceOperations,
      IcebergTableOperationDispatcher tableOperations,
      IcebergViewOperationDispatcher viewOperations) {
    return IcebergHookGraph.createAuxiliary(
        namespaceOperations,
        tableOperations,
        viewOperations,
        eventBus,
        METALAKE,
        mock(EntityStore.class),
        mock(LockManager.class),
        mock(SchemaDispatcher.class),
        mock(TableDispatcher.class),
        mock(ViewDispatcher.class),
        mock(OwnerDispatcher.class),
        SCHEMA_SEPARATOR);
  }
}
