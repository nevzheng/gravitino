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
package org.apache.gravitino.di;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.catalog.CatalogAccess;
import org.junit.jupiter.api.Test;

public class TestComponentAuthorizerContext {

  @Test
  public void testResolvesEachServiceWhenAsked() {
    Config config = new Config(false) {};
    EntityStore entityStore = mock(EntityStore.class);
    CatalogAccess catalogAccess = mock(CatalogAccess.class);
    AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);

    AuthorizerContext context =
        new ComponentAuthorizerContext(
            config,
            () -> entityStore,
            () -> catalogAccess,
            /* authorizationEnabled= */ true,
            () -> accessControl);

    assertSame(config, context.config());
    assertSame(entityStore, context.entityStore());
    assertSame(catalogAccess, context.catalogAccess());
    assertSame(accessControl, context.internalAccessControlDispatcher());
  }

  @Test
  public void testHasNoAccessControlWhenAuthorizationIsDisabled() {
    AuthorizerContext context =
        new ComponentAuthorizerContext(
            new Config(false) {},
            () -> mock(EntityStore.class),
            () -> mock(CatalogAccess.class),
            /* authorizationEnabled= */ false,
            () -> {
              throw new AssertionError("must not be built while authorization is disabled");
            });

    assertNull(context.internalAccessControlDispatcher());
  }

  @Test
  public void testComponentBuildsOneContext() throws Exception {
    Config config = TestGravitinoComponent.testConfig();
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);

    AuthorizerContext context = component.authorizerContext();

    assertSame(context, component.authorizerContext());
    assertSame(config, context.config());
    assertNull(context.internalAccessControlDispatcher());
    component.managedServices().close();
  }
}
