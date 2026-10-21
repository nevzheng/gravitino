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

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.Set;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.hook.MetalakeHookDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the catalog and metalake managers, their dispatcher chains and the credential and secret
 * property dispatchers that work on catalogs.
 *
 * <p>Both managers are bound {@link Undecorated}, so only this package can inject them: the
 * metalake manager is reached through its chains, and the catalog manager through its chains and
 * through {@link CatalogAccess}, which has no method that changes a catalog.
 *
 * <p>The {@link Internal} metalake and catalog chains are normalize → manager. The {@link Public}
 * chains are event → normalize → hook → manager, around the same manager instance. Dagger does not
 * close the managers; the {@link LifecycleModule lifecycle} does. Each chain is wrapped in its
 * {@link DispatcherDecorator decorators}.
 */
@SuppressWarnings("CloseableProvides")
@Module
abstract class CatalogModule {

  private CatalogModule() {}

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<MetalakeDispatcher>> internalMetalakeDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<MetalakeDispatcher>> metalakeDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<CatalogDispatcher>> internalCatalogDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<CatalogDispatcher>> catalogDecorators();

  /**
   * Builds the catalog manager. It starts its catalog cache cleaner and registers a change-log
   * listener with entity stores that support one.
   *
   * <p>The authorization plugins of its catalogs read the internal dispatchers, which are built
   * around this manager, so the plugin context resolves them when a plugin asks.
   */
  @Provides
  @Singleton
  @Undecorated
  static CatalogManager catalogManager(
      Config config,
      @AuthorizationEnabled boolean authorizationEnabled,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      LockManager lockManager,
      MetricsSystem metricsSystem,
      @Internal Provider<CatalogDispatcher> internalCatalogDispatcher,
      @Internal Provider<SchemaDispatcher> internalSchemaDispatcher,
      @Internal Provider<TableDispatcher> internalTableDispatcher,
      @Internal Provider<FilesetDispatcher> internalFilesetDispatcher,
      BuiltServices built) {
    AuthorizationPluginContext pluginContext =
        AuthorizationPluginContext.of(
            () -> authorizationEnabled,
            internalCatalogDispatcher::get,
            internalSchemaDispatcher::get,
            internalTableDispatcher::get,
            internalFilesetDispatcher::get);
    return built.add(
        BuiltServices.CATALOG_MANAGER,
        new CatalogManager(
            config,
            entityStore,
            idGenerator,
            secretManager,
            lockManager,
            metricsSystem,
            pluginContext));
  }

  /**
   * Binds the lookups and leased access of the catalog manager. This is the face of the manager
   * that code outside this package may inject: it has no method that changes a catalog.
   *
   * @param catalogManager the catalog manager
   * @return the catalog access
   */
  @Binds
  abstract CatalogAccess catalogAccess(@Undecorated CatalogManager catalogManager);

  /**
   * Builds the metalake manager. It takes the catalog manager so that a force drop removes the
   * child catalogs through {@link CatalogManager#dropCatalog}.
   */
  @Provides
  @Singleton
  @Undecorated
  static MetalakeManager metalakeManager(
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Undecorated CatalogManager catalogManager,
      LockManager lockManager,
      BuiltServices built) {
    return built.add(
        BuiltServices.METALAKE_MANAGER,
        new MetalakeManager(entityStore, idGenerator, catalogManager, lockManager));
  }

  @Provides
  @Singleton
  @Internal
  static MetalakeDispatcher internalMetalakeDispatcher(
      @Undecorated MetalakeManager metalakeManager,
      @Internal Set<DispatcherDecorator<MetalakeDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal MetalakeDispatcher",
        new MetalakeNormalizeDispatcher(metalakeManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static MetalakeDispatcher metalakeDispatcher(
      EventBus eventBus,
      @Undecorated MetalakeManager metalakeManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<MetalakeDispatcher>> decorators) {
    MetalakeHookDispatcher hook =
        new MetalakeHookDispatcher(metalakeManager, authorizationServices);
    return Decorators.apply(
        "@Public MetalakeDispatcher",
        new MetalakeEventDispatcher(eventBus, new MetalakeNormalizeDispatcher(hook)),
        decorators);
  }

  @Provides
  @Singleton
  @Internal
  static CatalogDispatcher internalCatalogDispatcher(
      @Undecorated CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<CatalogDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal CatalogDispatcher", new CatalogNormalizeDispatcher(catalogManager), decorators);
  }

  @Provides
  @Singleton
  @Public
  static CatalogDispatcher catalogDispatcher(
      EventBus eventBus,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<CatalogDispatcher>> decorators) {
    CatalogHookDispatcher hook = new CatalogHookDispatcher(catalogManager, authorizationServices);
    return Decorators.apply(
        "@Public CatalogDispatcher",
        new CatalogEventDispatcher(eventBus, new CatalogNormalizeDispatcher(hook)),
        decorators);
  }

  /**
   * Builds the credential operation dispatcher, which reaches the catalogs through the catalog
   * manager.
   *
   * @param catalogManager the catalog manager
   * @param entityStore the entity store
   * @param idGenerator the id generator
   * @param secretManager the secret manager
   * @return the credential operation dispatcher
   */
  @Provides
  @Singleton
  static CredentialOperationDispatcher credentialOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    return new CredentialOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager);
  }

  /**
   * Builds the secret property dispatcher, which reaches the catalogs through the catalog manager.
   *
   * @param catalogManager the catalog manager
   * @param entityStore the entity store
   * @param idGenerator the id generator
   * @param secretManager the secret manager
   * @return the secret property dispatcher
   */
  @Provides
  @Singleton
  static SecretPropertyOperationDispatcher secretPropertyOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    return new SecretPropertyOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager);
  }
}
