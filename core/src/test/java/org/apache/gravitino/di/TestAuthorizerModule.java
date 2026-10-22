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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.security.Principal;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class TestAuthorizerModule {

  @AfterEach
  public void tearDown() {
    RecordingAuthorizer.reset();
  }

  @Test
  public void testDisabledAuthorizationUsesTheNoopAuthorizer() throws Exception {
    ManagedServices services = new ManagedServices();

    GravitinoAuthorizer authorizer =
        AuthorizerModule.gravitinoAuthorizer(
            false, new Config(false) {}, mock(AuthorizerContext.class), services);

    assertSame(NoopGravitinoAuthorizer.INSTANCE, authorizer);
    assertTrue(services.services().isEmpty());
  }

  @Test
  public void testEnabledAuthorizationBuildsTheConfiguredClass() throws Exception {
    Config config = new Config(false) {};
    config.set(Configs.AUTHORIZATION_IMPL, RecordingAuthorizer.class.getName());
    AuthorizerContext context = mock(AuthorizerContext.class);
    ManagedServices services = new ManagedServices();

    GravitinoAuthorizer authorizer =
        AuthorizerModule.gravitinoAuthorizer(true, config, context, services);

    assertInstanceOf(RecordingAuthorizer.class, authorizer);
    assertSame(context, RecordingAuthorizer.context);
    assertEquals(1, services.services().size());
    services.close();
    assertEquals(1, RecordingAuthorizer.closed);
  }

  @Test
  public void testAnAuthorizerThatFailsToInitializeIsClosed() {
    Config config = new Config(false) {};
    config.set(Configs.AUTHORIZATION_IMPL, RecordingAuthorizer.class.getName());
    RecordingAuthorizer.failInitialize = true;
    ManagedServices services = new ManagedServices();

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                AuthorizerModule.gravitinoAuthorizer(
                    true, config, mock(AuthorizerContext.class), services));

    assertEquals("cannot initialize", e.getMessage());
    assertEquals(1, RecordingAuthorizer.closed);
    assertTrue(services.services().isEmpty());
  }

  @Test
  public void testUnknownClassIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AuthorizerModule.newAuthorizer("org.apache.gravitino.NoSuchAuthorizer"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AuthorizerModule.newAuthorizer(String.class.getName()));
  }

  @Test
  public void testTheComponentBuildsTheAuthorizerOnce() throws Exception {
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(TestGravitinoComponent.testConfig());
    try {
      assertSame(NoopGravitinoAuthorizer.INSTANCE, component.gravitinoAuthorizer());
      assertSame(component.gravitinoAuthorizer(), component.gravitinoAuthorizer());
    } finally {
      component.managedServices().close();
    }
  }

  /** An authorizer that records how it is initialized and closed. */
  public static class RecordingAuthorizer implements GravitinoAuthorizer {
    static AuthorizerContext context;
    static boolean failInitialize;
    static int closed;

    static void reset() {
      context = null;
      failInitialize = false;
      closed = 0;
    }

    @Override
    public void initialize() {}

    @Override
    public void initialize(AuthorizerContext context) {
      if (failInitialize) {
        throw new IllegalStateException("cannot initialize");
      }
      RecordingAuthorizer.context = context;
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
    public void handleRolePrivilegeChange(String metalake, String roleName) {}

    @Override
    public void handleMetadataOwnerChange(
        String metalake, Long oldOwnerId, NameIdentifier nameIdentifier, Entity.EntityType type) {}

    @Override
    public void close() {
      closed++;
    }
  }
}
