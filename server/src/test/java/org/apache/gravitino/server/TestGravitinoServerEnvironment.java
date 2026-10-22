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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.apache.gravitino.authorization.GravitinoAuthorizer;
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
      assertFalse(hasInstance(server, GravitinoEnvBinder.class));
      verify(metricsSystem).register(any(HttpServerMetricsSource.class));
      verify(env, never()).metricsSystem();
      verify(env, never()).eventBus();
    } finally {
      server.stop();
    }
  }

  @Test
  public void testInstallsTheAuthorizerOfTheComponentInTheProvider() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(component.gravitinoAuthorizer()).thenReturn(authorizer);
    when(component.metricsSystem()).thenReturn(mock(MetricsSystem.class));
    when(component.eventBus()).thenReturn(mock(EventBus.class));
    when(env.component()).thenReturn(component);
    GravitinoAuthorizerProvider provider = mock(GravitinoAuthorizerProvider.class);
    GravitinoServer server =
        new GravitinoServer(
            config,
            env,
            mock(GravitinoComponent.Factory.class),
            mock(JettyServer.class),
            mock(LineageService.class),
            provider);
    try {
      server.initialize();

      verify(provider).install(authorizer);
      verify(provider, never()).initialize(any(ServerConfig.class), any());
      verify(env, never()).setGravitinoAuthorizer(any());
    } finally {
      server.stop();
    }
    // The provider takes the authorizer back; the component's lifecycle closes it.
    verify(provider).close();
    verify(authorizer, never()).close();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testTheDeprecatedConstructorBindsFromTheEnvironmentGetters() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(env.metricsSystem()).thenReturn(metricsSystem);
    // What the public deprecated constructor passes: no component factory.
    GravitinoServer server = server(config, env, null);
    try {
      server.initialize();

      // The one-argument method, which a GravitinoEnv subclass may override.
      verify(env).initializeFullComponents(config);
      verify(env, never()).initializeFullComponents(any(), any());
      assertTrue(hasInstance(server, GravitinoEnvBinder.class));
      assertFalse(hasInstance(server, GravitinoComponentBinder.class));
      assertThrows(IllegalStateException.class, server::component);
      verify(metricsSystem).register(any(HttpServerMetricsSource.class));
    } finally {
      server.stop();
    }
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
