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
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lifecycle.Lifecycle;
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
import org.apache.gravitino.utils.MetadataObjectDispatchers;

/**
 * The root object graph of the Gravitino server.
 *
 * <p>The migration from {@code GravitinoEnv} to dependency injection moves one area at a time into
 * this component. Its modules build every component {@code GravitinoEnv} holds: {@link
 * ConfigModule} the id generator and the metrics system, {@link StorageModule} the entity store and
 * the secret stores, {@link LockModule} the lock manager, {@link EventModule} the event listener
 * manager, the event bus and the audit log manager, {@link CatalogModule} the catalog and metalake
 * managers and chains, {@link SchemaTableModule} the schema, table, partition and view chains,
 * {@link FilesetTopicModelModule} the fileset, topic, model, function and semantic model chains,
 * {@link GovernanceModule} the tag, policy, owner and statistic chains, {@link AuthorizationModule}
 * the access-control components, {@link JobModule} the job manager and chains, {@link
 * AuxiliaryModule} an auxiliary service manager, not used yet, and the context it hands to the
 * auxiliary services, and {@link LifecycleModule} the lifecycle that starts and stops them. The
 * authorizer, which the server installs later, reaches the graph through {@link LegacyBridge} and
 * {@link LegacyModule}, so every object is built by exactly one side. It is bound only as a {@code
 * Supplier<GravitinoAuthorizer>} read on every check, and read from outside the graph through
 * {@link AuthorizationServices#gravitinoAuthorizer()}, because it changes after the graph is built.
 *
 * <p>Each entity type has an {@link Internal} chain for infrastructure code and a {@link Public}
 * chain for REST resources. The managers and operation dispatchers at the bottom of the chains are
 * bound with a package-private qualifier, {@link Undecorated}, and not exposed here, so no consumer
 * can bypass a chain's hooks and decorators by injecting the object under it. The component builds
 * nothing until it is asked: {@code GravitinoEnv} asks for each object at the point of its
 * initialization sequence where it used to build it.
 *
 * <p>Singletons are per component: each component instance builds its own objects, each one the
 * first time it is requested.
 *
 * <p>The modules are listed once, in {@link GravitinoModules}. A downstream root component extends
 * this interface and is built from {@link GravitinoModules} and its own modules. Those modules
 * change a chain by contributing a {@link DispatcherDecorator} for it, and add services to the
 * {@link #lifecycle()} by contributing a {@link ManagedService}; upstream contributes neither, so
 * this component's chains and lifecycle are exactly what its modules build. {@code GravitinoEnv}
 * takes its objects from the component that the factory it is initialized with creates.
 */
@Singleton
@Component(modules = GravitinoModules.class)
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
   * Returns the tree lock manager owned by this component. The {@link #lifecycle()} closes it,
   * which stops its background threads.
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
   * Returns the KMS client registry. The {@link #lifecycle()} closes it.
   *
   * @return the KMS client registry
   */
  KmsClientRegistry kmsClientRegistry();

  /**
   * Returns the secret manager. The {@link #lifecycle()} closes it.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the event listener manager, with the listeners named by the configuration loaded. The
   * {@link #lifecycle()} starts and stops it.
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
   * Returns the internal schema chain: normalize, then the schema operation dispatcher that the
   * internal and public schema chains share. The function and semantic model operation dispatchers
   * also work on schemas through it.
   *
   * @return the internal schema dispatcher
   */
  @Internal
  SchemaDispatcher internalSchemaDispatcher();

  /**
   * Returns the public schema chain: event, normalize, hook, then the shared schema operation
   * dispatcher.
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
   * Returns the internal fileset chain: normalize, then operation.
   *
   * @return the internal fileset dispatcher
   */
  @Internal
  FilesetDispatcher internalFilesetDispatcher();

  /**
   * Returns the public fileset chain: event, normalize, hook, then the same operation dispatcher.
   *
   * @return the public fileset dispatcher
   */
  @Public
  FilesetDispatcher filesetDispatcher();

  /**
   * Returns the internal topic chain: normalize, then operation.
   *
   * @return the internal topic dispatcher
   */
  @Internal
  TopicDispatcher internalTopicDispatcher();

  /**
   * Returns the public topic chain: event, normalize, hook, then the same operation dispatcher.
   *
   * @return the public topic dispatcher
   */
  @Public
  TopicDispatcher topicDispatcher();

  /**
   * Returns the internal model chain: normalize, then operation.
   *
   * @return the internal model dispatcher
   */
  @Internal
  ModelDispatcher internalModelDispatcher();

  /**
   * Returns the public model chain: event, normalize, hook, then the same operation dispatcher.
   *
   * @return the public model dispatcher
   */
  @Public
  ModelDispatcher modelDispatcher();

  /**
   * Returns the internal function chain: normalize, then operation.
   *
   * @return the internal function dispatcher
   */
  @Internal
  FunctionDispatcher internalFunctionDispatcher();

  /**
   * Returns the public function chain: event, normalize, hook, then the same operation dispatcher.
   *
   * @return the public function dispatcher
   */
  @Public
  FunctionDispatcher functionDispatcher();

  /**
   * Returns the internal semantic model chain: normalize, then operation.
   *
   * @return the internal semantic model dispatcher
   */
  @Internal
  SemanticModelDispatcher internalSemanticModelDispatcher();

  /**
   * Returns the public semantic model chain: event, normalize, hook, then its own operation
   * dispatcher.
   *
   * @return the public semantic model dispatcher
   */
  @Public
  SemanticModelDispatcher semanticModelDispatcher();

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
   * Returns the services that keep authorization in step with metadata changes. Building them
   * builds nothing: each service is resolved when it is asked for, and the access control, owner
   * and future grant services are null while authorization is disabled.
   *
   * @return the authorization services
   */
  AuthorizationServices authorizationServices();

  /**
   * Returns the internal dispatchers asked whether a metadata object exists. Building them builds
   * nothing: each dispatcher is resolved when it is asked for.
   *
   * @return the metadata object dispatchers
   */
  MetadataObjectDispatchers metadataObjectDispatchers();

  /**
   * Returns the internal job chain: template validation, then the job manager.
   *
   * @return the internal job dispatcher
   */
  @Internal
  JobOperationDispatcher internalJobOperationDispatcher();

  /**
   * Returns the public job chain: event, hook, then the {@link #internalJobOperationDispatcher()}
   * validation.
   *
   * @return the public job dispatcher
   */
  @Public
  JobOperationDispatcher jobOperationDispatcher();

  /**
   * Returns the listener that registers the built-in job templates of new metalakes. {@code
   * GravitinoEnv} adds it to the {@link #eventListenerManager()}.
   *
   * @return the built-in job template listener
   */
  BuiltInJobTemplateEventListener builtInJobTemplateEventListener();

  /**
   * Returns the host services the auxiliary services may use. Building it builds nothing: each
   * service is resolved when an auxiliary service asks for it.
   *
   * @return the auxiliary context
   */
  AuxiliaryContext auxiliaryContext();

  /**
   * Returns the auxiliary service manager, with the services the configuration names loaded and
   * initialized with the {@link #auxiliaryContext()}. The {@link #lifecycle()} starts and stops
   * them.
   *
   * @return the auxiliary service manager
   */
  AuxiliaryServiceManager auxiliaryServiceManager();

  /**
   * Returns the lifecycle that starts and stops the services this component has built, in the order
   * {@code GravitinoEnv} has always used. See {@link LifecycleModule}.
   *
   * @return the lifecycle
   */
  Lifecycle lifecycle();

  /** Creates {@link GravitinoComponent} instances. */
  @Component.Factory
  interface Factory {

    /**
     * Creates a component.
     *
     * @param config the server configuration, the only leaf the graph does not build itself
     * @param legacy the source of the objects the graph does not build; removed when the migration
     *     is complete
     * @return a new component
     */
    GravitinoComponent create(@BindsInstance Config config, @BindsInstance LegacyBridge legacy);
  }
}
