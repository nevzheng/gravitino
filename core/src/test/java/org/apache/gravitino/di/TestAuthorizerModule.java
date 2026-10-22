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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.CatalogAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks how {@link AuthorizerModule} builds, initializes and closes the authorizer. */
public class TestAuthorizerModule {

  @BeforeEach
  public void setUp() {
    RecordingAuthorizer.reset();
  }

  @Test
  public void testDisabledAuthorizationBindsTheNoopAuthorizer() throws Exception {
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(new Config(false) {});

    assertSame(NoopGravitinoAuthorizer.INSTANCE, component.gravitinoAuthorizer());
    assertSame(
        NoopGravitinoAuthorizer.INSTANCE, component.authorizationServices().gravitinoAuthorizer());
    assertSame(
        NoopGravitinoAuthorizer.INSTANCE, component.auxiliaryContext().gravitinoAuthorizer());
    component.lifecycle().close();
  }

  @Test
  public void testEnabledAuthorizationLoadsAndInitializesTheConfiguredClass() throws Exception {
    Config config = enabledConfig(RecordingAuthorizer.class.getName());
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);

    GravitinoAuthorizer authorizer = component.gravitinoAuthorizer();

    RecordingAuthorizer recording = assertInstanceOf(RecordingAuthorizer.class, authorizer);
    assertSame(authorizer, component.gravitinoAuthorizer());
    assertSame(authorizer, component.authorizationServices().gravitinoAuthorizer());
    assertSame(authorizer, component.auxiliaryContext().gravitinoAuthorizer());
    assertEquals(1, RecordingAuthorizer.CREATED.size());
    assertInstanceOf(ComponentAuthorizerContext.class, recording.context);
    assertSame(config, recording.context.config());

    component.lifecycle().close();
    assertTrue(recording.closed);
  }

  @Test
  public void testTheAuthorizerIsNotBuiltUntilRequested() throws Exception {
    GravitinoComponent component =
        DaggerGravitinoComponent.factory()
            .create(enabledConfig(RecordingAuthorizer.class.getName()));

    // Building the component, the services that hand the authorizer out and the lifecycle builds
    // no authorizer, and closing the lifecycle does not build one to close it.
    component.authorizationServices();
    component.auxiliaryContext();
    component.lifecycle().close();

    assertTrue(RecordingAuthorizer.CREATED.isEmpty());
  }

  @Test
  public void testAnAuthorizerThatFailsToInitializeIsClosed() {
    RecordingAuthorizer.failInitialize = true;
    GravitinoComponent component =
        DaggerGravitinoComponent.factory()
            .create(enabledConfig(RecordingAuthorizer.class.getName()));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, component::gravitinoAuthorizer);

    assertEquals("cannot initialize", failure.getMessage());
    assertEquals(1, RecordingAuthorizer.CREATED.size());
    assertTrue(RecordingAuthorizer.CREATED.get(0).closed);
  }

  @Test
  public void testAClassThatCannotBeLoadedIsRejected() {
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> AuthorizerModule.newAuthorizer("org.apache.gravitino.NoSuchAuthorizer"));
    assertInstanceOf(ClassNotFoundException.class, missing.getCause());

    IllegalArgumentException notAnAuthorizer =
        assertThrows(
            IllegalArgumentException.class,
            () -> AuthorizerModule.newAuthorizer(String.class.getName()));
    assertInstanceOf(ClassCastException.class, notAnAuthorizer.getCause());
  }

  @Test
  public void testTheContextHidesTheAccessControlDispatcherWhileDisabled() {
    Config config = new Config(false) {};
    EntityStore entityStore = mock(EntityStore.class);
    CatalogAccess catalogAccess = mock(CatalogAccess.class);
    AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);

    ComponentAuthorizerContext disabled =
        new ComponentAuthorizerContext(
            config, () -> entityStore, () -> catalogAccess, false, () -> accessControl);
    ComponentAuthorizerContext enabled =
        new ComponentAuthorizerContext(
            config, () -> entityStore, () -> catalogAccess, true, () -> accessControl);

    assertSame(config, disabled.config());
    assertSame(entityStore, disabled.entityStore());
    assertSame(catalogAccess, disabled.catalogAccess());
    assertNull(disabled.internalAccessControlDispatcher());
    assertSame(accessControl, enabled.internalAccessControlDispatcher());
  }

  @Test
  public void testTheAuthorizerIsAModuleOfItsOwn() {
    // A test component replaces the authorizer by leaving this module out.
    List<Class<?>> includes =
        List.of(GravitinoModules.class.getAnnotation(Module.class).includes());
    assertTrue(includes.contains(AuthorizerModule.class));
    assertTrue(includes.contains(AuthorizationModule.class));
    assertFalse(
        List.of(ModuleTestComponent.class.getAnnotation(Component.class).modules())
            .contains(AuthorizerModule.class));
  }

  private static Config enabledConfig(String authorizerClass) {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    config.set(Configs.AUTHORIZATION_IMPL, authorizerClass);
    return config;
  }

  /** An authorizer that records how it was built, initialized and closed. */
  public static class RecordingAuthorizer implements GravitinoAuthorizer {
    static final List<RecordingAuthorizer> CREATED = new ArrayList<>();
    static boolean failInitialize;

    AuthorizerContext context;
    boolean closed;

    /** Creates the authorizer and records it. */
    public RecordingAuthorizer() {
      CREATED.add(this);
    }

    /**
     * Returns whether the authorizer was closed.
     *
     * @return true once closed
     */
    public boolean isClosed() {
      return closed;
    }

    static void reset() {
      CREATED.clear();
      failInitialize = false;
    }

    @Override
    public void initialize() {}

    @Override
    public void initialize(AuthorizerContext context) {
      if (failInitialize) {
        throw new IllegalStateException("cannot initialize");
      }
      this.context = context;
    }

    @Override
    public boolean authorize(
        Principal principal,
        String metalake,
        MetadataObject metadataObject,
        Privilege.Name privilege,
        AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public boolean deny(
        Principal principal,
        String metalake,
        MetadataObject metadataObject,
        Privilege.Name privilege,
        AuthorizationRequestContext requestContext) {
      return false;
    }

    @Override
    public boolean isOwner(
        Principal principal,
        String metalake,
        MetadataObject metadataObject,
        AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public boolean isServiceAdmin() {
      return true;
    }

    @Override
    public boolean isSelf(
        Entity.EntityType type,
        NameIdentifier nameIdentifier,
        AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public boolean isMetalakeUser(String metalake, AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public boolean hasSetOwnerPermission(
        String metalake, String type, String fullName, AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public boolean hasMetadataPrivilegePermission(
        String metalake, String type, String fullName, AuthorizationRequestContext requestContext) {
      return true;
    }

    @Override
    public void handleRolePrivilegeChange(Long roleId) {}

    @Override
    public void handleMetadataOwnerChange(
        String metalake, Long oldOwnerId, NameIdentifier nameIdentifier, Entity.EntityType type) {}

    @Override
    public void close() {
      closed = true;
    }
  }
}
