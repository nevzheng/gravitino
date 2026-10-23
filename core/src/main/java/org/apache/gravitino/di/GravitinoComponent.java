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
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;

/**
 * The root object graph of the Gravitino server.
 *
 * <p>The migration from {@code GravitinoEnv} to dependency injection moves one area at a time into
 * this component. {@link ConfigModule} builds the id generator and the metrics system, {@link
 * StorageModule} the entity store and the secret stores, {@link LockModule} the lock manager,
 * {@link EventModule} the event listener manager, the event bus and the audit log manager, {@link
 * CatalogModule} the catalog and metalake managers and chains, {@link GovernanceModule} the tag,
 * policy, owner and statistic chains, and {@link AuthorizationModule} the access-control
 * components. {@code GravitinoEnv} takes these from the component and still builds the other
 * chains, so every object is built by exactly one side.
 *
 * <p>Each entity type has an {@link Internal} chain for infrastructure code and a {@link Public}
 * chain for REST resources. The component builds nothing until it is asked: {@code GravitinoEnv}
 * asks for each object at the point of its initialization sequence where it used to build it.
 *
 * <p>Singletons are per component: each component instance builds its own objects, each one the
 * first time it is requested.
 */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      StorageModule.class,
      LockModule.class,
      EventModule.class,
      CatalogModule.class,
      GovernanceModule.class,
      AuthorizationModule.class
    })
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
   * Returns the KMS client registry. {@code GravitinoEnv} closes it.
   *
   * @return the KMS client registry
   */
  KmsClientRegistry kmsClientRegistry();

  /**
   * Returns the secret manager. {@code GravitinoEnv} closes it.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the event listener manager, with the listeners named by the configuration loaded.
   * {@code GravitinoEnv} starts and stops it.
   *
   * @return the event listener manager
   */
  EventListenerManager eventListenerManager();

  /**
   * Returns the event bus created by the {@link #eventListenerManager()}.
   *
   * @return the event bus
   */
  EventBus eventBus();

  /**
   * Returns the audit log manager. Building it registers the audit listener with the {@link
   * #eventListenerManager()} when audit logging is enabled; the {@link #eventBus()} depends on it,
   * so it is built before the bus.
   *
   * @return the audit log manager
   */
  AuditLogManager auditLogManager();

  /**
   * Returns the catalog manager.
   *
   * @return the catalog manager
   */
  CatalogManager catalogManager();

  /**
   * Returns the metalake manager.
   *
   * @return the metalake manager
   */
  MetalakeManager metalakeManager();

  /**
   * Returns the internal metalake chain: normalize, then the {@link #metalakeManager()}.
   *
   * @return the internal metalake dispatcher
   */
  @Internal
  MetalakeDispatcher internalMetalakeDispatcher();

  /**
   * Returns the public metalake chain: event, normalize, hook, then the {@link #metalakeManager()}.
   *
   * @return the public metalake dispatcher
   */
  @Public
  MetalakeDispatcher metalakeDispatcher();

  /**
   * Returns the internal catalog chain: normalize, then the {@link #catalogManager()}.
   *
   * @return the internal catalog dispatcher
   */
  @Internal
  CatalogDispatcher internalCatalogDispatcher();

  /**
   * Returns the public catalog chain: event, normalize, hook, then the {@link #catalogManager()}.
   *
   * @return the public catalog dispatcher
   */
  @Public
  CatalogDispatcher catalogDispatcher();

  /**
   * Returns the credential operation dispatcher.
   *
   * @return the credential operation dispatcher
   */
  CredentialOperationDispatcher credentialOperationDispatcher();

  /**
   * Returns the secret property operation dispatcher.
   *
   * @return the secret property operation dispatcher
   */
  SecretPropertyOperationDispatcher secretPropertyOperationDispatcher();

  /**
   * Returns the tag manager, the internal tag chain.
   *
   * @return the internal tag dispatcher
   */
  @Internal
  TagDispatcher internalTagDispatcher();

  /**
   * Returns the public tag chain: event, hook, then the {@link #internalTagDispatcher()} manager.
   *
   * @return the public tag dispatcher
   */
  @Public
  TagDispatcher tagDispatcher();

  /**
   * Returns the policy manager, the internal policy chain.
   *
   * @return the internal policy dispatcher
   */
  @Internal
  PolicyDispatcher internalPolicyDispatcher();

  /**
   * Returns the public policy chain: event, hook, then the {@link #internalPolicyDispatcher()}
   * manager.
   *
   * @return the public policy dispatcher
   */
  @Public
  PolicyDispatcher policyDispatcher();

  /**
   * Returns the internal owner chain: the owner manager, or {@link NoopOwnerDispatcher} while
   * authorization is disabled.
   *
   * @return the internal owner dispatcher
   */
  @Internal
  OwnerDispatcher internalOwnerDispatcher();

  /**
   * Returns the public owner chain: event, then the owner manager, or {@link NoopOwnerDispatcher}
   * while authorization is disabled.
   *
   * @return the public owner dispatcher
   */
  @Public
  OwnerDispatcher ownerDispatcher();

  /**
   * Returns the statistic manager, the internal statistic chain.
   *
   * @return the internal statistic dispatcher
   */
  @Internal
  StatisticDispatcher internalStatisticDispatcher();

  /**
   * Returns the public statistic chain: event, then the {@link #internalStatisticDispatcher()}
   * manager.
   *
   * @return the public statistic dispatcher
   */
  @Public
  StatisticDispatcher statisticDispatcher();

  /**
   * Returns the access control manager, the internal access-control chain. It can be built while
   * authorization is disabled, but {@code GravitinoEnv} only requests it when it is enabled.
   *
   * @return the internal access control dispatcher
   */
  @Internal
  AccessControlDispatcher internalAccessControlDispatcher();

  /**
   * Returns the public access-control chain: event, hook, then the {@link
   * #internalAccessControlDispatcher()} manager. It can be built while authorization is disabled,
   * but {@code GravitinoEnv} only requests it when it is enabled.
   *
   * @return the public access control dispatcher
   */
  @Public
  AccessControlDispatcher accessControlDispatcher();

  /**
   * Returns the bulk manager. It can be built while authorization is disabled, but {@code
   * GravitinoEnv} only requests it when it is enabled.
   *
   * @return the bulk manager
   */
  BulkManager bulkManager();

  /**
   * Returns the future grant manager, which uses the {@link #internalOwnerDispatcher()}. It can be
   * built while authorization is disabled, but {@code GravitinoEnv} only requests it when it is
   * enabled.
   *
   * @return the future grant manager
   */
  FutureGrantManager futureGrantManager();

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
