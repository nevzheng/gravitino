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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lifecycle.ManagedServices;
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
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(component.metricsSystem()).thenReturn(metricsSystem);
    when(component.eventBus()).thenReturn(mock(EventBus.class));
    when(component.config()).thenReturn(config);
    ManagedServices services = mock(ManagedServices.class);
    when(component.managedServices()).thenReturn(services);
    GravitinoComponent.Factory factory = mock(GravitinoComponent.Factory.class);
    when(factory.create(config)).thenReturn(component);
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
    // Stopping a server that never started still closes the services the component built.
    verify(services).close();
  }

  @Test
  public void testFailsWhenTheFactoryCreatesNoComponent() {
    GravitinoServer server = server(new ServerConfig(), mock(GravitinoComponent.Factory.class));

    NullPointerException e = assertThrows(NullPointerException.class, server::initialize);
    assertTrue(e.getMessage().contains("created no component"), e.getMessage());
  }

  @Test
  public void testRejectsAMissingComponentFactory() {
    assertThrows(
        NullPointerException.class,
        () -> new GravitinoServer(new ServerConfig(), (GravitinoComponent.Factory) null));
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
