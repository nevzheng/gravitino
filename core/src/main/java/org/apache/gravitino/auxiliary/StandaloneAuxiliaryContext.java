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
 * The {@link AuxiliaryContext} of a standalone server: the base services, and an {@link
 * IllegalStateException} for every service that only the Gravitino server builds.
 */
final class StandaloneAuxiliaryContext implements AuxiliaryContext {

  private final Config config;
  private final EventBus eventBus;
  private final MetricsSystem metricsSystem;
  private final SecretManager secretManager;
  private final KmsClientRegistry kmsClientRegistry;

  StandaloneAuxiliaryContext(
      Config config,
      EventBus eventBus,
      MetricsSystem metricsSystem,
      SecretManager secretManager,
      KmsClientRegistry kmsClientRegistry) {
    this.config = config;
    this.eventBus = eventBus;
    this.metricsSystem = metricsSystem;
    this.secretManager = secretManager;
    this.kmsClientRegistry = kmsClientRegistry;
  }

  @Override
  public Config config() {
    return config;
  }

  @Override
  public EventBus eventBus() {
    return eventBus;
  }

  @Override
  public MetricsSystem metricsSystem() {
    return metricsSystem;
  }

  @Override
  public SecretManager secretManager() {
    return secretManager;
  }

  @Override
  public KmsClientRegistry kmsClientRegistry() {
    return kmsClientRegistry;
  }

  @Override
  public IdGenerator idGenerator() {
    throw serverOnly("id generator");
  }

  @Override
  public LockManager lockManager() {
    throw serverOnly("lock manager");
  }

  @Override
  public EntityStore entityStore() {
    throw serverOnly("entity store");
  }

  @Override
  public CatalogManager catalogManager() {
    throw serverOnly("catalog manager");
  }

  @Override
  public CatalogDispatcher catalogDispatcher() {
    throw serverOnly("catalog dispatcher");
  }

  @Override
  public SchemaDispatcher schemaDispatcher() {
    throw serverOnly("schema dispatcher");
  }

  @Override
  public TableDispatcher tableDispatcher() {
    throw serverOnly("table dispatcher");
  }

  @Override
  public PolicyDispatcher policyDispatcher() {
    throw serverOnly("policy dispatcher");
  }

  @Override
  public SecretPropertyOperationDispatcher secretPropertyOperationDispatcher() {
    throw serverOnly("secret property operation dispatcher");
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    throw serverOnly("internal schema dispatcher");
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    throw serverOnly("internal table dispatcher");
  }

  @Override
  public ViewDispatcher internalViewDispatcher() {
    throw serverOnly("internal view dispatcher");
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    throw serverOnly("internal owner dispatcher");
  }

  private static IllegalStateException serverOnly(String service) {
    return new IllegalStateException(
        "The "
            + service
            + " is only available to an auxiliary service that runs inside the Gravitino server");
  }
}
