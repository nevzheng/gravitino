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

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.EnvironmentAuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.ServerConfig;

/**
 * Used to initialize and store {@link GravitinoAuthorizer}. When Gravitino Server starts up, it
 * initializes GravitinoAuthorizer by reading the ServerConfig through the init method and stores it
 * in the GravitinoAuthorizerProvider. The GravitinoAuthorizer instance can then be retrieved using
 * the getGravitinoAuthorizer method.
 */
public class GravitinoAuthorizerProvider implements Closeable {

  private static final GravitinoAuthorizerProvider INSTANCE = new GravitinoAuthorizerProvider();

  private GravitinoAuthorizerProvider() {}

  private volatile GravitinoAuthorizer gravitinoAuthorizer;

  // Whether the deprecated initialize(ServerConfig) handed the authorizer to GravitinoEnv.
  private volatile boolean handedToProcessEnv;

  /**
   * Instantiate the {@link GravitinoAuthorizer}, initialize it with the host services of {@code
   * GravitinoEnv}, and hand it to {@code GravitinoEnv}.
   *
   * @param serverConfig Gravitino server config
   * @deprecated Use {@link #initialize(ServerConfig, AuthorizerContext)} and hand the authorizer to
   *     the environment of the server.
   */
  @Deprecated
  public void initialize(ServerConfig serverConfig) {
    synchronized (this) {
      if (gravitinoAuthorizer == null) {
        initialize(serverConfig, EnvironmentAuthorizerContext.INSTANCE);
        processEnv().setGravitinoAuthorizer(gravitinoAuthorizer);
        handedToProcessEnv = true;
      }
    }
  }

  /**
   * Instantiates the {@link GravitinoAuthorizer} configured by {@code serverConfig}, or a {@link
   * PassThroughAuthorizer} when authorization is disabled, and initializes it with {@code context}.
   * Does nothing when the authorizer is already initialized.
   *
   * <p>Unlike {@link #initialize(ServerConfig)}, it does not hand the authorizer to {@code
   * GravitinoEnv}; the caller does that.
   *
   * @param serverConfig Gravitino server config
   * @param context the host services the authorizer may use
   */
  public void initialize(ServerConfig serverConfig, AuthorizerContext context) {
    Preconditions.checkNotNull(context, "context");
    if (gravitinoAuthorizer == null) {
      synchronized (this) {
        if (gravitinoAuthorizer == null) {
          gravitinoAuthorizer = newAuthorizer(serverConfig);
          gravitinoAuthorizer.initialize(context);
        }
      }
    }
  }

  public static GravitinoAuthorizerProvider getInstance() {
    return INSTANCE;
  }

  /**
   * Retrieve GravitinoAuthorizer instance.
   *
   * @return GravitinoAuthorizer instance
   */
  public GravitinoAuthorizer getGravitinoAuthorizer() {
    return gravitinoAuthorizer;
  }

  // Only the deprecated initialize(ServerConfig) hands the authorizer to the process environment.
  @LegacyEnvAccess
  private static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }

  private static GravitinoAuthorizer newAuthorizer(ServerConfig serverConfig) {
    if (!serverConfig.get(Configs.ENABLE_AUTHORIZATION)) {
      return new PassThroughAuthorizer();
    }
    String authorizationImpl = serverConfig.get(Configs.AUTHORIZATION_IMPL);
    try {
      return (GravitinoAuthorizer)
          Class.forName(authorizationImpl).getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot initialize GravitinoAuthorizer", e);
    }
  }

  /**
   * Closes the authorizer. An authorizer that {@link #initialize(ServerConfig)} handed to {@code
   * GravitinoEnv} is taken back from it first.
   */
  @Override
  public void close() throws IOException {
    if (handedToProcessEnv) {
      processEnv().setGravitinoAuthorizer(null);
      handedToProcessEnv = false;
    }
    if (gravitinoAuthorizer != null) {
      gravitinoAuthorizer.close();
    }
    gravitinoAuthorizer = null;
  }
}
