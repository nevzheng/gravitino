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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.MetadataObjectChange;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogTestUtils;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.exceptions.AuthorizationPluginException;
import org.apache.gravitino.exceptions.CatalogInUseException;
import org.apache.gravitino.exceptions.NonEmptyCatalogException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestCatalogHookDispatcher {

  @Test
  public void testCreateCatalogThrowsPostHookExceptionWhenRollbackSucceeds() throws Exception {
    GravitinoEnv gravitinoEnv = GravitinoEnv.getInstance();
    Object originalOwnerDispatcher =
        FieldUtils.readField(gravitinoEnv, "internalOwnerDispatcher", true);
    Object originalFutureGrantManager =
        FieldUtils.readField(gravitinoEnv, "futureGrantManager", true);

    CatalogManager dispatcher = Mockito.mock(CatalogManager.class);
    Catalog catalog = Mockito.mock(Catalog.class);
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    RuntimeException postHookException = new RuntimeException("post-hook failed");

    OwnerDispatcher ownerDispatcher = Mockito.mock(OwnerDispatcher.class);
    Mockito.doThrow(postHookException)
        .when(ownerDispatcher)
        .setOwner(Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any());
    Mockito.when(
            dispatcher.createCatalog(
                Mockito.eq(ident),
                Mockito.eq(Catalog.Type.RELATIONAL),
                Mockito.eq("provider"),
                Mockito.eq("comment"),
                Mockito.anyMap(),
                Mockito.anyMap(),
                Mockito.anyMap()))
        .thenReturn(catalog);

    FieldUtils.writeField(gravitinoEnv, "internalOwnerDispatcher", ownerDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "futureGrantManager", null, true);

    try {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      RuntimeException thrown =
          assertThrowsExactly(
              RuntimeException.class,
              () ->
                  hookDispatcher.createCatalog(
                      ident,
                      Catalog.Type.RELATIONAL,
                      "provider",
                      "comment",
                      Collections.emptyMap()));
      assertSame(postHookException, thrown);

      Mockito.verify(dispatcher).dropCatalog(ident, true);
    } finally {
      FieldUtils.writeField(gravitinoEnv, "internalOwnerDispatcher", originalOwnerDispatcher, true);
      FieldUtils.writeField(gravitinoEnv, "futureGrantManager", originalFutureGrantManager, true);
    }
  }

  @Test
  public void testCreateCatalogRollbackExceptionDoesNotMaskPostHookException() throws Exception {
    GravitinoEnv gravitinoEnv = GravitinoEnv.getInstance();
    Object originalOwnerDispatcher =
        FieldUtils.readField(gravitinoEnv, "internalOwnerDispatcher", true);
    Object originalFutureGrantManager =
        FieldUtils.readField(gravitinoEnv, "futureGrantManager", true);

    CatalogManager dispatcher = Mockito.mock(CatalogManager.class);
    Catalog catalog = Mockito.mock(Catalog.class);
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    RuntimeException postHookException = new RuntimeException("post-hook failed");
    RuntimeException rollbackException = new RuntimeException("rollback failed");

    OwnerDispatcher ownerDispatcher = Mockito.mock(OwnerDispatcher.class);
    Mockito.doThrow(postHookException)
        .when(ownerDispatcher)
        .setOwner(Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any());
    Mockito.when(
            dispatcher.createCatalog(
                Mockito.eq(ident),
                Mockito.eq(Catalog.Type.RELATIONAL),
                Mockito.eq("provider"),
                Mockito.eq("comment"),
                Mockito.anyMap(),
                Mockito.anyMap(),
                Mockito.anyMap()))
        .thenReturn(catalog);
    Mockito.doThrow(rollbackException).when(dispatcher).dropCatalog(ident, true);

    FieldUtils.writeField(gravitinoEnv, "internalOwnerDispatcher", ownerDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "futureGrantManager", null, true);

    try {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      RuntimeException thrown =
          assertThrowsExactly(
              RuntimeException.class,
              () ->
                  hookDispatcher.createCatalog(
                      ident,
                      Catalog.Type.RELATIONAL,
                      "provider",
                      "comment",
                      Collections.emptyMap()));
      assertSame(postHookException, thrown);
      assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(t -> t == rollbackException));

      Mockito.verify(dispatcher).dropCatalog(ident, true);
    } finally {
      FieldUtils.writeField(gravitinoEnv, "internalOwnerDispatcher", originalOwnerDispatcher, true);
      FieldUtils.writeField(gravitinoEnv, "futureGrantManager", originalFutureGrantManager, true);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRefusedDropCatalogKeepsPluginPrivileges(boolean inUse) {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    RuntimeException refusal =
        inUse
            ? new CatalogInUseException("Catalog %s is in use", ident)
            : new NonEmptyCatalogException("Catalog %s has schemas", ident);
    // Every dropCatalog overload refuses the drop before running any callback, like
    // CatalogManager does for an enabled or non-empty catalog when force is false.
    CatalogManager dispatcher =
        Mockito.mock(
            CatalogManager.class,
            invocation -> {
              switch (invocation.getMethod().getName()) {
                case "catalogExists":
                  return true;
                case "dropCatalog":
                  throw refusal;
                default:
                  return Mockito.RETURNS_DEFAULTS.answer(invocation);
              }
            });
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);
    GravitinoAuthorizer authorizer = Mockito.mock(GravitinoAuthorizer.class);

    try (MockedStatic<GravitinoEnv> envStatic = mockEnv(plugin, authorizer)) {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      RuntimeException thrown =
          assertThrows(RuntimeException.class, () -> hookDispatcher.dropCatalog(ident, false));
      assertSame(refusal, thrown);
    }

    Mockito.verifyNoInteractions(plugin);
    Mockito.verifyNoInteractions(authorizer);
  }

  @Test
  public void testSuccessfulDropCatalogRemovesPluginPrivilegesBeforeDelete() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);
    AtomicBoolean deleted = new AtomicBoolean(false);
    AtomicBoolean pluginCalledBeforeDelete = new AtomicBoolean(false);
    Mockito.doAnswer(
            invocation -> {
              pluginCalledBeforeDelete.set(!deleted.get());
              return true;
            })
        .when(plugin)
        .onMetadataUpdated(Mockito.any());
    CatalogManager dispatcher = mockDropRunningCallback(deleted, true);
    GravitinoAuthorizer authorizer = Mockito.mock(GravitinoAuthorizer.class);

    try (MockedStatic<GravitinoEnv> envStatic = mockEnv(plugin, authorizer)) {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      assertTrue(hookDispatcher.dropCatalog(ident, true));
    }

    assertTrue(deleted.get());
    assertTrue(pluginCalledBeforeDelete.get());
    Mockito.verify(dispatcher, Mockito.never()).dropCatalog(ident, true);
    ArgumentCaptor<MetadataObjectChange[]> changes =
        ArgumentCaptor.forClass(MetadataObjectChange[].class);
    Mockito.verify(plugin).onMetadataUpdated(changes.capture());
    assertEquals(1, changes.getValue().length);
    MetadataObjectChange.RemoveMetadataObject removal =
        assertInstanceOf(MetadataObjectChange.RemoveMetadataObject.class, changes.getValue()[0]);
    assertEquals(MetadataObject.Type.CATALOG, removal.metadataObject().type());
    assertEquals("catalog", removal.metadataObject().fullName());
    Mockito.verify(authorizer)
        .handleEntityNameIdMappingChange("metalake", ident, Entity.EntityType.CATALOG);
  }

  @Test
  public void testPluginFailureAbortsDropCatalog() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);
    AuthorizationPluginException pluginFailure = new AuthorizationPluginException("unreachable");
    Mockito.doThrow(pluginFailure).when(plugin).onMetadataUpdated(Mockito.any());
    AtomicBoolean deleted = new AtomicBoolean(false);
    CatalogManager dispatcher = mockDropRunningCallback(deleted, true);
    GravitinoAuthorizer authorizer = Mockito.mock(GravitinoAuthorizer.class);

    try (MockedStatic<GravitinoEnv> envStatic = mockEnv(plugin, authorizer)) {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      assertSame(
          pluginFailure,
          assertThrows(
              AuthorizationPluginException.class, () -> hookDispatcher.dropCatalog(ident, true)));
    }

    assertFalse(deleted.get());
    Mockito.verifyNoInteractions(authorizer);
  }

  @Test
  public void testDropMissingCatalogDoesNotCallPlugin() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    CatalogManager dispatcher = Mockito.mock(CatalogManager.class);
    Mockito.when(dispatcher.catalogExists(ident)).thenReturn(false);
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);

    try (MockedStatic<GravitinoEnv> envStatic = mockEnv(plugin, null)) {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      assertFalse(hookDispatcher.dropCatalog(ident, true));
    }

    Mockito.verify(dispatcher, Mockito.never())
        .dropCatalog(Mockito.any(), Mockito.anyBoolean(), Mockito.any());
    Mockito.verifyNoInteractions(plugin);
  }

  @Test
  public void testDropCatalogReturningFalseDoesNotNotify() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog");
    // The catalog exists at the pre-check but another server drops it first, so the drop returns
    // false without running the callback.
    CatalogManager dispatcher = Mockito.mock(CatalogManager.class);
    Mockito.when(dispatcher.catalogExists(ident)).thenReturn(true);
    Mockito.when(dispatcher.dropCatalog(Mockito.eq(ident), Mockito.eq(true), Mockito.any()))
        .thenReturn(false);
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);
    GravitinoAuthorizer authorizer = Mockito.mock(GravitinoAuthorizer.class);

    try (MockedStatic<GravitinoEnv> envStatic = mockEnv(plugin, authorizer)) {
      CatalogHookDispatcher hookDispatcher = new CatalogHookDispatcher(dispatcher);
      assertFalse(hookDispatcher.dropCatalog(ident, true));
    }

    Mockito.verifyNoInteractions(plugin);
    Mockito.verifyNoInteractions(authorizer);
  }

  /**
   * Mocks a catalog manager whose three-argument drop runs the callback, then sets {@code deleted}
   * and returns {@code result}, like {@link CatalogManager} does. A callback failure propagates
   * before {@code deleted} is set.
   */
  private static CatalogManager mockDropRunningCallback(AtomicBoolean deleted, boolean result) {
    return Mockito.mock(
        CatalogManager.class,
        invocation -> {
          switch (invocation.getMethod().getName()) {
            case "catalogExists":
              return true;
            case "dropCatalog":
              if (invocation.getArguments().length == 3) {
                Runnable beforeDelete = invocation.getArgument(2);
                beforeDelete.run();
                deleted.set(true);
                return result;
              }
              throw new AssertionError("The hook must drop through the callback overload");
            default:
              return Mockito.RETURNS_DEFAULTS.answer(invocation);
          }
        });
  }

  private static MockedStatic<GravitinoEnv> mockEnv(
      AuthorizationPlugin plugin, GravitinoAuthorizer authorizer) {
    BaseCatalog<?> catalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(catalog.name()).thenReturn("catalog");
    Mockito.when(catalog.getAuthorizationPlugin()).thenReturn(plugin);
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    CatalogTestUtils.mockDoWithCatalog(catalogManager, catalog);
    GravitinoEnv env = Mockito.mock(GravitinoEnv.class);
    Mockito.when(env.catalogManager()).thenReturn(catalogManager);
    Mockito.when(env.gravitinoAuthorizer()).thenReturn(authorizer);
    MockedStatic<GravitinoEnv> envStatic = Mockito.mockStatic(GravitinoEnv.class);
    envStatic.when(GravitinoEnv::getInstance).thenReturn(env);
    return envStatic;
  }
}
