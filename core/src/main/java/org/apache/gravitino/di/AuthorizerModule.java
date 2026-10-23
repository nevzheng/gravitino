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
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.lifecycle.ManagedService;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds the {@link GravitinoAuthorizer} of the component.
 *
 * <p>When authorization is enabled, the authorizer is the class {@link Configs#AUTHORIZATION_IMPL}
 * names, loaded by reflection, so core does not depend on the server module that holds the built-in
 * implementation. When it is disabled, the authorizer is {@link NoopGravitinoAuthorizer#INSTANCE},
 * which allows everything, as the server's pass-through authorizer did with no access control to
 * consult. The first request builds the authorizer and initializes it with the component's {@link
 * AuthorizerContext}; the server requests it before it serves requests. It registers with {@link
 * ManagedServices} when it is built, after the services it reads, so it closes before them.
 *
 * <p>It is a module of its own, not part of {@link AuthorizationModule}, so a test component can
 * replace it.
 */
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
      AuthorizerContext context,
      ManagedServices services) {
    if (!authorizationEnabled) {
      return NoopGravitinoAuthorizer.INSTANCE;
    }
    GravitinoAuthorizer authorizer = newAuthorizer(config.get(Configs.AUTHORIZATION_IMPL));
    try {
      authorizer.initialize(context);
    } catch (RuntimeException e) {
      closeQuietly(authorizer, e);
      throw e;
    }
    services.register(ManagedService.closing("GravitinoAuthorizer", authorizer));
    return authorizer;
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
      throw new IllegalArgumentException("Cannot initialize GravitinoAuthorizer " + className, e);
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
