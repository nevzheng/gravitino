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
package org.apache.gravitino.connector;

import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * A {@link CatalogContext} that reads each service from {@link GravitinoEnv} when it is asked for
 * it.
 *
 * <p>It keeps the entry points that predate {@link CatalogContext} working: a catalog that is not
 * given a context, catalog operations built with their no-argument constructor, and the
 * three-argument {@link CatalogOperations#initialize(java.util.Map, CatalogInfo,
 * HasPropertyMetadata)} called directly. Catalogs loaded by {@code CatalogManager} get the
 * manager's context instead. It is deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentCatalogContext implements CatalogContext {

  /** The only instance. */
  public static final EnvironmentCatalogContext INSTANCE = new EnvironmentCatalogContext();

  private EnvironmentCatalogContext() {}

  /**
   * Returns the given context, or this one when there is none.
   *
   * @param context the context a catalog or its operations were given, or null
   * @return the given context if it is not null, else {@link #INSTANCE}
   */
  public static CatalogContext orEnvironment(@Nullable CatalogContext context) {
    return context != null ? context : INSTANCE;
  }

  @Override
  public EntityStore entityStore() {
    return env().entityStore();
  }

  @Override
  public SecretManager secretManager() {
    return env().secretManager();
  }

  @Override
  public MetricsSystem metricsSystem() {
    return env().metricsSystem();
  }

  @Override
  public IdGenerator idGenerator() {
    return env().idGenerator();
  }

  @Override
  public boolean credentialBackfillToProperties() {
    Config serverConfig = env().config();
    return serverConfig != null
        && Boolean.TRUE.equals(serverConfig.get(Configs.CATALOG_CREDENTIAL_BACKFILL_TO_PROPERTIES));
  }

  @LegacyEnvAccess
  private static GravitinoEnv env() {
    return GravitinoEnv.getInstance();
  }
}
