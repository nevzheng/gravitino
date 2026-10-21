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

import javax.inject.Inject;
import javax.inject.Provider;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
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
 * The {@link AuxiliaryContext} of the Gravitino server, backed by the component.
 *
 * <p>Every service is resolved when it is asked for, so building the context builds nothing. The
 * server initializes its auxiliary services after every service the context offers is built, so a
 * service gets the same instances the rest of the server uses, and the dispatchers carry the
 * decorators of a downstream component. The internal owner dispatcher is {@code null} when
 * authorization is disabled, as for the hooks of the server.
 */
final class ComponentAuxiliaryContext implements AuxiliaryContext {

  private final Config config;
  private final Provider<EventBus> eventBus;
  private final Provider<MetricsSystem> metricsSystem;
  private final Provider<SecretManager> secretManager;
  private final Provider<KmsClientRegistry> kmsClientRegistry;
  private final Provider<IdGenerator> idGenerator;
  private final Provider<LockManager> lockManager;
  private final Provider<EntityStore> entityStore;
  private final Provider<CatalogAccess> catalogAccess;
  private final Provider<CatalogDispatcher> catalogDispatcher;
  private final Provider<SchemaDispatcher> schemaDispatcher;
  private final Provider<TableDispatcher> tableDispatcher;
  private final Provider<PolicyDispatcher> policyDispatcher;
  private final Provider<SecretPropertyOperationDispatcher> secretPropertyOperationDispatcher;
  private final Provider<SchemaDispatcher> internalSchemaDispatcher;
  private final Provider<TableDispatcher> internalTableDispatcher;
  private final Provider<ViewDispatcher> internalViewDispatcher;
  private final boolean authorizationEnabled;
  private final Provider<OwnerDispatcher> internalOwnerDispatcher;

  @Inject
  ComponentAuxiliaryContext(
      Config config,
      Provider<EventBus> eventBus,
      Provider<MetricsSystem> metricsSystem,
      Provider<SecretManager> secretManager,
      Provider<KmsClientRegistry> kmsClientRegistry,
      Provider<IdGenerator> idGenerator,
      Provider<LockManager> lockManager,
      Provider<EntityStore> entityStore,
      Provider<CatalogAccess> catalogAccess,
      @Public Provider<CatalogDispatcher> catalogDispatcher,
      @Public Provider<SchemaDispatcher> schemaDispatcher,
      @Public Provider<TableDispatcher> tableDispatcher,
      @Public Provider<PolicyDispatcher> policyDispatcher,
      Provider<SecretPropertyOperationDispatcher> secretPropertyOperationDispatcher,
      @Internal Provider<SchemaDispatcher> internalSchemaDispatcher,
      @Internal Provider<TableDispatcher> internalTableDispatcher,
      @Internal Provider<ViewDispatcher> internalViewDispatcher,
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<OwnerDispatcher> internalOwnerDispatcher) {
    this.config = config;
    this.eventBus = eventBus;
    this.metricsSystem = metricsSystem;
    this.secretManager = secretManager;
    this.kmsClientRegistry = kmsClientRegistry;
    this.idGenerator = idGenerator;
    this.lockManager = lockManager;
    this.entityStore = entityStore;
    this.catalogAccess = catalogAccess;
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = schemaDispatcher;
    this.tableDispatcher = tableDispatcher;
    this.policyDispatcher = policyDispatcher;
    this.secretPropertyOperationDispatcher = secretPropertyOperationDispatcher;
    this.internalSchemaDispatcher = internalSchemaDispatcher;
    this.internalTableDispatcher = internalTableDispatcher;
    this.internalViewDispatcher = internalViewDispatcher;
    this.authorizationEnabled = authorizationEnabled;
    this.internalOwnerDispatcher = internalOwnerDispatcher;
  }

  @Override
  public Config config() {
    return config;
  }

  @Override
  public EventBus eventBus() {
    return eventBus.get();
  }

  @Override
  public MetricsSystem metricsSystem() {
    return metricsSystem.get();
  }

  @Override
  public SecretManager secretManager() {
    return secretManager.get();
  }

  @Override
  public KmsClientRegistry kmsClientRegistry() {
    return kmsClientRegistry.get();
  }

  @Override
  public IdGenerator idGenerator() {
    return idGenerator.get();
  }

  @Override
  public LockManager lockManager() {
    return lockManager.get();
  }

  @Override
  public EntityStore entityStore() {
    return entityStore.get();
  }

  @Override
  public CatalogAccess catalogAccess() {
    return catalogAccess.get();
  }

  @Override
  public CatalogDispatcher catalogDispatcher() {
    return catalogDispatcher.get();
  }

  @Override
  public SchemaDispatcher schemaDispatcher() {
    return schemaDispatcher.get();
  }

  @Override
  public TableDispatcher tableDispatcher() {
    return tableDispatcher.get();
  }

  @Override
  public PolicyDispatcher policyDispatcher() {
    return policyDispatcher.get();
  }

  @Override
  public SecretPropertyOperationDispatcher secretPropertyOperationDispatcher() {
    return secretPropertyOperationDispatcher.get();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return internalSchemaDispatcher.get();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return internalTableDispatcher.get();
  }

  @Override
  public ViewDispatcher internalViewDispatcher() {
    return internalViewDispatcher.get();
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    return authorizationEnabled ? internalOwnerDispatcher.get() : null;
  }
}
