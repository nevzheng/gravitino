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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.security.Principal;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.di.GravitinoProfiles;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;

/**
 * Checks that a {@link MetadataAccessChecker} built over the authorizer of the component, as the
 * server binders build it, decides with the authorizer the component builds from the configuration.
 */
public class TestMetadataAccessCheckerWithComponentAuthorizer {

  private static final String EXPRESSION = "CATALOG::USE_CATALOG";

  @Test
  public void testCheckerUsesTheAuthorizerTheComponentBuilds() throws Exception {
    Config config = new Config(false) {};
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    config.set(Configs.CACHE_ENABLED, false);
    config.set(Configs.AUTHORIZATION_IMPL, DenyingAuthorizer.class.getName());
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);
    try {
      GravitinoProfiles.base(component);
      MetadataAccessChecker checker =
          new MetadataAccessChecker(
              config,
              component::gravitinoAuthorizer,
              () -> mock(EntityStore.class),
              () -> mock(SchemaDispatcher.class));
      NameIdentifier[] schemas = {
        NameIdentifierUtil.ofSchema("metalake", "catalog", "schema1"),
        NameIdentifierUtil.ofSchema("metalake", "catalog", "schema2")
      };

      assertDenied(checker, schemas);
      assertInstanceOf(DenyingAuthorizer.class, component.gravitinoAuthorizer());
    } finally {
      component.managedServices().close();
    }
  }

  @Test
  public void testCheckerAllowsEverythingWhenAuthorizationIsDisabled() throws Exception {
    Config config = new Config(false) {};
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);
    try {
      GravitinoProfiles.base(component);
      MetadataAccessChecker checker =
          new MetadataAccessChecker(
              config,
              component::gravitinoAuthorizer,
              () -> mock(EntityStore.class),
              () -> mock(SchemaDispatcher.class));
      NameIdentifier[] schemas = {NameIdentifierUtil.ofSchema("metalake", "catalog", "schema")};

      assertTrue(checker.checkAccess(schemas[0], Entity.EntityType.SCHEMA, EXPRESSION));
      assertSame(
          schemas,
          checker.filterByExpression("metalake", EXPRESSION, Entity.EntityType.SCHEMA, schemas));
    } finally {
      component.managedServices().close();
    }
  }

  /** An authorizer that allows nothing. */
  public static class DenyingAuthorizer extends PassThroughAuthorizer {
    @Override
    public boolean authorize(
        Principal principal,
        String metalake,
        MetadataObject metadataObject,
        Privilege.Name privilege,
        AuthorizationRequestContext requestContext) {
      return false;
    }

    @Override
    public boolean isOwner(
        Principal principal,
        String metalake,
        MetadataObject metadataObject,
        AuthorizationRequestContext requestContext) {
      return false;
    }

    @Override
    public boolean isServiceAdmin() {
      return false;
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
