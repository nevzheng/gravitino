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
package org.apache.gravitino.server.authorization.jcasbin;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

public class TestJcasbinRolePrivilegeChange {

  @Test
  public void testRoleIsFoundThroughTheContextEntityStore() throws Exception {
    EntityStore store = mock(EntityStore.class);
    RoleEntity role = mock(RoleEntity.class);
    when(role.id()).thenReturn(7L);
    when(store.get(
            NameIdentifierUtil.ofRole("metalake", "role"),
            Entity.EntityType.ROLE,
            RoleEntity.class))
        .thenReturn(role);
    JcasbinAuthorizer authorizer = authorizerWith(store);
    doNothing().when(authorizer).handleRolePrivilegeChange(7L);

    authorizer.handleRolePrivilegeChange("metalake", "role");

    verify(authorizer).handleRolePrivilegeChange(7L);
  }

  @Test
  public void testMissingRoleIsReported() throws Exception {
    EntityStore store = mock(EntityStore.class);
    when(store.get(
            NameIdentifierUtil.ofRole("metalake", "role"),
            Entity.EntityType.ROLE,
            RoleEntity.class))
        .thenThrow(new IOException("missing"));
    JcasbinAuthorizer authorizer = authorizerWith(store);

    Assertions.assertThrows(
        RuntimeException.class, () -> authorizer.handleRolePrivilegeChange("metalake", "role"));
  }

  private static JcasbinAuthorizer authorizerWith(EntityStore store) throws IllegalAccessException {
    AuthorizerContext context = mock(AuthorizerContext.class);
    when(context.entityStore()).thenReturn(store);
    JcasbinAuthorizer authorizer =
        mock(JcasbinAuthorizer.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
    FieldUtils.writeField(authorizer, "context", context, true);
    return authorizer;
  }
}
