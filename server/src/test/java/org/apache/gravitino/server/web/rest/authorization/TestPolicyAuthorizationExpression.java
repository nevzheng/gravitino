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
import org.apache.gravitino.server.web.rest.PolicyOperations;
import org.junit.jupiter.api.Test;

/** Tests authorization expressions for live and deleted policy REST operations. */
public class TestPolicyAuthorizationExpression {

  /** Verifies that deleted policy reads and restores retain the temporary admin boundary. */
  @Test
  public void testDeletedPolicyReadAndRestoreAuthorization()
      throws NoSuchMethodException, OgnlException {
    Method listMethod =
        PolicyOperations.class.getMethod(
            "listPolicies", String.class, boolean.class, String.class, String.class, String.class);
    assertEquals("", listMethod.getAnnotation(AuthorizationExpression.class).expression());

    Method getMethod =
        PolicyOperations.class.getMethod(
            "getPolicy", String.class, String.class, String.class, String.class);
    MockAuthorizationExpressionEvaluator getEvaluator =
        new MockAuthorizationExpressionEvaluator(
            getMethod.getAnnotation(AuthorizationExpression.class).expression());
    assertFalse(getEvaluator.getResult(ImmutableSet.of()));
    assertTrue(getEvaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertTrue(getEvaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertTrue(getEvaluator.getResult(ImmutableSet.of("POLICY::OWNER")));

    Method restoreMethod =
        PolicyOperations.class.getMethod(
            "restorePolicy",
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            EntityRestoreRequest.class);
    MockAuthorizationExpressionEvaluator restoreEvaluator =
        new MockAuthorizationExpressionEvaluator(
            restoreMethod.getAnnotation(AuthorizationExpression.class).expression());
    assertFalse(restoreEvaluator.getResult(ImmutableSet.of()));
    assertTrue(restoreEvaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertFalse(restoreEvaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertFalse(restoreEvaluator.getResult(ImmutableSet.of("POLICY::OWNER")));
  }
}
