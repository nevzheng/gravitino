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
package org.apache.gravitino.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEnvironmentAuthorizerContext {

  @Test
  public void testReadsTheGettersOfTheEnvironment() {
    GravitinoEnv env = mock(GravitinoEnv.class);
    Config config = mock(Config.class);
    EntityStore entityStore = mock(EntityStore.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);
    when(env.config()).thenReturn(config);
    when(env.entityStore()).thenReturn(entityStore);
    when(env.catalogManager()).thenReturn(catalogManager);
    when(env.internalAccessControlDispatcher()).thenReturn(accessControl);

    AuthorizerContext context = EnvironmentAuthorizerContext.of(env);

    Assertions.assertSame(config, context.config());
    Assertions.assertSame(entityStore, context.entityStore());
    Assertions.assertSame(catalogManager, context.catalogManager());
    Assertions.assertSame(accessControl, context.internalAccessControlDispatcher());
  }

  @Test
  public void testReadsEachServiceWhenAskedFor() {
    GravitinoEnv env = mock(GravitinoEnv.class);
    AuthorizerContext context = EnvironmentAuthorizerContext.of(env);
    Assertions.assertNull(context.internalAccessControlDispatcher());

    AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);
    when(env.internalAccessControlDispatcher()).thenReturn(accessControl);

    Assertions.assertSame(accessControl, context.internalAccessControlDispatcher());
  }

  @Test
  public void testDefaultInitializeWithContextCallsInitialize() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    doCallRealMethod().when(authorizer).initialize(any(AuthorizerContext.class));

    authorizer.initialize(mock(AuthorizerContext.class));

    verify(authorizer).initialize();
  }
}
