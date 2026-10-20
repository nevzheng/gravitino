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

import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The root object graph of the Gravitino server.
 *
 * <p>The migration from {@code GravitinoEnv} to dependency injection moves one area at a time into
 * this component. Objects the component owns are built by its modules; {@link ConfigModule} owns
 * the id generator and the metrics system, and {@link LockModule} owns the lock manager. Objects
 * that {@code GravitinoEnv} still builds reach the graph through {@link LegacyBridge} and {@link
 * LegacyModule}, so every object is built by exactly one side.
 *
 * <p>Singletons are per component: each component instance builds its own lock manager, id
 * generator and metrics system, lazily, the first time one is requested.
 */
@Singleton
@Component(modules = {ConfigModule.class, LockModule.class, LegacyModule.class})
public interface GravitinoComponent {

  /**
   * Returns the server configuration the component was created with.
   *
   * @return the configuration
   */
  Config config();

  /**
   * Returns the id generator owned by this component.
   *
   * @return the id generator
   */
  IdGenerator idGenerator();

  /**
   * Returns the metrics system owned by this component, with the JVM metrics source registered.
   *
   * @return the metrics system
   */
  MetricsSystem metricsSystem();

  /**
   * Returns the tree lock manager owned by this component.
   *
   * @return the lock manager
   */
  LockManager lockManager();

  /**
   * Returns the entity store that {@code GravitinoEnv} still builds, seeded through {@link
   * LegacyBridge}.
   *
   * @return the entity store
   */
  EntityStore entityStore();

  /**
   * Returns the catalog manager that {@code GravitinoEnv} still builds, seeded through {@link
   * LegacyBridge}.
   *
   * @return the catalog manager
   */
  CatalogManager catalogManager();

  /**
   * Returns the secret manager that {@code GravitinoEnv} still builds, seeded through {@link
   * LegacyBridge}.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the event bus that {@code GravitinoEnv} still builds, seeded through {@link
   * LegacyBridge}.
   *
   * @return the event bus
   */
  EventBus eventBus();

  /** Creates {@link GravitinoComponent} instances. */
  @Component.Factory
  interface Factory {

    /**
     * Creates a component.
     *
     * @param config the server configuration, the only leaf the graph does not build itself
     * @param legacy the source of the objects {@code GravitinoEnv} still builds; removed when the
     *     migration is complete
     * @return a new component
     */
    GravitinoComponent create(@BindsInstance Config config, @BindsInstance LegacyBridge legacy);
  }
}
