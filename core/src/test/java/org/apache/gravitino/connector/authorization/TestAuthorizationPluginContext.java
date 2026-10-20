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
package org.apache.gravitino.connector.authorization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestAuthorizationPluginContext {

  @Test
  public void testOfReadsEachServiceWhenAskedFor() {
    AtomicBoolean enabled = new AtomicBoolean();
    CatalogDispatcher[] catalogs = new CatalogDispatcher[1];
    SchemaDispatcher schemas = mock(SchemaDispatcher.class);
    TableDispatcher tables = mock(TableDispatcher.class);
    FilesetDispatcher filesets = mock(FilesetDispatcher.class);

    AuthorizationPluginContext context =
        AuthorizationPluginContext.of(
            enabled::get, () -> catalogs[0], () -> schemas, () -> tables, () -> filesets);
    enabled.set(true);
    catalogs[0] = mock(CatalogDispatcher.class);

    Assertions.assertTrue(context.authorizationEnabled());
    Assertions.assertSame(catalogs[0], context.internalCatalogDispatcher());
    Assertions.assertSame(schemas, context.internalSchemaDispatcher());
    Assertions.assertSame(tables, context.internalTableDispatcher());
    Assertions.assertSame(filesets, context.internalFilesetDispatcher());
  }

  @Test
  public void testOfRejectsAMissingSupplier() {
    Assertions.assertThrows(
        NullPointerException.class,
        () -> AuthorizationPluginContext.of(() -> true, null, () -> null, () -> null, () -> null));
  }

  @Test
  public void testOrEnvironment() {
    AuthorizationPluginContext given = mock(AuthorizationPluginContext.class);
    Assertions.assertSame(given, EnvironmentAuthorizationPluginContext.orEnvironment(given));
    Assertions.assertSame(
        EnvironmentAuthorizationPluginContext.INSTANCE,
        EnvironmentAuthorizationPluginContext.orEnvironment(null));
  }

  @Test
  public void testDefaultNewPluginIgnoresTheContext() {
    AuthorizationPlugin plugin = mock(AuthorizationPlugin.class);
    BaseAuthorization<?> authorization = mock(BaseAuthorization.class);
    when(authorization.newPlugin(Mockito.any(), Mockito.any(), Mockito.anyMap()))
        .thenReturn(plugin);
    when(authorization.newPlugin(Mockito.any(), Mockito.any(), Mockito.anyMap(), Mockito.any()))
        .thenCallRealMethod();

    Assertions.assertSame(
        plugin,
        authorization.newPlugin(
            "metalake", "hive", Collections.emptyMap(), mock(AuthorizationPluginContext.class)));
  }
}
