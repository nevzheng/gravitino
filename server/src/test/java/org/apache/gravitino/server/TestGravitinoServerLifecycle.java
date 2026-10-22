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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lifecycle.Lifecycle;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.JettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class TestGravitinoServerLifecycle {

  // The start and stop of the services the component's lifecycle runs.
  private Lifecycle.Action componentStart;
  private Lifecycle.Action componentStop;
  private JettyServer jetty;
  private LineageService lineage;
  private GravitinoAuthorizerProvider authorizer;
  private GravitinoServer server;

  @BeforeEach
  public void setUp() {
    componentStart = mock(Lifecycle.Action.class);
    componentStop = mock(Lifecycle.Action.class);
    jetty = mock(JettyServer.class);
    lineage = mock(LineageService.class);
    authorizer = mock(GravitinoAuthorizerProvider.class);

    ServerConfig config = new ServerConfig();
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    when(component.config()).thenReturn(config);
    when(component.metricsSystem()).thenReturn(mock(MetricsSystem.class));
    when(component.eventBus()).thenReturn(mock(EventBus.class));
    when(component.lifecycle())
        .thenReturn(
            Lifecycle.builder()
                .alwaysStop()
                .add("component services", componentStart, componentStop)
                .build());
    GravitinoComponent.Factory factory = mock(GravitinoComponent.Factory.class);
    when(factory.create(any())).thenReturn(component);

    server = new GravitinoServer(config, factory, jetty, lineage, authorizer);
    server.initialize();
  }

  @AfterEach
  public void tearDown() throws IOException {
    server.stop();
  }

  @Test
  public void testJettyStopsBeforeAuthorizerAndComponent() throws Exception {
    server.start();
    server.stop();

    InOrder order = inOrder(componentStart, componentStop, jetty, authorizer, lineage);
    order.verify(componentStart).run();
    order.verify(jetty).start();
    order.verify(jetty).stop();
    order.verify(authorizer).close();
    order.verify(componentStop).run();
    order.verify(lineage).close();
  }

  @Test
  public void testStartNeedsAnInitializedServer() throws Exception {
    GravitinoServer uninitialized =
        new GravitinoServer(
            new ServerConfig(),
            mock(GravitinoComponent.Factory.class),
            mock(JettyServer.class),
            mock(LineageService.class),
            mock(GravitinoAuthorizerProvider.class));

    IllegalStateException failure = assertThrows(IllegalStateException.class, uninitialized::start);

    assertEquals(
        "The server has no component: call initialize() before start()", failure.getMessage());
    uninitialized.stop();
  }

  @Test
  public void testJettyStartFailureStopsTheComponent() throws Exception {
    IllegalStateException failure = new IllegalStateException("port in use");
    doThrow(failure).when(jetty).start();

    assertSame(failure, assertThrows(IllegalStateException.class, server::start));

    InOrder order = inOrder(componentStart, componentStop, authorizer);
    order.verify(componentStart).run();
    order.verify(authorizer).close();
    order.verify(componentStop).run();
    verify(jetty, never()).stop();
  }

  @Test
  public void testStopAfterComponentStartFailureReleasesEverything() throws Exception {
    IllegalStateException failure = new IllegalStateException("auxiliary service failed");
    doThrow(failure).when(componentStart).run();
    assertSame(failure, assertThrows(IllegalStateException.class, server::start));

    server.stop();

    // The component's lifecycle stopped its services when they failed to start; stop() still
    // releases every other service, and stops none twice.
    InOrder order = inOrder(componentStop, jetty, authorizer, lineage);
    order.verify(componentStop).run();
    order.verify(jetty).stop();
    order.verify(authorizer).close();
    order.verify(lineage).close();
    verify(authorizer, times(1)).close();
    verify(componentStop, times(1)).run();
  }

  @Test
  public void testStopAfterJettyStartFailureStopsOnlyWhatTheRollbackDidNot() throws Exception {
    doThrow(new IllegalStateException("port in use")).when(jetty).start();
    assertThrows(IllegalStateException.class, server::start);

    server.stop();
    server.stop();

    verify(jetty, times(1)).stop();
    verify(authorizer, times(1)).close();
    verify(componentStop, times(1)).run();
  }

  @Test
  public void testGracefulStopRunsOnce() throws Exception {
    server.start();

    server.gracefulStop();
    server.gracefulStop();

    verify(jetty, times(1)).stop();
    verify(authorizer, times(1)).close();
    verify(componentStop, times(1)).run();
    verify(lineage, times(1)).close();
  }

  @Test
  public void testStopKeepsGoingWhenAServiceFails() throws Exception {
    server.start();
    IllegalStateException jettyFailure = new IllegalStateException("jetty");
    doThrow(jettyFailure).when(jetty).stop();

    IOException thrown = assertThrows(IOException.class, server::stop);

    assertSame(jettyFailure, thrown.getCause().getCause());
    verify(authorizer).close();
    verify(componentStop).run();
    verify(lineage).close();
  }

  @Test
  public void testComponentStopFailureIsTheOneTheComponentThrew() throws Exception {
    server.start();
    IllegalStateException stopFailure = new IllegalStateException("store");
    doThrow(stopFailure).when(componentStop).run();

    IOException thrown = assertThrows(IOException.class, server::stop);

    // The server reports the component's own failure, not the lifecycle's wrapper around it.
    assertSame(stopFailure, thrown.getCause().getCause());
  }

  @Test
  public void testStopWithoutStartReleasesResources() throws Exception {
    server.stop();

    InOrder order = inOrder(jetty, authorizer, componentStop, lineage);
    order.verify(jetty).stop();
    order.verify(authorizer).close();
    order.verify(componentStop).run();
    order.verify(lineage).close();
  }
}
