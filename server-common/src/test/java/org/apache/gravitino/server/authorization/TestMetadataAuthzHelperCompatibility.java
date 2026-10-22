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
package org.apache.gravitino.server.authorization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** The static helpers answer through the process environment until GravitinoEnv goes. */
public class TestMetadataAuthzHelperCompatibility {

  @Test
  public void testStaticFacadeReadsTheEnvironmentOnEveryCall() {
    NameIdentifier[] tables = {NameIdentifier.of("metalake", "catalog", "schema", "table")};
    GravitinoEnv env = mock(GravitinoEnv.class);
    try (MockedStatic<GravitinoEnv> envStatic = Mockito.mockStatic(GravitinoEnv.class)) {
      envStatic.when(GravitinoEnv::getInstance).thenReturn(env);
      // No configuration yet: authorization is off and every entry passes.
      Assertions.assertArrayEquals(
          tables,
          MetadataAuthzHelper.filterByExpression(
              "metalake",
              AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.TABLE,
              tables));

      Config config = mock(Config.class);
      when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);
      when(env.config()).thenReturn(config);
      Assertions.assertTrue(
          MetadataAuthzHelper.checkAccess(
              tables[0],
              Entity.EntityType.TABLE,
              AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION));
    }
  }

  @Test
  public void testIdConverterKeepsTheResolverScopes() {
    Assertions.assertEquals(
        MetadataIdResolver.catalogScopedTypes(), MetadataIdConverter.catalogScopedTypes());
    MetadataObject metalake = MetadataObjects.of(null, "metalake", MetadataObject.Type.METALAKE);
    GravitinoEnv env = mock(GravitinoEnv.class);
    try (MockedStatic<GravitinoEnv> envStatic = Mockito.mockStatic(GravitinoEnv.class)) {
      envStatic.when(GravitinoEnv::getInstance).thenReturn(env);
      Assertions.assertEquals(
          metalake, MetadataIdConverter.normalizeMetadataObject(metalake, "metalake"));
    }
  }
}
