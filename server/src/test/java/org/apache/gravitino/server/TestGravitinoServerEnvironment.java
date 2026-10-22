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

import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.HttpServerMetricsSource;
import org.apache.gravitino.server.web.JettyServer;
import org.junit.jupiter.api.Test;

/** Checks where the server takes its component and the REST dispatchers from. */
public class TestGravitinoServerEnvironment {

  @Test
  public void testBindsFromTheComponentTheFactoryCreates() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoComponent component = component(config);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(component.metricsSystem()).thenReturn(metricsSystem);
    GravitinoComponent.Factory factory = factory(component);
    GravitinoServer server = server(config, factory);
    try {
      assertThrows(IllegalStateException.class, server::component);

      server.initialize();

      verify(factory).create(config);
      assertSame(component, server.component());
      assertTrue(hasInstance(server, GravitinoComponentBinder.class));
      verify(metricsSystem).register(any(HttpServerMetricsSource.class));
    } finally {
      server.stop();
    }
  }

  @Test
  public void testInitializeBuildsTheFullProfile() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoComponent component = component(config);
    GravitinoServer server = server(config, factory(component));
    try {
      server.initialize();

      // The full profile builds the public chains, the auxiliary services and the job chains.
      verify(component).catalogDispatcher();
      verify(component).auxiliaryServiceManager();
      verify(component).jobOperationDispatcher();
    } finally {
      server.stop();
    }
  }

  @Test
  public void testTheConstructorNeedsAFactory() {
    assertThrows(
        NullPointerException.class,
        () -> new GravitinoServer(new ServerConfig(), (GravitinoComponent.Factory) null));
  }

  @Test
  public void testInstallsTheAuthorizerOfTheComponentInTheProvider() throws Exception {
    ServerConfig config = new ServerConfig();
    GravitinoComponent component = component(config);
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(component.gravitinoAuthorizer()).thenReturn(authorizer);
    GravitinoAuthorizerProvider provider = mock(GravitinoAuthorizerProvider.class);
    GravitinoServer server =
        new GravitinoServer(
            config,
            factory(component),
            mock(JettyServer.class),
            mock(LineageService.class),
            provider);
    try {
      server.initialize();

      verify(provider).install(authorizer);
      verify(provider, never()).initialize(any(ServerConfig.class), any());
    } finally {
      server.stop();
    }
    // The provider takes the authorizer back; the component's lifecycle closes it.
    verify(provider).close();
    verify(authorizer, never()).close();
  }

  private static GravitinoComponent component(ServerConfig config) {
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    when(component.config()).thenReturn(config);
    when(component.metricsSystem()).thenReturn(mock(MetricsSystem.class));
    when(component.eventBus()).thenReturn(mock(EventBus.class));
    return component;
  }

  private static GravitinoComponent.Factory factory(GravitinoComponent component) {
    GravitinoComponent.Factory factory = mock(GravitinoComponent.Factory.class);
    when(factory.create(any())).thenReturn(component);
    return factory;
  }

  private static GravitinoServer server(ServerConfig config, GravitinoComponent.Factory factory) {
    return new GravitinoServer(
        config,
        factory,
        mock(JettyServer.class),
        mock(LineageService.class),
        mock(GravitinoAuthorizerProvider.class));
  }

  private static boolean hasInstance(GravitinoServer server, Class<?> type) {
    return server.getInstances().stream().anyMatch(type::isInstance);
  }
}
