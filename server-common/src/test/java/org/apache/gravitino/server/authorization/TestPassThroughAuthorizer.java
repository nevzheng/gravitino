/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.authorization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Test of {@link PassThroughAuthorizer} */
public class TestPassThroughAuthorizer {

  @Test
  public void testInitializeWithContextRunsTheOverridableInitialize() {
    AtomicBoolean initialized = new AtomicBoolean();
    PassThroughAuthorizer authorizer =
        new PassThroughAuthorizer() {
          @Override
          public void initialize() {
            initialized.set(true);
          }
        };

    authorizer.initialize(mock(AuthorizerContext.class));

    Assertions.assertTrue(initialized.get());
  }

  @Test
  public void testPassThroughAuthorizerDefaults() throws IOException {
    try (PassThroughAuthorizer passThroughAuthorizer = new PassThroughAuthorizer()) {
      passThroughAuthorizer.initialize(mock(AuthorizerContext.class));
      Principal principal = mock(Principal.class);
      when(principal.getName()).thenReturn("testUser");
      MetadataObject metadataObject =
          MetadataObjects.of("catalog", "schema", MetadataObject.Type.SCHEMA);
      Assertions.assertTrue(
          passThroughAuthorizer.authorize(
              principal,
              "metalake",
              metadataObject,
              Privilege.Name.CREATE_SCHEMA,
              new AuthorizationRequestContext()));

      Assertions.assertFalse(
          passThroughAuthorizer.deny(
              principal,
              "metalake",
              metadataObject,
              Privilege.Name.CREATE_SCHEMA,
              new AuthorizationRequestContext()));

      Assertions.assertTrue(
          passThroughAuthorizer.isOwner(
              principal, "metalake", metadataObject, new AuthorizationRequestContext()));
      Assertions.assertTrue(passThroughAuthorizer.isServiceAdmin());
      Assertions.assertTrue(
          passThroughAuthorizer.isMetalakeUser("metalake", new AuthorizationRequestContext()));
      Assertions.assertTrue(
          passThroughAuthorizer.isSelf(
              Entity.EntityType.USER, null, new AuthorizationRequestContext()));
      Assertions.assertTrue(
          passThroughAuthorizer.hasSetOwnerPermission(
              "metalake", "type", "fullName", new AuthorizationRequestContext()));
      Assertions.assertTrue(
          passThroughAuthorizer.hasMetadataPrivilegePermission(
              "metalake", "type", "fullName", new AuthorizationRequestContext()));
    }
  }

  @Test
  public void testIsMetalakeUserNeedsAContext() throws IOException {
    try (PassThroughAuthorizer passThroughAuthorizer = new PassThroughAuthorizer()) {
      passThroughAuthorizer.initialize();

      Assertions.assertThrows(
          IllegalStateException.class,
          () ->
              passThroughAuthorizer.isMetalakeUser("metalake", new AuthorizationRequestContext()));
    }
  }

  @Test
  public void testIsMetalakeUserUsesInternalAccessControlDispatcher() throws Exception {
    AccessControlDispatcher dispatcher = mock(AccessControlDispatcher.class);
    User user = mock(User.class);
    when(dispatcher.getUser("metalake", "testUser")).thenReturn(user);
    AuthorizerContext context = mock(AuthorizerContext.class);
    when(context.internalAccessControlDispatcher()).thenReturn(dispatcher);

    try (PassThroughAuthorizer passThroughAuthorizer = new PassThroughAuthorizer()) {
      passThroughAuthorizer.initialize(context);
      PrincipalUtils.doAs(
          new UserPrincipal("testUser"),
          () -> {
            Assertions.assertTrue(
                passThroughAuthorizer.isMetalakeUser(
                    "metalake", new AuthorizationRequestContext()));
            return null;
          });
      verify(dispatcher).getUser("metalake", "testUser");
    }
  }

  @Test
  public void testIsMetalakeUserAddsAMissingUser() throws Exception {
    AccessControlDispatcher dispatcher = mock(AccessControlDispatcher.class);
    when(dispatcher.getUser("metalake", "testUser"))
        .thenThrow(new NoSuchUserException("User %s does not exist", "testUser"));
    AuthorizerContext context = mock(AuthorizerContext.class);
    when(context.internalAccessControlDispatcher()).thenReturn(dispatcher);

    try (PassThroughAuthorizer passThroughAuthorizer = new PassThroughAuthorizer()) {
      passThroughAuthorizer.initialize(context);
      PrincipalUtils.doAs(
          new UserPrincipal("testUser"),
          () ->
              passThroughAuthorizer.isMetalakeUser("metalake", new AuthorizationRequestContext()));
      verify(dispatcher).addUser("metalake", "testUser");
    }
  }
}
