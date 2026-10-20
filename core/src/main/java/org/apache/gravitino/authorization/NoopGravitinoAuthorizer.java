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

import java.security.Principal;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;

/**
 * The authorizer in effect when none is installed, for example before the server installs its
 * authorizer or in a process that never does.
 *
 * <p>It stands in for the {@code null} authorizer callers used to test for: every check passes, and
 * every change notification does nothing. Unlike the server's pass-through authorizer it has no
 * side effects; in particular {@link #isMetalakeUser} never adds the current user to a metalake.
 */
public final class NoopGravitinoAuthorizer implements GravitinoAuthorizer {

  /** The only instance. */
  public static final NoopGravitinoAuthorizer INSTANCE = new NoopGravitinoAuthorizer();

  private NoopGravitinoAuthorizer() {}

  @Override
  public void initialize() {}

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

  /** Does nothing; unlike the interface default, it does not look the role up. */
  @Override
  public void handleRolePrivilegeChange(String metalake, String roleName) {}

  @Override
  public void handleUserRoleRelChange(String metalake, String userName) {}

  @Override
  public void handleGroupRoleRelChange(String metalake, String groupName) {}

  @Override
  public void handleMetadataOwnerChange(
      String metalake, Long oldOwnerId, NameIdentifier nameIdentifier, Entity.EntityType type) {}

  @Override
  public void handleEntityNameIdMappingChange(
      String metalake, NameIdentifier nameIdentifier, Entity.EntityType type) {}

  @Override
  public void close() {}
}
