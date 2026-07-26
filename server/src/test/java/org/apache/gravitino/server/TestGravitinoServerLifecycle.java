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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.JettyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TestGravitinoServerLifecycle {

  private GravitinoEnv environment;
  private JettyServer jettyServer;
  private LineageService lineageService;
  private GravitinoAuthorizerProvider authorizerProvider;
  private GravitinoServer gravitinoServer;

  @BeforeEach
  void setUp() {
    environment = mock(GravitinoEnv.class);
    jettyServer = mock(JettyServer.class);
    lineageService = mock(LineageService.class);
    authorizerProvider = mock(GravitinoAuthorizerProvider.class);
    gravitinoServer =
        new GravitinoServer(
            mock(ServerConfig.class), environment, jettyServer, lineageService, authorizerProvider);
  }

  @Test
  void testStartsEnvironmentBeforeJettyAndStopsInExistingOrder() throws Exception {
    gravitinoServer.start();
    gravitinoServer.stop();

    InOrder order = inOrder(environment, jettyServer, authorizerProvider, lineageService);
    order.verify(environment).start();
    order.verify(jettyServer).start();
    order.verify(authorizerProvider).close();
    order.verify(jettyServer).stop();
    order.verify(environment).shutdown();
    order.verify(lineageService).close();
  }

  @Test
  void testRollsBackEnvironmentWhenJettyStartupFails() {
    IllegalStateException startFailure = new IllegalStateException("Jetty start failed");
    doThrow(startFailure).when(jettyServer).start();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, gravitinoServer::start);

    assertSame(startFailure, thrown);
    InOrder order = inOrder(environment, jettyServer);
    order.verify(environment).start();
    order.verify(jettyServer).start();
    order.verify(jettyServer).stop();
    order.verify(environment).shutdown();
  }

  @Test
  void testGracefulStopIsIdempotent() throws Exception {
    gravitinoServer.start();

    gravitinoServer.gracefulStop();
    gravitinoServer.gracefulStop();

    verify(authorizerProvider, times(1)).close();
    verify(jettyServer, times(1)).stop();
    verify(environment, times(1)).shutdown();
    verify(lineageService, times(1)).close();
  }

  @Test
  void testStopContinuesCleanupAndAggregatesFailures() throws Exception {
    gravitinoServer.start();
    IOException authorizerFailure = new IOException("Authorizer stop failed");
    IllegalStateException jettyFailure = new IllegalStateException("Jetty stop failed");
    IllegalArgumentException environmentFailure =
        new IllegalArgumentException("Environment stop failed");
    IllegalStateException lineageFailure = new IllegalStateException("Lineage stop failed");
    doThrow(authorizerFailure).when(authorizerProvider).close();
    doThrow(jettyFailure).when(jettyServer).stop();
    doThrow(environmentFailure).when(environment).shutdown();
    doThrow(lineageFailure).when(lineageService).close();

    IOException thrown = assertThrows(IOException.class, gravitinoServer::stop);

    assertSame(authorizerFailure, thrown);
    assertEquals(2, thrown.getSuppressed().length);
    Exception lifecycleFailure = (Exception) thrown.getSuppressed()[0];
    assertEquals("Failed to stop lifecycle service: Jetty server", lifecycleFailure.getMessage());
    assertSame(jettyFailure, lifecycleFailure.getCause());
    assertEquals(1, lifecycleFailure.getSuppressed().length);
    assertEquals(
        "Failed to stop lifecycle service: Gravitino environment",
        lifecycleFailure.getSuppressed()[0].getMessage());
    assertSame(environmentFailure, lifecycleFailure.getSuppressed()[0].getCause());
    assertSame(lineageFailure, thrown.getSuppressed()[1]);

    InOrder order = inOrder(authorizerProvider, jettyServer, environment, lineageService);
    order.verify(authorizerProvider).close();
    order.verify(jettyServer).stop();
    order.verify(environment).shutdown();
    order.verify(lineageService).close();
  }

  @Test
  void testStopBeforeStartPreservesCleanupOrder() throws Exception {
    gravitinoServer.stop();

    InOrder order = inOrder(authorizerProvider, jettyServer, environment, lineageService);
    order.verify(authorizerProvider).close();
    order.verify(jettyServer).stop();
    order.verify(environment).shutdown();
    order.verify(lineageService).close();
  }
}
