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
import org.apache.gravitino.server.web.rest.JobOperations;
import org.junit.jupiter.api.Test;

/** Tests live-compatible and deleted-resource authorization for job-template REST operations. */
public class TestJobTemplateAuthorizationExpression {

  /** Verifies collection reads preserve live behavior and check deleted access at runtime. */
  @Test
  public void testCollectionAuthorizationExpression() throws NoSuchMethodException {
    Method listMethod =
        JobOperations.class.getMethod(
            "listJobTemplates",
            String.class,
            boolean.class,
            String.class,
            String.class,
            String.class);
    assertEquals("", listMethod.getAnnotation(AuthorizationExpression.class).expression());
  }

  /** Verifies the shared exact read supports both live privileges and deleted-resource admins. */
  @Test
  public void testItemAuthorizationExpression() throws NoSuchMethodException, OgnlException {
    Method getMethod =
        JobOperations.class.getMethod(
            "getJobTemplate", String.class, String.class, String.class, String.class);
    MockAuthorizationExpressionEvaluator evaluator = evaluator(getMethod);
    assertFalse(evaluator.getResult(ImmutableSet.of()));
    assertTrue(evaluator.getResult(ImmutableSet.of("SERVICE_ADMIN")));
    assertTrue(evaluator.getResult(ImmutableSet.of("METALAKE::OWNER")));
    assertTrue(evaluator.getResult(ImmutableSet.of("JOB_TEMPLATE::OWNER")));
    assertTrue(evaluator.getResult(ImmutableSet.of("METALAKE::USE_JOB_TEMPLATE")));
  }

  /** Verifies job-template restore is temporarily restricted to service administrators. */
  @Test
  public void testRestoreAuthorizationExpression() throws NoSuchMethodException, OgnlException {
    Method restoreMethod =
        JobOperations.class.getMethod(
            "restoreJobTemplate",
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
    assertFalse(evaluator.getResult(ImmutableSet.of("JOB_TEMPLATE::OWNER")));
    assertFalse(evaluator.getResult(ImmutableSet.of("METALAKE::USE_JOB_TEMPLATE")));
  }

  private static MockAuthorizationExpressionEvaluator evaluator(Method method)
      throws OgnlException {
    return new MockAuthorizationExpressionEvaluator(
        method.getAnnotation(AuthorizationExpression.class).expression());
  }
}
