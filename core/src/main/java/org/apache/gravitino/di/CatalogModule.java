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
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.hook.MetalakeHookDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the catalog and metalake managers and their dispatcher chains. The credential and secret
 * property dispatchers that work on catalogs have {@code @Inject} constructors, so they need no
 * binding here.
 *
 * <p>The {@link Internal} metalake and catalog chains are normalize → manager. The {@link Public}
 * chains are event → normalize → hook → manager, around the same manager instance. Dagger does not
 * close the managers; {@code GravitinoEnv.shutdown()} does.
 */
@SuppressWarnings("CloseableProvides")
@Module
abstract class CatalogModule {

  private CatalogModule() {}

  /**
   * Builds the catalog manager. It starts its catalog cache cleaner and registers a change-log
   * listener with entity stores that support one.
   */
  @Provides
  @Singleton
  static CatalogManager catalogManager(
      Config config,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      LockManager lockManager) {
    return new CatalogManager(config, entityStore, idGenerator, secretManager, lockManager);
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
      LockManager lockManager) {
    return new MetalakeManager(entityStore, idGenerator, catalogManager, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static MetalakeDispatcher internalMetalakeDispatcher(MetalakeManager metalakeManager) {
    return new MetalakeNormalizeDispatcher(metalakeManager);
  }

  @Provides
  @Singleton
  @Public
  static MetalakeDispatcher metalakeDispatcher(EventBus eventBus, MetalakeManager metalakeManager) {
    MetalakeHookDispatcher hook = new MetalakeHookDispatcher(metalakeManager);
    return new MetalakeEventDispatcher(eventBus, new MetalakeNormalizeDispatcher(hook));
  }

  @Provides
  @Singleton
  @Internal
  static CatalogDispatcher internalCatalogDispatcher(CatalogManager catalogManager) {
    return new CatalogNormalizeDispatcher(catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static CatalogDispatcher catalogDispatcher(EventBus eventBus, CatalogManager catalogManager) {
    CatalogHookDispatcher hook = new CatalogHookDispatcher(catalogManager);
    return new CatalogEventDispatcher(eventBus, new CatalogNormalizeDispatcher(hook));
  }
}
