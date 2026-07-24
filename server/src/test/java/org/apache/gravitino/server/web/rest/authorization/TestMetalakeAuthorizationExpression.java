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
package org.apache.gravitino.server.web.rest.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import ognl.OgnlException;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.rest.MetalakeOperations;
import org.junit.jupiter.api.Test;

public class TestMetalakeAuthorizationExpression {

  @Test
  public void testLoadMetalakePreservesMetalakeUsersAndAllowsServiceAdmin()
      throws NoSuchMethodException {
    Method method =
        MetalakeOperations.class.getMethod(
            "loadMetalake", String.class, String.class, String.class);
    AuthorizationExpression annotation = method.getAnnotation(AuthorizationExpression.class);

    assertEquals("SERVICE_ADMIN || METALAKE_USER", annotation.expression());
  }

  @Test
  public void testRestoreMetalakeRequiresServiceAdmin()
      throws NoSuchMethodException, OgnlException {
    Method method =
        MetalakeOperations.class.getMethod(
            "restoreMetalake",
            String.class,
            String.class,
            String.class,
            String.class,
            EntityRestoreRequest.class);
    AuthorizationExpression annotation = method.getAnnotation(AuthorizationExpression.class);
    MockAuthorizationExpressionEvaluator evaluator =
        new MockAuthorizationExpressionEvaluator(annotation.expression());

    assertFalse(evaluator.getResult(ImmutableSet.of()));
    assertTrue(evaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertFalse(evaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
  }
}
