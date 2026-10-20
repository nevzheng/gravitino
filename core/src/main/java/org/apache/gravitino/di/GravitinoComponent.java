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
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;

/**
 * The root object graph of the Gravitino server.
 *
 * <p>The migration from {@code GravitinoEnv} to dependency injection moves one area at a time into
 * this component. Objects the component owns are built by its modules; {@link ConfigModule} owns
 * the id generator and the metrics system, {@link LockModule} owns the lock manager, {@link
 * SchemaTableModule} owns the schema, table, partition and view chains, {@link GovernanceModule}
 * owns the tag, policy, owner and statistic managers and their chains, and {@link
 * AuthorizationModule} owns the access-control components. Objects that {@code GravitinoEnv} still
 * builds reach the graph through {@link LegacyBridge} and {@link LegacyModule}, so every object is
 * built by exactly one side.
 *
 * <p>Singletons are per component: each component instance builds its own lock manager, id
 * generator and metrics system, lazily, the first time one is requested.
 */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      LockModule.class,
      SchemaTableModule.class,
      GovernanceModule.class,
      AuthorizationModule.class,
      LegacyModule.class
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
   * Returns the schema operation dispatcher shared by the internal and public schema chains.
   *
   * <p>Transitional: {@code GravitinoEnv} still builds the function and semantic model operation
   * dispatchers, which take it directly.
   *
   * @return the schema operation dispatcher
   */
  SchemaOperationDispatcher schemaOperationDispatcher();

  /**
   * Returns the internal schema chain: normalize, then {@link #schemaOperationDispatcher()}.
   *
   * @return the internal schema dispatcher
   */
  @Internal
  SchemaDispatcher internalSchemaDispatcher();

  /**
   * Returns the public schema chain: event, normalize, hook, then {@link
   * #schemaOperationDispatcher()}.
   *
   * @return the public schema dispatcher
   */
  @Public
  SchemaDispatcher schemaDispatcher();

  /**
   * Returns the internal table chain: normalize, then its own operation dispatcher.
   *
   * @return the internal table dispatcher
   */
  @Internal
  TableDispatcher internalTableDispatcher();

  /**
   * Returns the public table chain: event, normalize, hook, then its own operation dispatcher.
   *
   * @return the public table dispatcher
   */
  @Public
  TableDispatcher tableDispatcher();

  /**
   * Returns the internal partition chain: normalize, then operation.
   *
   * @return the internal partition dispatcher
   */
  @Internal
  PartitionDispatcher internalPartitionDispatcher();

  /**
   * Returns the public partition chain: event, then {@link #internalPartitionDispatcher()}.
   *
   * @return the public partition dispatcher
   */
  @Public
  PartitionDispatcher partitionDispatcher();

  /**
   * Returns the internal view chain: normalize, then its own operation dispatcher.
   *
   * @return the internal view dispatcher
   */
  @Internal
  ViewDispatcher internalViewDispatcher();

  /**
   * Returns the public view chain: event, normalize, hook, then its own operation dispatcher.
   *
   * @return the public view dispatcher
   */
  @Public
  ViewDispatcher viewDispatcher();

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
