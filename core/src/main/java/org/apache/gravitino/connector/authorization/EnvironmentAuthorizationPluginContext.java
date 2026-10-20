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
package org.apache.gravitino.connector.authorization;

import javax.annotation.Nullable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * An {@link AuthorizationPluginContext} that reads each service from {@link GravitinoEnv} when it
 * is asked for it.
 *
 * <p>It keeps the entry points that predate {@link AuthorizationPluginContext} working: a plugin
 * built with a constructor that takes no context, and a catalog manager built without one.
 * Authorization is enabled exactly when the environment holds an internal access control
 * dispatcher. It is deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentAuthorizationPluginContext implements AuthorizationPluginContext {

  /** The only instance. */
  public static final EnvironmentAuthorizationPluginContext INSTANCE =
      new EnvironmentAuthorizationPluginContext();

  private EnvironmentAuthorizationPluginContext() {}

  /**
   * Returns the given context, or this one when there is none.
   *
   * @param context the context a plugin was given, or null
   * @return the given context if it is not null, else {@link #INSTANCE}
   */
  public static AuthorizationPluginContext orEnvironment(
      @Nullable AuthorizationPluginContext context) {
    return context != null ? context : INSTANCE;
  }

  @Override
  public boolean authorizationEnabled() {
    return env().internalAccessControlDispatcher() != null;
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return env().internalCatalogDispatcher();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return env().internalSchemaDispatcher();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return env().internalTableDispatcher();
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return env().internalFilesetDispatcher();
  }

  @LegacyEnvAccess
  private static GravitinoEnv env() {
    return GravitinoEnv.getInstance();
  }
}
