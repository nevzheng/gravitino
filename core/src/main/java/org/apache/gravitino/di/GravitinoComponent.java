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
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The root object graph of the Gravitino server.
 *
 * <p>The migration from {@code GravitinoEnv} to dependency injection moves one area at a time into
 * this component. It starts with the leaves every other object depends on: {@link ConfigModule}
 * owns the id generator and the metrics system, {@link LockModule} the lock manager, {@link
 * StorageModule} the entity store, the KMS client registry and the secret manager, and {@link
 * EventModule} the event listener manager, its bus and the audit log manager. {@code GravitinoEnv}
 * takes these from the component and still builds everything else, so every object is built by
 * exactly one side.
 *
 * <p>Singletons are per component: each component instance builds its own objects, lazily, the
 * first time one is requested.
 */
@Singleton
@Component(modules = {ConfigModule.class, LockModule.class, StorageModule.class, EventModule.class})
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
   * Returns the entity store, created from the configuration and initialized on first request.
   *
   * @return the entity store
   */
  EntityStore entityStore();

  /**
   * Returns the KMS client registry.
   *
   * @return the KMS client registry
   */
  KmsClientRegistry kmsClientRegistry();

  /**
   * Returns the secret manager.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the event listener manager, with the listeners named by the configuration loaded.
   *
   * @return the event listener manager
   */
  EventListenerManager eventListenerManager();

  /**
   * Returns the event bus of the event listener manager.
   *
   * @return the event bus
   */
  EventBus eventBus();

  /**
   * Returns the audit log manager, which listens to the event bus when audit logging is enabled.
   *
   * @return the audit log manager
   */
  AuditLogManager auditLogManager();

  /** Creates {@link GravitinoComponent} instances. */
  @Component.Factory
  interface Factory {

    /**
     * Creates a component.
     *
     * @param config the server configuration, the only leaf the graph does not build itself
     * @return a new component
     */
    GravitinoComponent create(@BindsInstance Config config);
  }
}
