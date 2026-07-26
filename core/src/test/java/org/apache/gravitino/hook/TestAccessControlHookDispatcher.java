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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestAccessControlHookDispatcher {

  private AccessControlHookDispatcher hookDispatcher;
  private AccessControlDispatcher mockDispatcher;
  private OwnerDispatcher mockOwnerDispatcher;
  private GravitinoAuthorizer mockAuthorizer;
  private AtomicReference<GravitinoAuthorizer> authorizerReference;

  @BeforeEach
  public void setUp() {
    mockDispatcher = mock(AccessControlDispatcher.class);
    mockOwnerDispatcher = mock(OwnerDispatcher.class);
    mockAuthorizer = mock(GravitinoAuthorizer.class);
    authorizerReference = new AtomicReference<>();
    hookDispatcher =
        new AccessControlHookDispatcher(
            mockDispatcher, mockOwnerDispatcher, authorizerReference::get);
    authorizerReference.set(mockAuthorizer);
  }

  @Test
  public void testCreateRoleThrowsWhenSetOwnerFails() {
    Role mockRole = mock(Role.class);
    when(mockDispatcher.createRole(any(), any(), any(), any())).thenReturn(mockRole);

    doThrow(new RuntimeException("Set owner failed"))
        .when(mockOwnerDispatcher)
        .setOwner(any(), any(), any(), any());

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                hookDispatcher.createRole(
                    "test_metalake", "test_role", Collections.emptyMap(), Collections.emptyList()));
    Assertions.assertEquals("Set owner failed", thrown.getMessage());
    verify(mockDispatcher).createRole(any(), any(), any(), any());
  }

  @Test
  public void testGrantRolesToUserInvalidatesUserRoleRelation() {
    User mockUser = mock(User.class);
    when(mockDispatcher.grantRolesToUser(
            eq("test_metalake"), eq(Collections.singletonList("test_role")), eq("test_user")))
        .thenReturn(mockUser);

    hookDispatcher.grantRolesToUser(
        "test_metalake", Collections.singletonList("test_role"), "test_user");

    verify(mockAuthorizer).handleRolePrivilegeChange("test_metalake", "test_role");
    verify(mockAuthorizer).handleUserRoleRelChange("test_metalake", "test_user");
  }

  @Test
  public void testResolvesAuthorizerAfterDispatcherConstruction() {
    GravitinoAuthorizer lateAuthorizer = mock(GravitinoAuthorizer.class);
    User mockUser = mock(User.class);
    when(mockDispatcher.grantRolesToUser(
            eq("test_metalake"), eq(Collections.singletonList("test_role")), eq("test_user")))
        .thenReturn(mockUser);

    authorizerReference.set(lateAuthorizer);
    hookDispatcher.grantRolesToUser(
        "test_metalake", Collections.singletonList("test_role"), "test_user");

    verify(lateAuthorizer).handleRolePrivilegeChange("test_metalake", "test_role");
    verify(lateAuthorizer).handleUserRoleRelChange("test_metalake", "test_user");
  }

  @Test
  public void testSkipsNotificationWhenAuthorizationIsDisabled() {
    User mockUser = mock(User.class);
    when(mockDispatcher.grantRolesToUser(
            eq("test_metalake"), eq(Collections.singletonList("test_role")), eq("test_user")))
        .thenReturn(mockUser);
    authorizerReference.set(null);

    hookDispatcher.grantRolesToUser(
        "test_metalake", Collections.singletonList("test_role"), "test_user");

    verify(mockDispatcher)
        .grantRolesToUser("test_metalake", Collections.singletonList("test_role"), "test_user");
    verifyNoInteractions(mockAuthorizer);
  }

  @Test
  public void testGrantRolesToGroupInvalidatesGroupRoleRelation() {
    Group mockGroup = mock(Group.class);
    when(mockDispatcher.grantRolesToGroup(
            eq("test_metalake"), eq(Collections.singletonList("test_role")), eq("test_group")))
        .thenReturn(mockGroup);

    hookDispatcher.grantRolesToGroup(
        "test_metalake", Collections.singletonList("test_role"), "test_group");

    verify(mockAuthorizer).handleRolePrivilegeChange("test_metalake", "test_role");
    verify(mockAuthorizer).handleGroupRoleRelChange("test_metalake", "test_group");
  }
}
