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

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.Map;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.hook.MetalakeHookDispatcher;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the catalog and metalake managers and their dispatcher chains. The credential and secret
 * property dispatchers that work on catalogs have {@code @Inject} constructors, so they need no
 * binding here.
 *
 * <p>The {@link Internal} metalake and catalog chains are normalize → manager. The {@link Public}
 * chains are event → normalize → hook → manager, around the same manager instance. Both managers
 * register with the {@link ManagedServices registry}, which closes them.
 *
 * <p>{@link Chain} assembles each chain from its layers and the {@link DispatcherDecorator
 * decorators} contributed for it, keyed by {@link Layer}; upstream contributes none.
 */
@SuppressWarnings("CloseableProvides")
@Module
abstract class CatalogModule {

  private CatalogModule() {}

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<MetalakeDispatcher>> internalMetalakeDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<MetalakeDispatcher>> metalakeDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<CatalogDispatcher>> internalCatalogDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<CatalogDispatcher>> catalogDecorators();

  /**
   * Builds the catalog manager. It starts its catalog cache cleaner and registers a change-log
   * listener with entity stores that support one.
   *
   * <p>The authorization plugins of its catalogs read the internal dispatchers, which are built
   * around this manager, so the plugin context resolves them when a plugin asks.
   */
  @Provides
  @Singleton
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
      ManagedServices services) {
    AuthorizationPluginContext pluginContext =
        AuthorizationPluginContext.of(
            () -> authorizationEnabled,
            internalCatalogDispatcher::get,
            internalSchemaDispatcher::get,
            internalTableDispatcher::get,
            internalFilesetDispatcher::get);
    return services.register(
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
   * Builds the metalake manager. It takes the catalog manager so that a force drop removes the
   * child catalogs through {@link CatalogManager#dropCatalog}.
   */
  @Provides
  @Singleton
  static MetalakeManager metalakeManager(
      EntityStore entityStore,
      IdGenerator idGenerator,
      CatalogManager catalogManager,
      LockManager lockManager,
      ManagedServices services) {
    return services.register(
        new MetalakeManager(entityStore, idGenerator, catalogManager, lockManager));
  }

  @Provides
  @Singleton
  @Internal
  static MetalakeDispatcher internalMetalakeDispatcher(
      MetalakeManager metalakeManager,
      @Internal Map<Layer, DispatcherDecorator<MetalakeDispatcher>> decorators) {
    return Chain.<MetalakeDispatcher>of("@Internal MetalakeDispatcher", metalakeManager, decorators)
        .normalize(MetalakeNormalizeDispatcher::new)
        .build();
  }

  @Provides
  @Singleton
  @Public
  static MetalakeDispatcher metalakeDispatcher(
      EventBus eventBus,
      MetalakeManager metalakeManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<MetalakeDispatcher>> decorators) {
    return Chain.<MetalakeDispatcher>of("@Public MetalakeDispatcher", metalakeManager, decorators)
        .hook(d -> new MetalakeHookDispatcher(d, authorizationServices))
        .normalize(MetalakeNormalizeDispatcher::new)
        .event(d -> new MetalakeEventDispatcher(eventBus, d))
        .build();
  }

  @Provides
  @Singleton
  @Internal
  static CatalogDispatcher internalCatalogDispatcher(
      CatalogManager catalogManager,
      @Internal Map<Layer, DispatcherDecorator<CatalogDispatcher>> decorators) {
    return Chain.<CatalogDispatcher>of("@Internal CatalogDispatcher", catalogManager, decorators)
        .normalize(CatalogNormalizeDispatcher::new)
        .build();
  }

  @Provides
  @Singleton
  @Public
  static CatalogDispatcher catalogDispatcher(
      EventBus eventBus,
      CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<CatalogDispatcher>> decorators) {
    return Chain.<CatalogDispatcher>of("@Public CatalogDispatcher", catalogManager, decorators)
        .hook(d -> new CatalogHookDispatcher(d, authorizationServices))
        .normalize(CatalogNormalizeDispatcher::new)
        .event(d -> new CatalogEventDispatcher(eventBus, d))
        .build();
  }
}
