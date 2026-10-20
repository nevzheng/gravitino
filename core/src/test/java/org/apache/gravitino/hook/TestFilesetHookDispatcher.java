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
package org.apache.gravitino.hook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.FakeAuthorizationServices;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogTestUtils;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.TestFilesetOperationDispatcher;
import org.apache.gravitino.catalog.TestOperationDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.file.FilesetChange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestFilesetHookDispatcher extends TestOperationDispatcher {

  private static FilesetHookDispatcher filesetHookDispatcher;
  private static SchemaHookDispatcher schemaHookDispatcher;
  private static AccessControlManager accessControlManager =
      Mockito.mock(AccessControlManager.class);
  private static AuthorizationPlugin authorizationPlugin;
  private static FakeAuthorizationServices services;

  @BeforeAll
  public static void initialize() throws Exception {
    TestFilesetOperationDispatcher.initialize();

    services = new FakeAuthorizationServices().withAccessControlDispatcher(accessControlManager);
    filesetHookDispatcher =
        new FilesetHookDispatcher(
            TestFilesetOperationDispatcher.getFilesetOperationDispatcher(), services);
    schemaHookDispatcher =
        new SchemaHookDispatcher(
            TestFilesetOperationDispatcher.getSchemaOperationDispatcher(), lockManager, services);

    catalogManager = Mockito.mock(CatalogManager.class);
    services.withCatalogManager(catalogManager);
    BaseCatalog catalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(catalog.capability()).thenReturn(Capability.DEFAULT);
    CatalogTestUtils.mockDoWithCatalog(catalogManager, catalog);
    authorizationPlugin = Mockito.mock(AuthorizationPlugin.class);
    Mockito.when(catalog.getAuthorizationPlugin()).thenReturn(authorizationPlugin);
  }

  @Test
  public void testCreateFilesetSetsOwnerWithNormalizedIdentifier() throws Exception {
    // Self-contained: use a fresh hook with a directly-mocked FilesetDispatcher and a case-
    // insensitive catalog so we can verify the helper passes a normalized ident to setOwner.
    CatalogManager mockCatalogManager = Mockito.mock(CatalogManager.class);
    BaseCatalog<?> mockCatalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(mockCatalog.capability()).thenReturn(new CaseInsensitiveCapability());
    CatalogTestUtils.mockDoWithCatalog(mockCatalogManager, mockCatalog);

    OwnerDispatcher mockOwnerDispatcher = Mockito.mock(OwnerDispatcher.class);
    FilesetDispatcher mockFilesetDispatcher = Mockito.mock(FilesetDispatcher.class);
    Mockito.when(
            mockFilesetDispatcher.createMultipleLocationFileset(
                any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mockito.mock(Fileset.class));

    FilesetDispatcher localHook =
        new FilesetNormalizeDispatcher(
            new FilesetHookDispatcher(
                mockFilesetDispatcher,
                new FakeAuthorizationServices()
                    .withAccessControlDispatcher(accessControlManager)
                    .withCatalogManager(mockCatalogManager)
                    .withOwnerDispatcher(mockOwnerDispatcher)),
            mockCatalogManager);
    NameIdentifier ident = NameIdentifier.of(metalake, catalog, "SCHEMA_NORM", "MY_FILESET");
    localHook.createMultipleLocationFileset(
        ident,
        "comment",
        Fileset.Type.MANAGED,
        ImmutableMap.of("default", "/tmp/loc"),
        ImmutableMap.of());

    ArgumentCaptor<MetadataObject> captor = ArgumentCaptor.forClass(MetadataObject.class);
    Mockito.verify(mockOwnerDispatcher)
        .setOwner(eq(metalake), captor.capture(), any(), eq(Owner.Type.USER));
    Assertions.assertEquals(
        "my_fileset",
        captor.getValue().name(),
        "Fileset name passed to setOwner must be lowercased by Capability.Scope.FILESET"
            + " normalization");
    Assertions.assertEquals(
        catalog + ".schema_norm",
        captor.getValue().parent(),
        "Fileset parent (catalog.schema) must have its schema component lowercased by"
            + " Capability.Scope.FILESET namespace normalization");
  }

  @Test
  public void testCreateFilesetThrowsWhenSetOwnerFails() throws IllegalAccessException {
    // Create the schema first with the existing (non-throwing) ownerDispatcher, then swap to the
    // throwing mock only for the fileset create we actually want to exercise. Otherwise the
    // throwing mock would fire during schema creation and we would never reach the fileset call.
    Namespace filesetNs = Namespace.of(metalake, catalog, "schema_owner_fail");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaHookDispatcher.createSchema(NameIdentifier.of(filesetNs.levels()), "comment", props);

    OwnerDispatcher mockOwnerDispatcher = Mockito.mock(OwnerDispatcher.class);
    Mockito.doThrow(new RuntimeException("Set owner failed"))
        .when(mockOwnerDispatcher)
        .setOwner(any(), any(), any(), any());
    services.withOwnerDispatcher(mockOwnerDispatcher);

    try {
      NameIdentifier filesetIdent = NameIdentifier.of(filesetNs, "fileset_owner_fail");
      RuntimeException thrown =
          Assertions.assertThrows(
              RuntimeException.class,
              () ->
                  filesetHookDispatcher.createFileset(
                      filesetIdent, "comment", Fileset.Type.MANAGED, "fileset_owner", props));
      Assertions.assertEquals("Set owner failed", thrown.getMessage());
    } finally {
      services.withOwnerDispatcher(null);
    }
  }

  @Test
  public void testDropKeepsPrivilegesWhenDropReturnsFalse() {
    FilesetDispatcher dispatcher = Mockito.mock(FilesetDispatcher.class);
    FilesetHookDispatcher hook = new FilesetHookDispatcher(dispatcher, services);
    NameIdentifier ident = NameIdentifier.of(metalake, catalog, "schema", "fileset");
    Mockito.when(dispatcher.dropFileset(ident)).thenReturn(false);

    try (MockedStatic<AuthorizationUtils> authz = Mockito.mockStatic(AuthorizationUtils.class)) {
      authz
          .when(
              () ->
                  AuthorizationUtils.getMetadataObjectLocation(
                      Mockito.any(AuthorizationServices.class), any(), any()))
          .thenReturn(ImmutableList.of("/test"));

      Assertions.assertFalse(hook.dropFileset(ident));

      authz.verify(
          () ->
              AuthorizationUtils.authorizationPluginRemovePrivileges(
                  Mockito.any(AuthorizationServices.class), any(), any(), any()),
          Mockito.never());
    }
  }

  @Test
  public void testDropAuthorizationPrivilege() {
    Namespace filesetNs = Namespace.of(metalake, catalog, "schema11212");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaHookDispatcher.createSchema(NameIdentifier.of(filesetNs.levels()), "comment", props);

    NameIdentifier filesetIdent = NameIdentifier.of(filesetNs, "filesetNAME1");
    filesetHookDispatcher.createFileset(
        filesetIdent, "comment", Fileset.Type.MANAGED, "fileset41", props);

    withMockedAuthorizationUtils(
        () -> {
          filesetHookDispatcher.dropFileset(filesetIdent);
          schemaHookDispatcher.dropSchema(NameIdentifier.of(filesetNs.levels()), true);
        });
  }

  @Test
  public void testDropFilesetShouldNotRemovePrivilegesWhenDropReturnsFalse() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog", "schema", "fileset");
    FilesetDispatcher delegate = Mockito.mock(FilesetDispatcher.class);
    FilesetHookDispatcher hookDispatcher = new FilesetHookDispatcher(delegate, services);
    List<String> locations = Lists.newArrayList("/tmp/fileset");

    Mockito.when(delegate.dropFileset(ident)).thenReturn(false);

    try (MockedStatic<AuthorizationUtils> mockedAuthz =
        Mockito.mockStatic(AuthorizationUtils.class)) {
      mockedAuthz
          .when(
              () ->
                  AuthorizationUtils.getMetadataObjectLocation(
                      Mockito.any(AuthorizationServices.class),
                      Mockito.eq(ident),
                      Mockito.eq(Entity.EntityType.FILESET)))
          .thenReturn(locations);

      Assertions.assertFalse(hookDispatcher.dropFileset(ident));

      mockedAuthz.verify(
          () ->
              AuthorizationUtils.authorizationPluginRemovePrivileges(
                  Mockito.any(AuthorizationServices.class),
                  Mockito.eq(ident),
                  Mockito.eq(Entity.EntityType.FILESET),
                  Mockito.eq(locations)),
          Mockito.never());
    }
  }

  @Test
  public void testRenameAuthorizationPrivilege() {
    Namespace filesetNs = Namespace.of(metalake, catalog, "schema1121");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaHookDispatcher.createSchema(NameIdentifier.of(filesetNs.levels()), "comment", props);

    NameIdentifier filesetIdent = NameIdentifier.of(filesetNs, "filesetNAME2");
    filesetHookDispatcher.createFileset(
        filesetIdent, "comment", Fileset.Type.MANAGED, "fileset41", props);

    Mockito.reset(authorizationPlugin);
    FilesetChange setChange = FilesetChange.setProperty("k1", "v1");
    filesetHookDispatcher.alterFileset(filesetIdent, setChange);
    Mockito.verify(authorizationPlugin, Mockito.never()).onMetadataUpdated(any());

    Mockito.reset(authorizationPlugin);
    FilesetChange renameChange = FilesetChange.rename("newName");
    filesetHookDispatcher.alterFileset(filesetIdent, renameChange);
    Mockito.verify(authorizationPlugin).onMetadataUpdated(any());
  }

  private static class CaseInsensitiveCapability implements Capability {
    @Override
    public CapabilityResult caseSensitiveOnName(Scope scope) {
      return CapabilityResult.unsupported("case-insensitive");
    }
  }
}
