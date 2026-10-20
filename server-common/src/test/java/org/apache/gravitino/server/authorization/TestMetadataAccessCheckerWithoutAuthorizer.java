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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Checks that a {@link MetadataAccessChecker} built over the authorizer of the component, as the
 * server binders build it, fails closed while authorization is enabled and no authorizer is
 * installed, for example while the server shuts down.
 */
public class TestMetadataAccessCheckerWithoutAuthorizer {

  private static final String EXPRESSION = "CATALOG::USE_CATALOG";

  @Test
  public void testCheckerDeniesWhileNoAuthorizerIsInstalled() {
    GravitinoEnv env = new GravitinoEnv() {};
    Config config = new Config(false) {};
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    config.set(Configs.CACHE_ENABLED, false);
    try {
      env.initializeBaseComponents(config);
      GravitinoComponent component = env.component();
      MetadataAccessChecker checker =
          new MetadataAccessChecker(
              config,
              component.authorizationServices()::gravitinoAuthorizer,
              () -> mock(EntityStore.class),
              () -> mock(SchemaDispatcher.class));
      NameIdentifier[] schemas = {
        NameIdentifierUtil.ofSchema("metalake", "catalog", "schema1"),
        NameIdentifierUtil.ofSchema("metalake", "catalog", "schema2")
      };

      // Nothing is installed yet.
      assertDenied(checker, schemas);

      // The server installs its authorizer, and the checker reads it on the next check.
      GravitinoAuthorizer installed = mock(GravitinoAuthorizer.class);
      when(installed.authorize(
              ArgumentMatchers.<Principal>any(),
              ArgumentMatchers.anyString(),
              ArgumentMatchers.<MetadataObject>any(),
              ArgumentMatchers.<Privilege.Name>any(),
              ArgumentMatchers.<AuthorizationRequestContext>any()))
          .thenReturn(true);
      env.setGravitinoAuthorizer(installed);
      assertTrue(checker.checkAccess(schemas[0], Entity.EntityType.SCHEMA, EXPRESSION));
      assertArrayEquals(
          schemas,
          checker.filterByExpression("metalake", EXPRESSION, Entity.EntityType.SCHEMA, schemas));

      // The server takes it back while it shuts down; a request still running is denied.
      env.setGravitinoAuthorizer(null);
      assertDenied(checker, schemas);
    } finally {
      env.shutdown();
    }
  }

  @Test
  public void testCheckerAllowsEverythingWhenAuthorizationIsDisabled() {
    GravitinoEnv env = new GravitinoEnv() {};
    Config config = new Config(false) {};
    try {
      env.initializeBaseComponents(config);
      MetadataAccessChecker checker =
          new MetadataAccessChecker(
              config,
              env.component().authorizationServices()::gravitinoAuthorizer,
              () -> mock(EntityStore.class),
              () -> mock(SchemaDispatcher.class));
      NameIdentifier[] schemas = {NameIdentifierUtil.ofSchema("metalake", "catalog", "schema")};

      assertTrue(checker.checkAccess(schemas[0], Entity.EntityType.SCHEMA, EXPRESSION));
      assertSame(
          schemas,
          checker.filterByExpression("metalake", EXPRESSION, Entity.EntityType.SCHEMA, schemas));
    } finally {
      env.shutdown();
    }
  }

  private static void assertDenied(MetadataAccessChecker checker, NameIdentifier[] schemas) {
    assertFalse(checker.checkAccess(schemas[0], Entity.EntityType.SCHEMA, EXPRESSION));
    assertEquals(
        0,
        checker.filterByExpression("metalake", EXPRESSION, Entity.EntityType.SCHEMA, schemas)
            .length);
  }
}
