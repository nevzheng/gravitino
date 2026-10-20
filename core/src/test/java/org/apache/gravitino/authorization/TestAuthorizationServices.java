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
package org.apache.gravitino.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collections;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAuthorizationServices {

  @Test
  public void testEnvironmentServicesReadTheGettersWhenAsked() {
    GravitinoEnv env = mock(GravitinoEnv.class);
    AuthorizationServices services = EnvironmentAuthorizationServices.of(env);
    Assertions.assertFalse(services.authorizationEnabled());
    Assertions.assertNull(services.internalOwnerDispatcher());

    AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);
    OwnerDispatcher owner = mock(OwnerDispatcher.class);
    FutureGrantManager futureGrants = mock(FutureGrantManager.class);
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    CatalogDispatcher catalogs = mock(CatalogDispatcher.class);
    SchemaDispatcher schemas = mock(SchemaDispatcher.class);
    TableDispatcher tables = mock(TableDispatcher.class);
    FilesetDispatcher filesets = mock(FilesetDispatcher.class);
    when(env.internalAccessControlDispatcher()).thenReturn(accessControl);
    when(env.internalOwnerDispatcher()).thenReturn(owner);
    when(env.futureGrantManager()).thenReturn(futureGrants);
    when(env.gravitinoAuthorizer()).thenReturn(authorizer);
    when(env.catalogManager()).thenReturn(catalogManager);
    when(env.internalCatalogDispatcher()).thenReturn(catalogs);
    when(env.internalSchemaDispatcher()).thenReturn(schemas);
    when(env.internalTableDispatcher()).thenReturn(tables);
    when(env.internalFilesetDispatcher()).thenReturn(filesets);

    Assertions.assertTrue(services.authorizationEnabled());
    Assertions.assertSame(accessControl, services.internalAccessControlDispatcher());
    Assertions.assertSame(owner, services.internalOwnerDispatcher());
    Assertions.assertSame(futureGrants, services.futureGrantManager());
    Assertions.assertSame(authorizer, services.gravitinoAuthorizer());
    Assertions.assertSame(catalogManager, services.catalogManager());
    Assertions.assertSame(catalogs, services.internalCatalogDispatcher());
    Assertions.assertSame(schemas, services.internalSchemaDispatcher());
    Assertions.assertSame(tables, services.internalTableDispatcher());
    Assertions.assertSame(filesets, services.internalFilesetDispatcher());
  }

  @Test
  public void testNotifyMappingChangeUsesTheGivenAuthorizerAndSwallowsFailures() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    NameIdentifier table = NameIdentifier.of("metalake", "catalog", "schema", "table");

    AuthorizationUtils.notifyEntityNameIdMappingChange(authorizer, table, Entity.EntityType.TABLE);
    verify(authorizer).handleEntityNameIdMappingChange("metalake", table, Entity.EntityType.TABLE);

    doThrow(new IllegalStateException("cache failure"))
        .when(authorizer)
        .handleEntityNameIdMappingChange(any(), any(), any());
    Assertions.assertDoesNotThrow(
        () ->
            AuthorizationUtils.notifyEntityNameIdMappingChange(
                authorizer, table, Entity.EntityType.TABLE));
  }

  @Test
  public void testRemoveAndRenameDoNothingWhileAuthorizationIsDisabled() {
    AuthorizationServices services = mock(AuthorizationServices.class);
    NameIdentifier table = NameIdentifier.of("metalake", "catalog", "schema", "table");

    AuthorizationUtils.authorizationPluginRemovePrivileges(
        services, table, Entity.EntityType.TABLE, Collections.emptyList());
    AuthorizationUtils.authorizationPluginRenamePrivileges(
        services, table, Entity.EntityType.TABLE, "renamed");

    verify(services, never()).gravitinoAuthorizer();
    verify(services, never()).catalogManager();
  }

  @Test
  public void testRemoveNotifiesTheAuthorizerAndThePluginsOfTheServices() {
    AuthorizationServices services = mock(AuthorizationServices.class);
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    when(services.internalAccessControlDispatcher())
        .thenReturn(mock(AccessControlDispatcher.class));
    when(services.gravitinoAuthorizer()).thenReturn(authorizer);
    when(services.catalogManager()).thenReturn(catalogManager);
    NameIdentifier table = NameIdentifier.of("metalake", "catalog", "schema", "table");

    AuthorizationUtils.authorizationPluginRemovePrivileges(
        services, table, Entity.EntityType.TABLE, Collections.emptyList());

    verify(authorizer).handleEntityNameIdMappingChange("metalake", table, Entity.EntityType.TABLE);
    verify(catalogManager).doWithCatalog(eq(NameIdentifier.of("metalake", "catalog")), any());
  }

  @Test
  public void testCheckCurrentUserAsksTheGivenAuthorizer() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    AuthorizationRequestContext context = new AuthorizationRequestContext();
    when(authorizer.isMetalakeUser("metalake", context)).thenReturn(true);

    AuthorizationUtils.checkCurrentUser(authorizer, "metalake", "user", context);

    Assertions.assertThrows(
        ForbiddenException.class,
        () ->
            AuthorizationUtils.checkCurrentUser(
                authorizer, "other", "user", new AuthorizationRequestContext()));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testDeprecatedManagerConstructorsReadTheEnvironment() throws Exception {
    EntityStore store =
        mock(EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
    Config config = new Config(false) {};
    config.set(Configs.SERVICE_ADMINS, Collections.singletonList("admin"));
    AccessControlManager accessControlManager =
        new AccessControlManager(store, mock(IdGenerator.class), config, mock(LockManager.class));
    Assertions.assertSame(
        EnvironmentAuthorizationServices.INSTANCE,
        FieldUtils.readField(
            FieldUtils.readField(accessControlManager, "roleManager", true),
            "authorizationServices",
            true));
    Assertions.assertSame(
        EnvironmentAuthorizationServices.INSTANCE,
        FieldUtils.readField(new OwnerManager(store), "authorizationServices", true));
  }

  @Test
  public void testOwnerManagerKeepsTheGivenServices() throws Exception {
    EntityStore store =
        mock(EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
    AuthorizationServices services = mock(AuthorizationServices.class);
    Assertions.assertSame(
        services,
        FieldUtils.readField(new OwnerManager(store, services), "authorizationServices", true));
  }
}
