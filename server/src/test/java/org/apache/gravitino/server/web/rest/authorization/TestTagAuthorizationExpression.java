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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import ognl.OgnlException;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.rest.TagOperations;
import org.junit.jupiter.api.Test;

/** Tests live-compatible and deleted-resource authorization for tag REST operations. */
public class TestTagAuthorizationExpression {

  /** Verifies collection reads preserve live behavior and check deleted access at runtime. */
  @Test
  public void testCollectionAuthorizationExpression() throws NoSuchMethodException {
    Method listMethod =
        TagOperations.class.getMethod(
            "listTags", String.class, boolean.class, String.class, String.class, String.class);
    assertEquals("", listMethod.getAnnotation(AuthorizationExpression.class).expression());
  }

  /** Verifies exact reads retain tag context while accepting service administrators. */
  @Test
  public void testItemAuthorizationExpression() throws NoSuchMethodException, OgnlException {
    Method getMethod =
        TagOperations.class.getMethod(
            "getTag", String.class, String.class, String.class, String.class);
    AuthorizationExpression annotation = getMethod.getAnnotation(AuthorizationExpression.class);
    assertEquals(MetadataObject.Type.TAG, annotation.accessMetadataType());
    MockAuthorizationExpressionEvaluator evaluator = evaluator(getMethod);
    assertFalse(evaluator.getResult(ImmutableSet.of()));
    assertTrue(evaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertTrue(evaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertTrue(evaluator.getResult(ImmutableSet.of("TAG::OWNER")));
    assertTrue(evaluator.getResult(ImmutableSet.of("METALAKE::APPLY_TAG")));
  }

  /** Verifies tag restore is temporarily restricted to service administrators. */
  @Test
  public void testRestoreAuthorizationExpression() throws NoSuchMethodException, OgnlException {
    Method restoreMethod =
        TagOperations.class.getMethod(
            "restoreTag",
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            EntityRestoreRequest.class);
    AuthorizationExpression annotation = restoreMethod.getAnnotation(AuthorizationExpression.class);
    assertEquals(MetadataObject.Type.METALAKE, annotation.accessMetadataType());
    MockAuthorizationExpressionEvaluator evaluator = evaluator(restoreMethod);
    assertFalse(evaluator.getResult(ImmutableSet.of()));
    assertTrue(evaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertFalse(evaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertFalse(evaluator.getResult(ImmutableSet.of("TAG::OWNER")));
    assertFalse(evaluator.getResult(ImmutableSet.of("METALAKE::APPLY_TAG")));
  }

  private static MockAuthorizationExpressionEvaluator evaluator(Method method)
      throws OgnlException {
    return new MockAuthorizationExpressionEvaluator(
        method.getAnnotation(AuthorizationExpression.class).expression());
  }
}
