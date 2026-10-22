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
package org.apache.gravitino.server;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.HttpServerMetricsSource;
import org.apache.gravitino.server.web.JettyServer;
import org.junit.jupiter.api.Test;

/** Checks where the server takes the environment's component and the REST dispatchers from. */
public class TestGravitinoServerEnvironment {

  @Test
  public void testBindsFromTheComponentTheFactoryCreates() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(component.metricsSystem()).thenReturn(metricsSystem);
    when(component.eventBus()).thenReturn(mock(EventBus.class));
    when(env.component()).thenReturn(component);
    GravitinoComponent.Factory factory = mock(GravitinoComponent.Factory.class);
    GravitinoServer server = server(config, env, factory);
    try {
      assertThrows(IllegalStateException.class, server::component);

      server.initialize();

      verify(env).initializeFullComponents(config, factory);
      verify(env, never()).initializeFullComponents(any(Config.class));
      assertSame(component, server.component());
      assertTrue(hasInstance(server, GravitinoComponentBinder.class));
      verify(metricsSystem).register(any(HttpServerMetricsSource.class));
      verify(env, never()).metricsSystem();
      verify(env, never()).eventBus();
    } finally {
      server.stop();
    }
  }

  @Test
  public void testFailsWhenTheEnvironmentHoldsNoComponent() {
    GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
    when(env.component()).thenReturn(null);
    GravitinoServer server =
        server(new ServerConfig(), env, mock(GravitinoComponent.Factory.class));

    NullPointerException e = assertThrows(NullPointerException.class, server::initialize);
    assertTrue(e.getMessage().contains("must call the overridden method"), e.getMessage());
  }

  private static GravitinoServer server(
      ServerConfig config, GravitinoEnv env, GravitinoComponent.Factory factory) {
    return new GravitinoServer(
        config,
        env,
        factory,
        mock(JettyServer.class),
        mock(LineageService.class),
        mock(GravitinoAuthorizerProvider.class));
  }

  private static boolean hasInstance(GravitinoServer server, Class<?> type) {
    return server.getInstances().stream().anyMatch(type::isInstance);
  }
}
