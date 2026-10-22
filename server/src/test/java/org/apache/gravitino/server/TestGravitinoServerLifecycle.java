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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.JettyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class TestGravitinoServerLifecycle {

  private GravitinoEnv env;
  private JettyServer jetty;
  private LineageService lineage;
  private GravitinoAuthorizerProvider authorizer;
  private GravitinoServer server;

  @BeforeEach
  public void setUp() {
    env = mock(GravitinoEnv.class);
    jetty = mock(JettyServer.class);
    lineage = mock(LineageService.class);
    authorizer = mock(GravitinoAuthorizerProvider.class);
    server =
        new GravitinoServer(
            mock(ServerConfig.class),
            env,
            DaggerGravitinoComponent.factory(),
            jetty,
            lineage,
            authorizer);
  }

  @Test
  public void testJettyStopsBeforeAuthorizerAndEnvironment() throws Exception {
    server.start();
    server.stop();

    InOrder order = inOrder(env, jetty, authorizer, lineage);
    order.verify(env).start();
    order.verify(jetty).start();
    order.verify(jetty).stop();
    order.verify(env).setGravitinoAuthorizer(null);
    order.verify(authorizer).close();
    order.verify(env).shutdown();
    order.verify(lineage).close();
  }

  @Test
  public void testJettyStartFailureShutsDownEnvironment() throws IOException {
    IllegalStateException failure = new IllegalStateException("port in use");
    doThrow(failure).when(jetty).start();

    assertSame(failure, assertThrows(IllegalStateException.class, server::start));

    InOrder order = inOrder(env, authorizer);
    order.verify(env).start();
    order.verify(authorizer).close();
    order.verify(env).shutdown();
    verify(jetty, never()).stop();
  }

  @Test
  public void testStopAfterEnvironmentStartFailureReleasesEverything() throws Exception {
    IllegalStateException failure = new IllegalStateException("auxiliary service failed");
    doThrow(failure).when(env).start();
    assertSame(failure, assertThrows(IllegalStateException.class, server::start));

    server.stop();

    // Nothing had started, so the rollback stopped nothing; stop() still releases every service.
    InOrder order = inOrder(jetty, env, authorizer, lineage);
    order.verify(jetty).stop();
    order.verify(env).setGravitinoAuthorizer(null);
    order.verify(authorizer).close();
    order.verify(env).shutdown();
    order.verify(lineage).close();
    verify(authorizer, times(1)).close();
    verify(env, times(1)).shutdown();
  }

  @Test
  public void testStopAfterJettyStartFailureStopsOnlyWhatTheRollbackDidNot() throws Exception {
    doThrow(new IllegalStateException("port in use")).when(jetty).start();
    assertThrows(IllegalStateException.class, server::start);

    server.stop();
    server.stop();

    verify(jetty, times(1)).stop();
    verify(authorizer, times(1)).close();
    verify(env, times(1)).shutdown();
  }

  @Test
  public void testGracefulStopRunsOnce() throws Exception {
    server.start();

    server.gracefulStop();
    server.gracefulStop();

    verify(jetty, times(1)).stop();
    verify(authorizer, times(1)).close();
    verify(env, times(1)).shutdown();
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
    verify(env).shutdown();
    verify(lineage).close();
  }

  @Test
  public void testStopWithoutStartReleasesResources() throws Exception {
    server.stop();

    InOrder order = inOrder(jetty, authorizer, env, lineage);
    order.verify(jetty).stop();
    order.verify(authorizer).close();
    order.verify(env).shutdown();
    order.verify(lineage).close();
  }
}
