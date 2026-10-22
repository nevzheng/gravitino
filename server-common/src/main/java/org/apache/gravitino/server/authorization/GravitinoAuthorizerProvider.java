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
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.EnvironmentAuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.ServerConfig;

/**
 * Holds the {@link GravitinoAuthorizer} of the server for the code that reads it statically. When
 * the Gravitino server starts up, it {@link #install installs} the authorizer its component built;
 * the deprecated {@link #initialize(ServerConfig)} and {@link #initialize(ServerConfig,
 * AuthorizerContext)} still build one here. The GravitinoAuthorizer instance can then be retrieved
 * using the getGravitinoAuthorizer method.
 */
public class GravitinoAuthorizerProvider implements Closeable {

  private static final GravitinoAuthorizerProvider INSTANCE = new GravitinoAuthorizerProvider();

  private GravitinoAuthorizerProvider() {}

  private volatile GravitinoAuthorizer gravitinoAuthorizer;

  // Whether this provider built the authorizer and closes it; an installed one is not closed here.
  private volatile boolean owned;

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
        EnvironmentAuthorizerContext.installInProcessEnvironment(gravitinoAuthorizer);
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
          GravitinoAuthorizer authorizer = newAuthorizer(serverConfig);
          authorizer.initialize(context);
          owned = true;
          gravitinoAuthorizer = authorizer;
        }
      }
    }
  }

  /**
   * Installs an authorizer that something else built and closes, such as the component of the
   * server. {@link #close()} takes it back without closing it.
   *
   * @param authorizer the authorizer
   * @throws IllegalStateException if another authorizer is installed or initialized
   */
  public synchronized void install(GravitinoAuthorizer authorizer) {
    Preconditions.checkNotNull(authorizer, "authorizer");
    Preconditions.checkState(
        gravitinoAuthorizer == null || gravitinoAuthorizer == authorizer,
        "Another authorizer is already installed");
    gravitinoAuthorizer = authorizer;
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
   * Takes the authorizer back, and closes it if this provider built it. An authorizer that {@link
   * #initialize(ServerConfig)} handed to {@code GravitinoEnv} is taken back from it first; an
   * installed one is left to its owner to close.
   */
  @Override
  public synchronized void close() throws IOException {
    if (handedToProcessEnv) {
      EnvironmentAuthorizerContext.installInProcessEnvironment(null);
      handedToProcessEnv = false;
    }
    GravitinoAuthorizer authorizer = gravitinoAuthorizer;
    boolean close = owned;
    gravitinoAuthorizer = null;
    owned = false;
    if (close && authorizer != null) {
      authorizer.close();
    }
  }
}
