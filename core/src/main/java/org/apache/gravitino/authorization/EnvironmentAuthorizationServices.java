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
package org.apache.gravitino.authorization;

import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * {@link AuthorizationServices} that read each service from a {@link GravitinoEnv} when they are
 * asked for it.
 *
 * <p>It keeps the entry points that predate {@link AuthorizationServices} working: the hooks built
 * with their deprecated constructors and the deprecated static methods of {@link
 * AuthorizationUtils}. It is deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentAuthorizationServices implements AuthorizationServices {

  /** The services of the process environment. */
  public static final EnvironmentAuthorizationServices INSTANCE =
      new EnvironmentAuthorizationServices(EnvironmentAuthorizationServices::processEnv);

  private final Supplier<GravitinoEnv> env;

  private EnvironmentAuthorizationServices(Supplier<GravitinoEnv> env) {
    this.env = env;
  }

  /**
   * Returns the services of the given environment.
   *
   * @param env the environment
   * @return services that read the environment's getters when asked
   */
  public static AuthorizationServices of(GravitinoEnv env) {
    Preconditions.checkNotNull(env, "env");
    return new EnvironmentAuthorizationServices(() -> env);
  }

  @Override
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return env.get().internalAccessControlDispatcher();
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    return env.get().internalOwnerDispatcher();
  }

  @Override
  public FutureGrantManager futureGrantManager() {
    return env.get().futureGrantManager();
  }

  @Override
  public GravitinoAuthorizer gravitinoAuthorizer() {
    return env.get().gravitinoAuthorizer();
  }

  @Override
  public CatalogManager catalogManager() {
    return env.get().catalogManager();
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return env.get().internalCatalogDispatcher();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return env.get().internalSchemaDispatcher();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return env.get().internalTableDispatcher();
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return env.get().internalFilesetDispatcher();
  }

  @LegacyEnvAccess
  private static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }
}
