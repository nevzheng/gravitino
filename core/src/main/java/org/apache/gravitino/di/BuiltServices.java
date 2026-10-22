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

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.stats.StatisticDispatcher;

/**
 * Records which of the services that {@link LifecycleModule} starts or stops one component has
 * built.
 *
 * <p>The component builds each object on first request, and each {@link GravitinoProfiles profile}
 * requests a different subset; an initialization that fails part way requests only the first part.
 * The lifecycle must stop exactly what was built, and must not build anything itself, so each
 * module that builds one of these services records it here.
 */
@Singleton
final class BuiltServices {

  static final Key<MetricsSystem> METRICS_SYSTEM = new Key<>();
  static final Key<EventListenerManager> EVENT_LISTENER_MANAGER = new Key<>();
  static final Key<EntityStore> ENTITY_STORE = new Key<>();
  static final Key<KmsClientRegistry> KMS_CLIENT_REGISTRY = new Key<>();
  static final Key<SecretManager> SECRET_MANAGER = new Key<>();
  static final Key<CatalogManager> CATALOG_MANAGER = new Key<>();
  static final Key<MetalakeManager> METALAKE_MANAGER = new Key<>();
  static final Key<StatisticDispatcher> INTERNAL_STATISTIC_DISPATCHER = new Key<>();
  static final Key<StatisticDispatcher> STATISTIC_DISPATCHER = new Key<>();
  static final Key<JobOperationDispatcher> JOB_OPERATION_DISPATCHER = new Key<>();
  static final Key<AuxiliaryServiceManager> AUXILIARY_SERVICE_MANAGER = new Key<>();
  static final Key<LockManager> LOCK_MANAGER = new Key<>();
  static final Key<GravitinoAuthorizer> AUTHORIZER = new Key<>();

  private final Map<Key<?>, Object> built = new HashMap<>();

  @Inject
  BuiltServices() {}

  /**
   * Records a service that was built.
   *
   * @param key the service
   * @param service the instance
   * @return the instance
   */
  synchronized <T> T add(Key<T> key, T service) {
    built.put(key, service);
    return service;
  }

  /**
   * Returns a service if it was built.
   *
   * @param key the service
   * @return the instance, or null if it was not built
   */
  @Nullable
  @SuppressWarnings("unchecked")
  synchronized <T> T get(Key<T> key) {
    // add() only stores a T under a Key<T>.
    return (T) built.get(key);
  }

  /**
   * Runs an action on a service if it was built.
   *
   * @param key the service
   * @param action the action
   * @throws Exception if the action fails
   */
  <T> void ifBuilt(Key<T> key, Use<T> action) throws Exception {
    T service = get(key);
    if (service != null) {
      action.accept(service);
    }
  }

  /**
   * Identifies a service. Keys compare by identity.
   *
   * @param <T> the type of the service, which ties {@link #add} and {@link #get} together
   */
  @SuppressWarnings("UnusedTypeParameter")
  static final class Key<T> {
    private Key() {}
  }

  /**
   * An action on a service.
   *
   * @param <T> the type of the service
   */
  @FunctionalInterface
  interface Use<T> {
    void accept(T service) throws Exception;
  }
}
