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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class TestIcebergLegacyHookCompatibility {

  private static final String METALAKE = "captured-metalake";

  @Test
  public void testLegacyConstructorsDeferEnvironmentResolution() {
    IcebergRESTServerContext serverContext = mock(IcebergRESTServerContext.class);
    when(serverContext.metalakeName()).thenReturn(METALAKE);

    try (MockedStatic<GravitinoEnv> environment = mockStatic(GravitinoEnv.class);
        MockedStatic<IcebergRESTServerContext> context =
            mockStatic(IcebergRESTServerContext.class)) {
      context.when(IcebergRESTServerContext::getInstance).thenReturn(serverContext);

      assertDoesNotThrow(
          () ->
              new IcebergNamespaceHookDispatcher(mock(IcebergNamespaceOperationDispatcher.class)));
      assertDoesNotThrow(
          () ->
              new IcebergTableHookDispatcher(
                  mock(IcebergTableOperationDispatcher.class),
                  mock(IcebergNamespaceOperationDispatcher.class)));
      assertDoesNotThrow(
          () ->
              new IcebergViewHookDispatcher(
                  mock(IcebergViewOperationDispatcher.class),
                  mock(IcebergNamespaceOperationDispatcher.class),
                  METALAKE));

      environment.verifyNoInteractions();
    }
  }

  @Test
  public void testLegacyHookResolvesReplacementEnvironmentAfterConstruction() {
    IcebergRESTServerContext serverContext = mock(IcebergRESTServerContext.class);
    when(serverContext.metalakeName()).thenReturn(METALAKE);
    IcebergTableOperationDispatcher operations = mock(IcebergTableOperationDispatcher.class);
    IcebergRequestContext requestContext = mock(IcebergRequestContext.class);
    when(requestContext.catalogName()).thenReturn("catalog");
    when(requestContext.userName()).thenReturn("user");
    Namespace namespace = Namespace.of("parent", "child");
    CreateTableRequest request =
        CreateTableRequest.builder()
            .withName("table")
            .withSchema(new Schema(Types.NestedField.required(1, "column", Types.StringType.get())))
            .build();
    when(operations.createTable(requestContext, namespace, request))
        .thenReturn(mock(LoadTableResponse.class));

    GravitinoEnv firstEnvironment = mock(GravitinoEnv.class);
    GravitinoEnv secondEnvironment = mock(GravitinoEnv.class);
    TableDispatcher firstTableDispatcher = mock(TableDispatcher.class);
    TableDispatcher secondTableDispatcher = mock(TableDispatcher.class);
    OwnerDispatcher firstOwnerDispatcher = mock(OwnerDispatcher.class);
    OwnerDispatcher secondOwnerDispatcher = mock(OwnerDispatcher.class);
    Config firstConfig = mock(Config.class);
    Config secondConfig = mock(Config.class);
    when(firstEnvironment.internalTableDispatcher()).thenReturn(firstTableDispatcher);
    when(firstEnvironment.internalOwnerDispatcher()).thenReturn(firstOwnerDispatcher);
    when(firstEnvironment.config()).thenReturn(firstConfig);
    when(firstConfig.get(Configs.SCHEMA_SEPARATOR)).thenReturn(":");
    when(secondEnvironment.internalTableDispatcher()).thenReturn(secondTableDispatcher);
    when(secondEnvironment.internalOwnerDispatcher()).thenReturn(secondOwnerDispatcher);
    when(secondEnvironment.config()).thenReturn(secondConfig);
    when(secondConfig.get(Configs.SCHEMA_SEPARATOR)).thenReturn("/");
    AtomicReference<GravitinoEnv> currentEnvironment = new AtomicReference<>();

    try (MockedStatic<GravitinoEnv> environment = mockStatic(GravitinoEnv.class);
        MockedStatic<IcebergRESTServerContext> context =
            mockStatic(IcebergRESTServerContext.class)) {
      context.when(IcebergRESTServerContext::getInstance).thenReturn(serverContext);
      environment.when(GravitinoEnv::getInstance).thenAnswer(ignored -> currentEnvironment.get());

      IcebergTableHookDispatcher hook =
          new IcebergTableHookDispatcher(
              operations, mock(IcebergNamespaceOperationDispatcher.class));

      currentEnvironment.set(firstEnvironment);
      hook.createTable(requestContext, namespace, request);
      currentEnvironment.set(secondEnvironment);
      hook.createTable(requestContext, namespace, request);
    }

    verify(firstTableDispatcher)
        .loadTable(eq(NameIdentifier.of(METALAKE, "catalog", "parent:child", "table")));
    verify(secondTableDispatcher)
        .loadTable(eq(NameIdentifier.of(METALAKE, "catalog", "parent/child", "table")));
    verify(firstOwnerDispatcher).setOwner(eq(METALAKE), any(), eq("user"), eq(Owner.Type.USER));
    verify(secondOwnerDispatcher).setOwner(eq(METALAKE), any(), eq("user"), eq(Owner.Type.USER));
  }
}
