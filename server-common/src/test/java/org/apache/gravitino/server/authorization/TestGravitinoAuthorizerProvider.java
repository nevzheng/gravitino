/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.authorization;

import java.io.IOException;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Test of {@link GravitinoAuthorizerProvider} */
public class TestGravitinoAuthorizerProvider {

  @AfterEach
  public void tearDown() throws IOException {
    GravitinoAuthorizerProvider.getInstance().close();
    RecordingAuthorizer.context = null;
  }

  @Test
  public void testInitializeWithContextPassesItToTheAuthorizer() {
    ServerConfig serverConfig = new ServerConfig();
    serverConfig.set(Configs.ENABLE_AUTHORIZATION, true);
    serverConfig.set(Configs.AUTHORIZATION_IMPL, RecordingAuthorizer.class.getName());
    AuthorizerContext context = Mockito.mock(AuthorizerContext.class);

    GravitinoAuthorizerProvider.getInstance().initialize(serverConfig, context);

    Assertions.assertInstanceOf(
        RecordingAuthorizer.class,
        GravitinoAuthorizerProvider.getInstance().getGravitinoAuthorizer());
    Assertions.assertSame(context, RecordingAuthorizer.context);
  }

  @Test
  public void testInitializeWithContextUsesThePassThroughAuthorizerWhenDisabled() {
    ServerConfig serverConfig = new ServerConfig();
    serverConfig.set(Configs.ENABLE_AUTHORIZATION, false);

    GravitinoAuthorizerProvider.getInstance()
        .initialize(serverConfig, Mockito.mock(AuthorizerContext.class));

    Assertions.assertInstanceOf(
        PassThroughAuthorizer.class,
        GravitinoAuthorizerProvider.getInstance().getGravitinoAuthorizer());
  }

  @Test
  public void testCloseTakesAnInstalledAuthorizerBackWithoutClosingIt() throws IOException {
    GravitinoAuthorizer authorizer = Mockito.mock(GravitinoAuthorizer.class);
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();

    provider.install(authorizer);
    // Installing the same authorizer again is allowed.
    provider.install(authorizer);
    Assertions.assertSame(authorizer, provider.getGravitinoAuthorizer());

    provider.close();
    Assertions.assertNull(provider.getGravitinoAuthorizer());
    Mockito.verify(authorizer, Mockito.never()).close();
  }

  @Test
  public void testInstallRejectsASecondAuthorizer() {
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();
    provider.install(Mockito.mock(GravitinoAuthorizer.class));

    Assertions.assertThrows(
        IllegalStateException.class,
        () -> provider.install(Mockito.mock(GravitinoAuthorizer.class)));
  }

  @Test
  public void testCloseClosesAnAuthorizerTheProviderBuilt() throws IOException {
    ServerConfig serverConfig = new ServerConfig();
    serverConfig.set(Configs.ENABLE_AUTHORIZATION, true);
    serverConfig.set(Configs.AUTHORIZATION_IMPL, ClosingAuthorizer.class.getName());
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();
    provider.initialize(serverConfig, Mockito.mock(AuthorizerContext.class));
    ClosingAuthorizer authorizer = (ClosingAuthorizer) provider.getGravitinoAuthorizer();

    provider.close();

    Assertions.assertTrue(authorizer.closed);
  }

  /** An authorizer that records that it was closed. */
  public static class ClosingAuthorizer extends PassThroughAuthorizer {
    boolean closed;

    @Override
    public void close() {
      closed = true;
    }
  }

  /** An authorizer that records the context it is initialized with. */
  public static class RecordingAuthorizer extends PassThroughAuthorizer {
    static AuthorizerContext context;

    @Override
    public void initialize(AuthorizerContext context) {
      RecordingAuthorizer.context = context;
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testInitializeAllowAuthorizer() {
    ServerConfig serverConfig = new ServerConfig();
    serverConfig.set(Configs.ENABLE_AUTHORIZATION, false);
    GravitinoAuthorizerProvider.getInstance().initialize(serverConfig);
    GravitinoAuthorizer gravitinoAuthorizer =
        GravitinoAuthorizerProvider.getInstance().getGravitinoAuthorizer();
    Assertions.assertInstanceOf(
        PassThroughAuthorizer.class,
        gravitinoAuthorizer,
        "Error initializing GravitinoAuthorizerProvider");
  }
}
