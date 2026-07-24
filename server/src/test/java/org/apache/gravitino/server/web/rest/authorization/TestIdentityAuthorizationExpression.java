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
package org.apache.gravitino.server.web.rest.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import ognl.OgnlException;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.rest.GroupOperations;
import org.apache.gravitino.server.web.rest.RoleOperations;
import org.apache.gravitino.server.web.rest.UserOperations;
import org.junit.jupiter.api.Test;

/** Tests live-compatible and deleted-resource authorization for identity REST operations. */
public class TestIdentityAuthorizationExpression {

  /**
   * Verifies collection routes preserve live authorization while deleted reads check at runtime.
   */
  @Test
  public void testCollectionAuthorizationExpressions() throws NoSuchMethodException {
    Method userList =
        UserOperations.class.getMethod(
            "listUsers", String.class, boolean.class, String.class, String.class, String.class);
    Method groupList =
        GroupOperations.class.getMethod(
            "listGroups", String.class, boolean.class, String.class, String.class, String.class);
    Method roleList =
        RoleOperations.class.getMethod(
            "listRoles", String.class, String.class, String.class, String.class);

    assertEquals("", userList.getAnnotation(AuthorizationExpression.class).expression());
    assertEquals("", groupList.getAnnotation(AuthorizationExpression.class).expression());
    assertEquals("", roleList.getAnnotation(AuthorizationExpression.class).expression());
  }

  /** Verifies shared item reads retain live access and allow the admin-only deleted branch. */
  @Test
  public void testItemAuthorizationExpressions() throws NoSuchMethodException, OgnlException {
    Method userGet =
        UserOperations.class.getMethod(
            "getUser", String.class, String.class, String.class, String.class);
    MockAuthorizationExpressionEvaluator userEvaluator = evaluator(userGet);
    assertFalse(userEvaluator.getResult(ImmutableSet.of()));
    assertTrue(userEvaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertTrue(userEvaluator.getResult(ImmutableSet.of("USER::SELF")));
    assertTrue(userEvaluator.getResult(ImmutableSet.of("METALAKE::MANAGE_USERS")));

    Method groupGet =
        GroupOperations.class.getMethod(
            "getGroup", String.class, String.class, String.class, String.class);
    assertNull(groupGet.getAnnotation(AuthorizationExpression.class));

    Method roleGet =
        RoleOperations.class.getMethod(
            "getRole", String.class, String.class, String.class, String.class);
    MockAuthorizationExpressionEvaluator roleEvaluator = evaluator(roleGet);
    assertFalse(roleEvaluator.getResult(ImmutableSet.of()));
    assertTrue(roleEvaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertTrue(roleEvaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertTrue(roleEvaluator.getResult(ImmutableSet.of("ROLE::OWNER")));
  }

  /** Verifies identity restore routes are temporarily restricted to service administrators. */
  @Test
  public void testRestoreAuthorizationExpressions() throws NoSuchMethodException, OgnlException {
    assertServiceAdminOnly(restoreMethod(UserOperations.class, "restoreUser"));
    assertServiceAdminOnly(restoreMethod(GroupOperations.class, "restoreGroup"));
    assertServiceAdminOnly(restoreMethod(RoleOperations.class, "restoreRole"));
  }

  private static Method restoreMethod(Class<?> operationsClass, String methodName)
      throws NoSuchMethodException {
    return operationsClass.getMethod(
        methodName,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        EntityRestoreRequest.class);
  }

  private static MockAuthorizationExpressionEvaluator evaluator(Method method)
      throws OgnlException {
    return new MockAuthorizationExpressionEvaluator(
        method.getAnnotation(AuthorizationExpression.class).expression());
  }

  private static void assertServiceAdminOnly(Method method) throws OgnlException {
    AuthorizationExpression annotation = method.getAnnotation(AuthorizationExpression.class);
    assertEquals(MetadataObject.Type.METALAKE, annotation.accessMetadataType());
    MockAuthorizationExpressionEvaluator evaluator = evaluator(method);
    assertFalse(evaluator.getResult(ImmutableSet.of()));
    assertTrue(evaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertFalse(evaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertFalse(evaluator.getResult(ImmutableSet.of("USER::SELF")));
    assertFalse(evaluator.getResult(ImmutableSet.of("ROLE::OWNER")));
  }
}
