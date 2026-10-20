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
package org.apache.gravitino.auxiliary;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.storage.IdGenerator;

/**
 * An {@link AuxiliaryContext} that reads each service from {@link GravitinoEnv} when it is asked
 * for it.
 *
 * <p>It keeps the entry points that predate {@link AuxiliaryContext} working: an auxiliary service
 * initialized through {@link GravitinoAuxiliaryService#serviceInit(java.util.Map, boolean)}, and
 * the public constructors of its parts that take no context. Each method returns what the {@code
 * GravitinoEnv} getter of the same name returns, including {@code null} or an exception for a
 * service the environment did not build. Services loaded by the server get the server's context
 * instead. It is deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentAuxiliaryContext implements AuxiliaryContext {

  /** The only instance. */
  public static final EnvironmentAuxiliaryContext INSTANCE = new EnvironmentAuxiliaryContext();

  private EnvironmentAuxiliaryContext() {}

  @Override
  public Config config() {
    return env().config();
  }

  @Override
  public EventBus eventBus() {
    return env().eventBus();
  }

  @Override
  public MetricsSystem metricsSystem() {
    return env().metricsSystem();
  }

  @Override
  public SecretManager secretManager() {
    return env().secretManager();
  }

  @Override
  public KmsClientRegistry kmsClientRegistry() {
    return env().kmsClientRegistry();
  }

  @Override
  public IdGenerator idGenerator() {
    return env().idGenerator();
  }

  @Override
  public LockManager lockManager() {
    return env().lockManager();
  }

  @Override
  public EntityStore entityStore() {
    return env().entityStore();
  }

  @Override
  public CatalogManager catalogManager() {
    return env().catalogManager();
  }

  @Override
  public CatalogDispatcher catalogDispatcher() {
    return env().catalogDispatcher();
  }

  @Override
  public SchemaDispatcher schemaDispatcher() {
    return env().schemaDispatcher();
  }

  @Override
  public TableDispatcher tableDispatcher() {
    return env().tableDispatcher();
  }

  @Override
  public PolicyDispatcher policyDispatcher() {
    return env().policyDispatcher();
  }

  @Override
  public SecretPropertyOperationDispatcher secretPropertyOperationDispatcher() {
    return env().secretPropertyOperationDispatcher();
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
  public ViewDispatcher internalViewDispatcher() {
    return env().internalViewDispatcher();
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    return env().internalOwnerDispatcher();
  }

  @LegacyEnvAccess
  private static GravitinoEnv env() {
    return GravitinoEnv.getInstance();
  }
}
