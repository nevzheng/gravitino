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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.security.Principal;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.junit.jupiter.api.Test;

public class TestNoopGravitinoAuthorizer {

  private final NoopGravitinoAuthorizer authorizer = NoopGravitinoAuthorizer.INSTANCE;
  private final AuthorizationRequestContext context = new AuthorizationRequestContext();

  @Test
  public void testEveryCheckPasses() {
    Principal principal = mock(Principal.class);
    MetadataObject object = mock(MetadataObject.class);

    assertTrue(authorizer.authorize(principal, "m", object, Privilege.Name.USE_CATALOG, context));
    assertFalse(authorizer.deny(principal, "m", object, Privilege.Name.USE_CATALOG, context));
    assertTrue(authorizer.isOwner(principal, "m", object, context));
    assertTrue(authorizer.isServiceAdmin());
    assertTrue(authorizer.isSelf(Entity.EntityType.USER, NameIdentifier.of("m", "u"), context));
    assertTrue(authorizer.isMetalakeUser("m", context));
    assertTrue(authorizer.hasSetOwnerPermission("m", "CATALOG", "c", context));
    assertTrue(authorizer.hasMetadataPrivilegePermission("m", "CATALOG", "c", context));
  }

  @Test
  public void testNotificationsDoNothing() {
    // The interface default of handleRolePrivilegeChange(String, String) reads the entity store
    // through GravitinoEnv; the no-op must not, so this passes on an uninitialized environment.
    authorizer.handleRolePrivilegeChange("m", "role");
    authorizer.handleRolePrivilegeChange(1L);
    authorizer.handleUserRoleRelChange("m", "user");
    authorizer.handleGroupRoleRelChange("m", "group");
    authorizer.handleMetadataOwnerChange(
        "m", 1L, NameIdentifier.of("m", "c"), Entity.EntityType.CATALOG);
    authorizer.handleEntityNameIdMappingChange(
        "m", NameIdentifier.of("m", "c"), Entity.EntityType.CATALOG);
    authorizer.initialize();
    authorizer.close();
  }
}
