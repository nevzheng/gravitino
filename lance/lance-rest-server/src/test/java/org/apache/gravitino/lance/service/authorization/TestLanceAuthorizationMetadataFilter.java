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
package org.apache.gravitino.lance.service.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.server.authorization.MetadataAccessChecker;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestLanceAuthorizationMetadataFilter {

  @Test
  public void testFiltersThroughTheGivenChecker() {
    MetadataAccessChecker checker = mock(MetadataAccessChecker.class);
    when(checker.filterByExpression(
            eq("metalake"),
            eq(AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION),
            eq(Entity.EntityType.TABLE),
            any(NameIdentifier[].class)))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifierUtil.ofTable("metalake", "catalog", "schema", "a")
            });

    LanceAuthorizationMetadataFilter filter =
        new LanceAuthorizationMetadataFilter("metalake", checker);

    Assertions.assertEquals(
        Arrays.asList("a"), filter.filterTables("catalog", "schema", Arrays.asList("a", "b")));
  }
}
