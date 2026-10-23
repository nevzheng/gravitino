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
package org.apache.gravitino;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.DenyAllGravitinoAuthorizer;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerDispatcher;
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
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretPropertyUtils;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.FileFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This class manages the Apache Gravitino environment.
 *
 * It is the legacy facade of the dependency-injection migration. It builds a GravitinoComponent on
 * every initialization and takes the objects the component owns from it, at the point of the
 * initialization sequence where it used to build them, so they are still built in the same order.
 * The component's {@link ManagedServices} registry starts and closes what the component built,
 * the auxiliary service manager included. The server still installs the authorizer here.
 */
public class GravitinoEnv {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoEnv.class);

  private Config config;
  private GravitinoComponent component;

  private EntityStore entityStore;

  private CatalogDispatcher catalogDispatcher;
  private CatalogDispatcher internalCatalogDispatcher;

  private CatalogManager catalogManager;

  private SchemaDispatcher schemaDispatcher;
  private SchemaDispatcher internalSchemaDispatcher;

  private TableDispatcher tableDispatcher;
  private TableDispatcher internalTableDispatcher;

  private PartitionDispatcher partitionDispatcher;
  private PartitionDispatcher internalPartitionDispatcher;

  private FilesetDispatcher filesetDispatcher;

  private FilesetDispatcher internalFilesetDispatcher;

  private TopicDispatcher topicDispatcher;

  private TopicDispatcher internalTopicDispatcher;

  private ModelDispatcher modelDispatcher;
  private ModelDispatcher internalModelDispatcher;

  private FunctionDispatcher functionDispatcher;
  private FunctionDispatcher internalFunctionDispatcher;

  private SemanticModelDispatcher semanticModelDispatcher;
  private SemanticModelDispatcher internalSemanticModelDispatcher;

  private ViewDispatcher viewDispatcher;
  private ViewDispatcher internalViewDispatcher;

  private MetalakeDispatcher metalakeDispatcher;
  private MetalakeDispatcher internalMetalakeDispatcher;

  private CredentialOperationDispatcher credentialOperationDispatcher;

  private SecretPropertyOperationDispatcher secretPropertyOperationDispatcher;

  private KmsClientRegistry kmsClientRegistry;

  private SecretManager secretManager;

  private TagDispatcher tagDispatcher;
  private TagDispatcher internalTagDispatcher;

  private PolicyDispatcher policyDispatcher;
  private PolicyDispatcher internalPolicyDispatcher;

  private AccessControlDispatcher accessControlDispatcher;
  private AccessControlDispatcher internalAccessControlDispatcher;

  private IdGenerator idGenerator;

  private AuxiliaryServiceManager auxServiceManager;

  private MetricsSystem metricsSystem;

  private LockManager lockManager;

  private EventListenerManager eventListenerManager;

  private JobOperationDispatcher jobOperationDispatcher;
  private JobOperationDispatcher internalJobOperationDispatcher;

  private EventBus eventBus;
  private OwnerDispatcher ownerDispatcher;
  private OwnerDispatcher internalOwnerDispatcher;
  private BulkManager bulkManager;
  private FutureGrantManager futureGrantManager;
  private GravitinoAuthorizer gravitinoAuthorizer;
  private StatisticDispatcher statisticDispatcher;
  private StatisticDispatcher internalStatisticDispatcher;

  protected GravitinoEnv() {}

  private static class InstanceHolder {
    private static final GravitinoEnv INSTANCE = new GravitinoEnv();
  }

  /**
   * Get the singleton instance of the GravitinoEnv.
   *
   * @return The singleton instance of the GravitinoEnv.
   */
  public static GravitinoEnv getInstance() {
    return InstanceHolder.INSTANCE;
  }

  /**
   * Initialize base components, used for Iceberg REST server.
   *
   * @param config The configuration object to initialize the environment.
   */
  public void initializeBaseComponents(Config config) {
    initializeBaseComponents(config, DaggerGravitinoComponent.factory());
  }

  /**
   * Initialize base components from the component a factory creates, used for Iceberg REST server.
   *
   * <p>A downstream distribution passes the factory of its own root component, so a standalone
   * server built on the base profile gets the decorators and managed services of that component.
   *
   * @param config The configuration object to initialize the environment.
   * @param componentFactory Creates the component the environment takes its objects from: the
   *     factory of {@link GravitinoComponent}, or of a downstream root component that extends it.
   */
  public void initializeBaseComponents(Config config, GravitinoComponent.Factory componentFactory) {
    LOG.info("Initializing Gravitino base environment...");
    initializeConfig(config, componentFactory);
    initBaseComponents();
    LOG.info("Gravitino base environment is initialized.");
  }

  /**
   * Initializes components required for normalized metadata operations.
   *
   * <p>This initialization profile does not initialize event listeners, audit logging, metadata
   * hooks, auxiliary services, or job management.
   *
   * <p>This method must be called on {@link #getInstance()}. Some metadata components read their
   * dependencies directly from that singleton instead of from the object being initialized.
   *
   * @param config The configuration object to initialize the environment.
   */
  public void initializeMetadataComponents(Config config) {
    initializeMetadataComponents(config, DaggerGravitinoComponent.factory());
  }

  /**
   * Initializes components required for normalized metadata operations from the component a factory
   * creates.
   *
   * <p>A downstream distribution passes the factory of its own root component, so the internal
   * chains of this profile are the ones its decorators changed. The profile is otherwise the same
   * as {@link #initializeMetadataComponents(Config)}, and must also be initialized on {@link
   * #getInstance()}.
   *
   * @param config The configuration object to initialize the environment.
   * @param componentFactory Creates the component the environment takes its objects from: the
   *     factory of {@link GravitinoComponent}, or of a downstream root component that extends it.
   */
  public void initializeMetadataComponents(
      Config config, GravitinoComponent.Factory componentFactory) {
    Preconditions.checkState(
        this == getInstance(),
        "Metadata components must be initialized on GravitinoEnv.getInstance().");
    LOG.info("Initializing Gravitino metadata environment...");
    initializeConfig(config, componentFactory);
    initCommonComponents();
    initMetadataComponents();
    LOG.info("Gravitino metadata environment is initialized.");
  }

  /**
   * Initialize all components, used for Gravitino server.
   *
   * @param config The configuration object to initialize the environment.
   */
  public void initializeFullComponents(Config config) {
    initializeFullComponents(config, DaggerGravitinoComponent.factory());
  }

  /**
   * Initialize all components from the component a factory creates, used for Gravitino server.
   *
   * <p>A downstream distribution passes the factory of its own root component, which is built from
   * {@code GravitinoModules} and its own modules. The getters of this environment then return the
   * objects of that component, including the dispatcher chains its decorators changed.
   *
   * @param config The configuration object to initialize the environment.
   * @param componentFactory Creates the component the environment takes its objects from: the
   *     factory of {@link GravitinoComponent}, or of a downstream root component that extends it.
   */
  public void initializeFullComponents(Config config, GravitinoComponent.Factory componentFactory) {
    LOG.info("Initializing Gravitino full environment...");
    initializeConfig(config, componentFactory);
    initBaseComponents();
    initGravitinoServerComponents();
    LOG.info("Gravitino full environment is initialized.");
  }

  /**
   * Get the configuration object associated with the Gravitino environment.
   *
   * @return The configuration object.
   */
  public Config config() {
    return config;
  }

  /**
   * Get the dependency-injection component built by the last initialization.
   *
   * <p>The component owns every component this environment holds except the installed authorizer;
   * this environment returns the same instances from its own getters.
   *
   * @return The component, or null if the environment is not initialized.
   */
  @Nullable
  public GravitinoComponent component() {
    return component;
  }

  /**
   * Get the auxiliary service manager associated with the Gravitino environment.
   *
   * @return The auxiliary service manager instance.
   */
  public AuxiliaryServiceManager auxServiceManager() {
    return auxServiceManager;
  }

  /**
   * Get the EntityStore associated with the Gravitino environment.
   *
   * @return The EntityStore instance.
   */
  public EntityStore entityStore() {
    Preconditions.checkArgument(entityStore != null, "GravitinoEnv is not initialized.");
    return entityStore;
  }

  /**
   * Get the CatalogDispatcher associated with the Gravitino environment.
   *
   * @return The CatalogDispatcher instance.
   */
  public CatalogDispatcher catalogDispatcher() {
    return catalogDispatcher;
  }

  /**
   * Get the internal CatalogDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission. It is
   * intended for infrastructure catalog lookups that should not be recorded as user API audit
   * events.
   *
   * @return The internal CatalogDispatcher instance.
   */
  public CatalogDispatcher internalCatalogDispatcher() {
    return internalCatalogDispatcher;
  }

  /**
   * Get the SchemaDispatcher associated with the Gravitino environment.
   *
   * @return The SchemaDispatcher instance.
   */
  public SchemaDispatcher schemaDispatcher() {
    return schemaDispatcher;
  }

  /**
   * Get the internal SchemaDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission. It is
   * intended for infrastructure code that synchronizes metadata as part of another user-visible
   * operation.
   *
   * @return The internal SchemaDispatcher instance.
   */
  public SchemaDispatcher internalSchemaDispatcher() {
    return internalSchemaDispatcher;
  }

  /**
   * Get the TableDispatcher associated with the Gravitino environment.
   *
   * @return The TableDispatcher instance.
   */
  public TableDispatcher tableDispatcher() {
    return tableDispatcher;
  }

  /**
   * Get the internal TableDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission. It is
   * intended for infrastructure code that synchronizes metadata as part of another user-visible
   * operation.
   *
   * @return The internal TableDispatcher instance.
   */
  public TableDispatcher internalTableDispatcher() {
    return internalTableDispatcher;
  }

  /**
   * Get the ModelDispatcher associated with the Gravitino environment.
   *
   * @return The ModelDispatcher instance.
   */
  public ModelDispatcher modelDispatcher() {
    return modelDispatcher;
  }

  /**
   * Get the internal ModelDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission.
   *
   * @return The internal ModelDispatcher instance.
   */
  public ModelDispatcher internalModelDispatcher() {
    return internalModelDispatcher;
  }

  /**
   * Get the FunctionDispatcher associated with the Gravitino environment.
   *
   * @return The FunctionDispatcher instance.
   */
  public FunctionDispatcher functionDispatcher() {
    return functionDispatcher;
  }

  /**
   * Get the internal FunctionDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission.
   *
   * @return The internal FunctionDispatcher instance.
   */
  public FunctionDispatcher internalFunctionDispatcher() {
    return internalFunctionDispatcher;
  }

  /**
   * Get the Semantic Model dispatcher associated with the Gravitino environment.
   *
   * @return The Semantic Model dispatcher.
   */
  public SemanticModelDispatcher semanticModelDispatcher() {
    return semanticModelDispatcher;
  }

  /**
   * Returns the Semantic Model dispatcher for infrastructure operations without hooks or events.
   *
   * @return the internal Semantic Model dispatcher
   */
  public SemanticModelDispatcher internalSemanticModelDispatcher() {
    return internalSemanticModelDispatcher;
  }

  /**
   * Get the ViewDispatcher associated with the Gravitino environment.
   *
   * @return The ViewDispatcher instance.
   */
  public ViewDispatcher viewDispatcher() {
    return viewDispatcher;
  }

  /**
   * Get the internal ViewDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hook/event side effects from
   * dependent schema lookups. It is intended for infrastructure code that synchronizes metadata as
   * part of another user-visible operation.
   *
   * @return The internal ViewDispatcher instance.
   */
  public ViewDispatcher internalViewDispatcher() {
    return internalViewDispatcher;
  }

  /**
   * * Get the PartitionDispatcher associated with the Gravitino environment.
   *
   * @return The PartitionDispatcher instance.
   */
  public PartitionDispatcher partitionDispatcher() {
    return partitionDispatcher;
  }

  /**
   * Get the internal PartitionDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips event emission.
   *
   * @return The internal PartitionDispatcher instance.
   */
  public PartitionDispatcher internalPartitionDispatcher() {
    Preconditions.checkArgument(
        internalPartitionDispatcher != null, "GravitinoEnv is not initialized.");
    return internalPartitionDispatcher;
  }

  /**
   * Get the FilesetDispatcher associated with the Gravitino environment.
   *
   * @return The FilesetDispatcher instance.
   */
  public FilesetDispatcher filesetDispatcher() {
    return filesetDispatcher;
  }

  /**
   * Get the internal FilesetDispatcher associated with the Gravitino environment.
   *
   * @return The internal FilesetDispatcher instance.
   */
  public FilesetDispatcher internalFilesetDispatcher() {
    Preconditions.checkArgument(
        internalFilesetDispatcher != null, "GravitinoEnv is not initialized.");
    return internalFilesetDispatcher;
  }

  /**
   * Get the TopicDispatcher associated with the Gravitino environment.
   *
   * @return The TopicDispatcher instance.
   */
  public TopicDispatcher topicDispatcher() {
    return topicDispatcher;
  }

  /**
   * Get the internal TopicDispatcher associated with the Gravitino environment.
   *
   * @return The internal TopicDispatcher instance.
   */
  public TopicDispatcher internalTopicDispatcher() {
    Preconditions.checkArgument(
        internalTopicDispatcher != null, "GravitinoEnv is not initialized.");
    return internalTopicDispatcher;
  }

  /**
   * Get the MetalakeDispatcher associated with the Gravitino environment.
   *
   * @return The MetalakeDispatcher instance.
   */
  public MetalakeDispatcher metalakeDispatcher() {
    return metalakeDispatcher;
  }

  /**
   * Get the internal MetalakeDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves normalization but skips hooks and event emission.
   *
   * @return The internal MetalakeDispatcher instance.
   */
  public MetalakeDispatcher internalMetalakeDispatcher() {
    return internalMetalakeDispatcher;
  }

  /**
   * Get the {@link CredentialOperationDispatcher} associated with the Gravitino environment.
   *
   * @return The {@link CredentialOperationDispatcher} instance.
   */
  public CredentialOperationDispatcher credentialOperationDispatcher() {
    return credentialOperationDispatcher;
  }

  /**
   * Get the {@link SecretPropertyOperationDispatcher} associated with the Gravitino environment.
   *
   * @return The {@link SecretPropertyOperationDispatcher} instance.
   */
  public SecretPropertyOperationDispatcher secretPropertyOperationDispatcher() {
    return secretPropertyOperationDispatcher;
  }

  /**
   * Get the metadata-only KMS client registry associated with the Gravitino environment.
   *
   * <p>The environment owns this registry. Callers may inject it into dependent components but must
   * not close it.
   *
   * @return The KMS client registry instance.
   * @throws IllegalStateException if the environment has not been initialized
   */
  public KmsClientRegistry kmsClientRegistry() {
    Preconditions.checkState(
        kmsClientRegistry != null, "GravitinoEnv components are not initialized.");
    return kmsClientRegistry;
  }

  /**
   * Get the {@link SecretManager} associated with the Gravitino environment.
   *
   * @return The SecretManager instance.
   * @throws IllegalStateException if the environment has not been initialized
   */
  public SecretManager secretManager() {
    Preconditions.checkState(secretManager != null, "GravitinoEnv components are not initialized.");
    return secretManager;
  }

  /**
   * Get the secrets-provider registry associated with the Gravitino environment.
   *
   * <p>Owned by {@link #secretManager()}. Callers may use it for discovery but must not close it.
   *
   * @return The secrets-provider registry instance.
   * @throws IllegalStateException if the environment has not been initialized
   */
  public SecretProviderRegistry secretProviderRegistry() {
    return secretManager().getRegistry();
  }

  /**
   * Get the IdGenerator associated with the Gravitino environment.
   *
   * @return The IdGenerator instance.
   */
  public IdGenerator idGenerator() {
    return idGenerator;
  }

  /**
   * Get the CatalogManager associated with the Gravitino environment.
   *
   * @return The CatalogManager instance.
   */
  public CatalogManager catalogManager() {
    Preconditions.checkArgument(catalogManager != null, "GravitinoEnv is not initialized.");
    return catalogManager;
  }

  /**
   * Get the EventBus associated with the Gravitino environment.
   *
   * @return The EventBus instance.
   */
  public EventBus eventBus() {
    Preconditions.checkArgument(eventBus != null, "GravitinoEnv is not initialized.");
    return eventBus;
  }

  /**
   * Get the MetricsSystem associated with the Gravitino environment.
   *
   * @return The MetricsSystem instance.
   */
  public MetricsSystem metricsSystem() {
    return metricsSystem;
  }

  /**
   * Get the LockManager associated with the Gravitino environment.
   *
   * <p>The lock manager is built by {@link #component()}; this returns the same instance.
   *
   * @return The LockManager instance.
   */
  public LockManager lockManager() {
    return lockManager;
  }

  /**
   * Get the AccessControlDispatcher associated with the Gravitino environment.
   *
   * @return The AccessControlDispatcher instance.
   */
  public AccessControlDispatcher accessControlDispatcher() {
    return accessControlDispatcher;
  }

  /**
   * Get the internal AccessControlDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher skips hooks and event emission. It is intended for authorization
   * infrastructure lookups that should not be recorded as user API audit events.
   *
   * @return The internal AccessControlDispatcher instance.
   */
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return internalAccessControlDispatcher;
  }

  /**
   * Get the BulkManager associated with the Gravitino environment.
   *
   * @return The BulkManager instance.
   */
  public BulkManager bulkManager() {
    return bulkManager;
  }

  /**
   * Get the tagDispatcher associated with the Gravitino environment.
   *
   * @return The tagDispatcher instance.
   */
  public TagDispatcher tagDispatcher() {
    return tagDispatcher;
  }

  /**
   * Get the internal TagDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher skips hooks and event emission.
   *
   * @return The internal TagDispatcher instance.
   */
  public TagDispatcher internalTagDispatcher() {
    return internalTagDispatcher;
  }

  /**
   * Get the PolicyDispatcher associated with the Gravitino environment.
   *
   * @return The PolicyDispatcher instance.
   */
  public PolicyDispatcher policyDispatcher() {
    return policyDispatcher;
  }

  /**
   * Get the internal PolicyDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher skips hooks and event emission.
   *
   * @return The internal PolicyDispatcher instance.
   */
  public PolicyDispatcher internalPolicyDispatcher() {
    return internalPolicyDispatcher;
  }

  /**
   * Get the Owner dispatcher associated with the Gravitino environment.
   *
   * @return The OwnerManager instance.
   */
  public OwnerDispatcher ownerDispatcher() {
    return ownerDispatcher;
  }

  /**
   * Get the internal OwnerDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher skips event emission. It is intended for infrastructure ownership
   * synchronization that happens as part of another user-visible operation.
   *
   * @return The internal OwnerDispatcher instance.
   */
  public OwnerDispatcher internalOwnerDispatcher() {
    return internalOwnerDispatcher;
  }

  /**
   * Get the FutureGrantManager associated with the Gravitino environment.
   *
   * @return The FutureGrantManager instance.
   */
  public FutureGrantManager futureGrantManager() {
    return futureGrantManager;
  }

  /**
   * Get the EventListenerManager associated with the Gravitino environment.
   *
   * @return The EventListenerManager instance.
   */
  public EventListenerManager eventListenerManager() {
    return eventListenerManager;
  }

  /**
   * Installs the GravitinoAuthorizer. The server installs its authorizer after the environment is
   * initialized.
   *
   * @param gravitinoAuthorizer the GravitinoAuthorizer instance, or null to uninstall it
   */
  public void setGravitinoAuthorizer(@Nullable GravitinoAuthorizer gravitinoAuthorizer) {
    this.gravitinoAuthorizer = gravitinoAuthorizer;
  }

  /**
   * Get the GravitinoAuthorizer.
   *
   * <p>When none is installed, it fails closed if authorization is enabled: the server installs its
   * authorizer after the environment is initialized and takes it back while it shuts down, and a
   * request that runs in between must not be allowed.
   *
   * @return the installed GravitinoAuthorizer; when none is installed, {@link
   *     DenyAllGravitinoAuthorizer#INSTANCE} if authorization is enabled, and {@link
   *     NoopGravitinoAuthorizer#INSTANCE} otherwise; never null
   */
  public GravitinoAuthorizer gravitinoAuthorizer() {
    GravitinoAuthorizer installed = gravitinoAuthorizer;
    if (installed != null) {
      return installed;
    }
    Config current = config;
    return current != null && current.get(Configs.ENABLE_AUTHORIZATION)
        ? DenyAllGravitinoAuthorizer.INSTANCE
        : NoopGravitinoAuthorizer.INSTANCE;
  }

  /**
   * Get the JobOperationDispatcher associated with the Gravitino environment.
   *
   * @return The JobOperationDispatcher instance.
   */
  public JobOperationDispatcher jobOperationDispatcher() {
    Preconditions.checkArgument(jobOperationDispatcher != null, "GravitinoEnv is not initialized.");
    return jobOperationDispatcher;
  }

  /**
   * Get the internal JobOperationDispatcher associated with the Gravitino environment.
   *
   * <p>The internal dispatcher preserves validation but skips hooks and event emission.
   *
   * @return The internal JobOperationDispatcher instance.
   */
  public JobOperationDispatcher internalJobOperationDispatcher() {
    Preconditions.checkArgument(
        internalJobOperationDispatcher != null, "GravitinoEnv is not initialized.");
    return internalJobOperationDispatcher;
  }

  public StatisticDispatcher statisticDispatcher() {
    return statisticDispatcher;
  }

  /**
   * Get the internal StatisticDispatcher associated with the Gravitino environment.
   *
   * @return The internal StatisticDispatcher instance.
   */
  public StatisticDispatcher internalStatisticDispatcher() {
    Preconditions.checkArgument(
        internalStatisticDispatcher != null, "GravitinoEnv is not initialized.");
    return internalStatisticDispatcher;
  }

  public boolean cacheEnabled() {
    return config == null || config.get(Configs.CACHE_ENABLED);
  }

  /**
   * Starts the environment: the {@link ManagedServices managed services} of the component, the
   * auxiliary service manager included, in the order the component built them. The services a
   * downstream component contributes (see {@link GravitinoComponent#contributedServices()}) are
   * built and registered first, so they start with the others.
   *
   * <p>If a service fails to start, the services already started are closed and the failure is
   * rethrown; {@link #shutdown()} then closes the rest.
   *
   * @throws IllegalStateException if no initialization built a component, or the environment was
   *     shut down or failed to start since its last initialization
   */
  public void start() {
    Preconditions.checkState(
        component != null,
        "GravitinoEnv is not initialized: call an initialize*Components method before start(). A"
            + " subclass that overrides one must call the overridden method, which builds the"
            + " component.");
    ManagedServices services = component.managedServices();
    Preconditions.checkState(
        services.state() == ManagedServices.State.NEW,
        "GravitinoEnv was started, shut down or failed to start: initialize it again before"
            + " calling start() again.");
    // A downstream component's contributed services are built here, after what they depend on, so
    // they register, start and close in the same dependency order.
    component.contributedServices().forEach(services::register);
    try {
      services.start();
    } catch (Exception e) {
      throw unchecked(e);
    }
  }

  /**
   * Shutdown the Gravitino environment.
   *
   * <p>Closes every {@link ManagedServices managed service} the last initialization built, the
   * auxiliary service manager included, whether or not {@link #start()} ran, in the reverse of the
   * order the component built them. A service that fails to close does not keep the others from
   * closing; the first failure is rethrown when all of them are closed. Calling it again does
   * nothing until the environment is initialized again.
   */
  public void shutdown() {
    LOG.info("Shutting down Gravitino Environment...");

    if (component != null) {
      try {
        component.managedServices().close();
      } catch (Exception e) {
        throw unchecked(e);
      } finally {
        // The job manager is closed; the getter reports the environment as not initialized.
        this.jobOperationDispatcher = null;
      }
    }

    LOG.info("Gravitino Environment is shut down.");
  }

  private static RuntimeException unchecked(Exception e) {
    return e instanceof RuntimeException
        ? (RuntimeException) e
        : new IllegalStateException(e.getMessage(), e);
  }

  private void initBaseComponents() {
    initCommonComponents();

    // Building the bus builds the listener manager and the audit log manager first.
    this.eventBus = component.eventBus();
    this.eventListenerManager = component.eventListenerManager();
  }

  private void initializeConfig(Config config, GravitinoComponent.Factory componentFactory) {
    this.config = config;
    // Building the component creates nothing yet: each object it owns is built on first request.
    this.component =
        Preconditions.checkNotNull(
            componentFactory.create(config), "The component factory created no component");
    FileFetcher.get().initialize(config.get(Configs.BLOCK_UNSAFE_REMOTE_URI));
    SecretPropertyUtils.configureSensitiveKeyKeywords(config);
  }

  private void initCommonComponents() {
    this.kmsClientRegistry = component.kmsClientRegistry();
    this.secretManager = component.secretManager();

    this.metricsSystem = component.metricsSystem();
  }

  private void initMetadataComponents() {
    initEntityStoreAndCatalogManager();

    // Building the internal metalake chain builds the metalake manager, after the catalog manager,
    // as before; the managed services close it.
    this.internalMetalakeDispatcher = component.internalMetalakeDispatcher();
    this.internalCatalogDispatcher = component.internalCatalogDispatcher();

    this.credentialOperationDispatcher = component.credentialOperationDispatcher();
    this.secretPropertyOperationDispatcher = component.secretPropertyOperationDispatcher();

    // The fileset chain is built before the schema chain, which drops filesets through it.
    this.internalFilesetDispatcher = component.internalFilesetDispatcher();
    this.internalSchemaDispatcher = component.internalSchemaDispatcher();
    this.internalTableDispatcher = component.internalTableDispatcher();
    this.internalPartitionDispatcher = component.internalPartitionDispatcher();
    this.internalTopicDispatcher = component.internalTopicDispatcher();
    this.internalModelDispatcher = component.internalModelDispatcher();
    this.internalFunctionDispatcher = component.internalFunctionDispatcher();
    this.internalViewDispatcher = component.internalViewDispatcher();
    this.internalSemanticModelDispatcher = component.internalSemanticModelDispatcher();
    // The full profile replaces this with the public chain.
    this.semanticModelDispatcher = internalSemanticModelDispatcher;

    this.internalStatisticDispatcher = component.internalStatisticDispatcher();
    initInternalAuthorizationComponents();

    this.internalTagDispatcher = component.internalTagDispatcher();
    this.internalPolicyDispatcher = component.internalPolicyDispatcher();
  }

  private void initInternalAuthorizationComponents() {
    if (config.get(Configs.ENABLE_AUTHORIZATION)) {
      this.internalAccessControlDispatcher = component.internalAccessControlDispatcher();
      this.internalOwnerDispatcher = component.internalOwnerDispatcher();
      this.bulkManager = component.bulkManager();
      this.futureGrantManager = component.futureGrantManager();
    } else {
      this.internalAccessControlDispatcher = null;
      this.internalOwnerDispatcher = null;
      this.bulkManager = null;
      this.futureGrantManager = null;
    }
  }

  private void initEntityStoreAndCatalogManager() {
    // Building the entity store connects to its backend, so it is built before anything else of
    // the metadata profile.
    this.entityStore = component.entityStore();

    this.idGenerator = component.idGenerator();
    this.lockManager = component.lockManager();

    // CatalogManager is initialized before MetalakeManager, as before; a force drop removes the
    // child catalogs through the public catalog chain around it.
    // CatalogManager registers its own change-log listener with compatible entity stores, so no
    // external poller wiring is needed here.
    // The component exposes the manager only as its read-only CatalogAccess face, which is the
    // manager itself; this environment's catalogManager() getter still hands out the manager.
    this.catalogManager = (CatalogManager) component.catalogAccess();
  }

  private void initGravitinoServerComponents() {
    initMetadataComponents();
    initPublicMetadataDispatchers();

    // Building the manager loads and initializes the auxiliary services, after every host service
    // they may use is built. It registers here, so it starts after the services built before it.
    this.auxServiceManager = component.auxiliaryServiceManager();

    // The job manager is built after the auxiliary services are initialized, as before.
    this.internalJobOperationDispatcher = component.internalJobOperationDispatcher();
    this.jobOperationDispatcher = component.jobOperationDispatcher();

    // Register built-in job template event listener to automatically register templates
    // when metalakes are created
    eventListenerManager.addEventListener(
        "builtin-job-template", component.builtInJobTemplateEventListener());
  }

  private void initPublicMetadataDispatchers() {
    // Each public chain is event -> normalize -> hook -> operation; see the modules of
    // GravitinoComponent. Partitions have no ownership, so their public chain wraps the internal
    // one without a hook.
    this.metalakeDispatcher = component.metalakeDispatcher();
    this.catalogDispatcher = component.catalogDispatcher();
    this.filesetDispatcher = component.filesetDispatcher();
    this.schemaDispatcher = component.schemaDispatcher();
    this.tableDispatcher = component.tableDispatcher();
    this.partitionDispatcher = component.partitionDispatcher();
    this.topicDispatcher = component.topicDispatcher();
    this.modelDispatcher = component.modelDispatcher();
    this.functionDispatcher = component.functionDispatcher();
    this.viewDispatcher = component.viewDispatcher();
    this.semanticModelDispatcher = component.semanticModelDispatcher();

    this.statisticDispatcher = component.statisticDispatcher();

    // Create and initialize access control related modules
    if (internalAccessControlDispatcher != null) {
      this.accessControlDispatcher = component.accessControlDispatcher();
      this.ownerDispatcher = component.ownerDispatcher();
    } else {
      this.accessControlDispatcher = null;
      this.ownerDispatcher = null;
    }

    this.tagDispatcher = component.tagDispatcher();
    this.policyDispatcher = component.policyDispatcher();
  }
}
