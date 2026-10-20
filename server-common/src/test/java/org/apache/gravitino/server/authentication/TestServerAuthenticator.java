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
package org.apache.gravitino.server.authentication;

import java.util.Collections;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestServerAuthenticator {

  @Test
  public void testInitializeHandsTheContextToEachAuthenticator() {
    Config config = new Config(false) {};
    config.set(
        Configs.AUTHENTICATORS, Collections.singletonList(RecordingAuthenticator.class.getName()));
    IdGenerator idGenerator = () -> 7L;
    AuthenticatorContext context = AuthenticatorContext.of(() -> idGenerator);

    ServerAuthenticator.getInstance().initialize(config, context);

    RecordingAuthenticator authenticator =
        (RecordingAuthenticator) ServerAuthenticator.getInstance().authenticators().get(0);
    Assertions.assertSame(config, authenticator.config);
    Assertions.assertSame(idGenerator, authenticator.context.idGenerator());
  }

  @Test
  public void testDefaultContextInitializeCallsTheConfigOnlyMethod() {
    ConfigOnlyAuthenticator authenticator = new ConfigOnlyAuthenticator();
    Config config = new Config(false) {};

    authenticator.initialize(
        config,
        AuthenticatorContext.of(
            () -> {
              throw new IllegalStateException("The context must not be read");
            }));

    Assertions.assertSame(config, authenticator.config);
  }

  @Test
  public void testContextReadsItsServicesLazily() {
    IdGenerator[] holder = new IdGenerator[1];
    AuthenticatorContext context = AuthenticatorContext.of(() -> holder[0]);
    holder[0] = () -> 1L;

    Assertions.assertSame(holder[0], context.idGenerator());
    Assertions.assertThrows(NullPointerException.class, () -> AuthenticatorContext.of(null));
  }

  /** Records the context-aware initialization. */
  public static class RecordingAuthenticator implements Authenticator {
    private Config config;
    private AuthenticatorContext context;

    @Override
    public void initialize(Config config) {
      throw new AssertionError("The server must call the context-aware initialize");
    }

    @Override
    public void initialize(Config config, AuthenticatorContext context) {
      this.config = config;
      this.context = context;
    }
  }

  private static class ConfigOnlyAuthenticator implements Authenticator {
    private Config config;

    @Override
    public void initialize(Config config) {
      this.config = config;
    }
  }
}
