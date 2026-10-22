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
package org.apache.gravitino.di;

import com.google.common.annotations.VisibleForTesting;
import dagger.Module;
import dagger.Provides;
import java.io.IOException;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds the {@link GravitinoAuthorizer} of the component.
 *
 * <p>When authorization is enabled, the authorizer is the class {@link Configs#AUTHORIZATION_IMPL}
 * names, loaded by reflection, so core does not depend on the server module that holds the built-in
 * implementation. When it is disabled, the authorizer is {@link NoopGravitinoAuthorizer#INSTANCE},
 * which allows everything, as the server's pass-through authorizer did. The authorizer is built and
 * initialized with a {@link ComponentAuthorizerContext} the first time it is requested; the server
 * requests it once the component is built and before it serves requests. The {@link LifecycleModule
 * lifecycle} closes it, first among the services it stops.
 *
 * <p>It is a module of its own, not part of {@link AuthorizationModule}, so a test component can
 * replace it.
 */
// The lifecycle closes the authorizer.
@SuppressWarnings("CloseableProvides")
@Module
abstract class AuthorizerModule {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizerModule.class);

  private AuthorizerModule() {}

  @Provides
  @Singleton
  static GravitinoAuthorizer gravitinoAuthorizer(
      @AuthorizationEnabled boolean authorizationEnabled,
      Config config,
      ComponentAuthorizerContext context,
      BuiltServices built) {
    GravitinoAuthorizer authorizer =
        authorizationEnabled
            ? newAuthorizer(config.get(Configs.AUTHORIZATION_IMPL))
            : NoopGravitinoAuthorizer.INSTANCE;
    try {
      authorizer.initialize(context);
    } catch (RuntimeException e) {
      closeQuietly(authorizer, e);
      throw e;
    }
    return built.add(BuiltServices.AUTHORIZER, authorizer);
  }

  /**
   * Instantiates an authorizer class by its public no-argument constructor.
   *
   * @param className the name of a class that implements {@link GravitinoAuthorizer}
   * @return the new, uninitialized authorizer
   * @throws IllegalArgumentException if the class cannot be loaded or instantiated
   */
  @VisibleForTesting
  static GravitinoAuthorizer newAuthorizer(String className) {
    try {
      return Class.forName(className)
          .asSubclass(GravitinoAuthorizer.class)
          .getDeclaredConstructor()
          .newInstance();
    } catch (ReflectiveOperationException | ClassCastException e) {
      throw new IllegalArgumentException("Cannot initialize GravitinoAuthorizer", e);
    }
  }

  private static void closeQuietly(GravitinoAuthorizer authorizer, Exception cause) {
    try {
      authorizer.close();
    } catch (IOException | RuntimeException e) {
      cause.addSuppressed(e);
      LOG.warn("Failed to close the authorizer whose initialization failed", e);
    }
  }
}
