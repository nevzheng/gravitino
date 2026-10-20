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
import com.google.errorprone.annotations.RestrictedApi;
import javax.annotation.Nullable;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.audit.AuditLogManager;
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
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.FunctionNormalizeDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.SemanticModelNormalizeDispatcher;
import org.apache.gravitino.catalog.SemanticModelOperationDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.TopicNormalizeDispatcher;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.di.LegacyBridge;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.hook.FilesetHookDispatcher;
import org.apache.gravitino.hook.FunctionHookDispatcher;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.hook.MetalakeHookDispatcher;
import org.apache.gravitino.hook.ModelHookDispatcher;
import org.apache.gravitino.hook.SemanticModelHookDispatcher;
import org.apache.gravitino.hook.TopicHookDispatcher;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.listener.FilesetEventDispatcher;
import org.apache.gravitino.listener.FunctionEventDispatcher;
import org.apache.gravitino.listener.JobEventDispatcher;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.listener.ModelEventDispatcher;
import org.apache.gravitino.listener.SemanticModelEventDispatcher;
import org.apache.gravitino.listener.TopicEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
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
 * initialization sequence where it used to build them, so they are still built in the same order;
 * everything else is still built here and handed to the component through LegacyBridge.
 */
public class GravitinoEnv implements LegacyBridge {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoEnv.class);

  private Config config;
  private GravitinoComponent component;
  // Iceberg REST server use base components while Gravitino Server use full components.
  private boolean manageFullComponents = true;

  private EntityStore entityStore;

  private CatalogDispatcher catalogDispatcher;
  private CatalogDispatcher internalCatalogDispatcher;

  private CatalogManager catalogManager;

  private MetalakeManager metalakeManager;

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
   * <p>This is a legacy service locator. Error Prone rejects new production callers; take the
   * dependency as a constructor parameter instead. Existing callers are marked with {@link
   * LegacyEnvAccess} until they are migrated. Test sources are allowed.
   *
   * @return The singleton instance of the GravitinoEnv.
   */
  @RestrictedApi(
      explanation =
          "GravitinoEnv.getInstance() is a legacy service locator. Inject the dependency through"
              + " the constructor instead. Only call sites marked @LegacyEnvAccess may use it.",
      link = "https://github.com/apache/gravitino/tree/main/dev/di",
      allowedOnPath = ".*/src/test/.*",
      allowlistAnnotations = {LegacyEnvAccess.class})
  public static GravitinoEnv getInstance() {
    return InstanceHolder.INSTANCE;
  }

  /**
   * Initialize base components, used for Iceberg REST server.
   *
   * @param config The configuration object to initialize the environment.
   */
  public void initializeBaseComponents(Config config) {
    LOG.info("Initializing Gravitino base environment...");
    initializeConfig(config);
    this.manageFullComponents = false;
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
    Preconditions.checkState(
        this == InstanceHolder.INSTANCE,
        "Metadata components must be initialized on GravitinoEnv.getInstance().");
    LOG.info("Initializing Gravitino metadata environment...");
    initializeConfig(config);
    this.manageFullComponents = false;
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
    LOG.info("Initializing Gravitino full environment...");
    initializeConfig(config);
    this.manageFullComponents = true;
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
   * <p>The component owns the configuration, the id generator, the metrics system, the lock
   * manager, the schema, table, partition and view dispatchers, the tag, policy, owner and
   * statistic dispatchers, and the access-control components; this environment returns the same
   * instances from its own getters.
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
  @Override
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
  @Override
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
  @Override
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
  @Override
  public CatalogManager catalogManager() {
    Preconditions.checkArgument(catalogManager != null, "GravitinoEnv is not initialized.");
    return catalogManager;
  }

  /**
   * Get the EventBus associated with the Gravitino environment.
   *
   * @return The EventBus instance.
   */
  @Override
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
  @Override
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

  public void start() {
    metricsSystem.start();
    if (eventListenerManager != null) {
      eventListenerManager.start();
    }
    if (manageFullComponents) {
      auxServiceManager.serviceStart();
    }
  }

  /** Shutdown the Gravitino environment. */
  public void shutdown() {
    LOG.info("Shutting down Gravitino Environment...");

    if (entityStore != null) {
      try {
        entityStore.close();
      } catch (Exception e) {
        LOG.warn("Failed to close EntityStore.", e);
      }
    }

    if (catalogManager != null) {
      catalogManager.close();
    }

    if (auxServiceManager != null) {
      try {
        auxServiceManager.serviceStop();
      } catch (Exception e) {
        LOG.warn("Failed to stop AuxServiceManager", e);
      }
    }

    if (metricsSystem != null) {
      metricsSystem.close();
    }

    if (eventListenerManager != null) {
      eventListenerManager.stop();
    }

    if (metalakeManager != null) {
      metalakeManager.close();
    }

    if (jobOperationDispatcher != null) {
      try {
        jobOperationDispatcher.close();
        jobOperationDispatcher = null;
      } catch (Exception e) {
        LOG.warn("Failed to close JobOperationDispatcher", e);
      }
    }

    StatisticDispatcher statisticDispatcherToClose =
        statisticDispatcher != null ? statisticDispatcher : internalStatisticDispatcher;
    if (statisticDispatcherToClose != null) {
      try {
        statisticDispatcherToClose.close();
      } catch (Exception e) {
        LOG.warn("Failed to close StatisticDispatcher", e);
      }
    }

    if (kmsClientRegistry != null) {
      kmsClientRegistry.close();
    }

    if (secretManager != null) {
      secretManager.close();
    }

    LOG.info("Gravitino Environment is shut down.");
  }

  private void initBaseComponents() {
    initCommonComponents();

    this.eventListenerManager = new EventListenerManager();
    eventListenerManager.init(
        config.getConfigsWithPrefix(EventListenerManager.GRAVITINO_EVENT_LISTENER_PREFIX));
    this.eventBus = eventListenerManager.createEventBus();

    AuditLogManager auditLogManager = new AuditLogManager();
    auditLogManager.init(config, eventListenerManager);
  }

  private void initializeConfig(Config config) {
    this.config = config;
    // Building the component creates nothing yet: each object it owns is built on first request.
    this.component = DaggerGravitinoComponent.factory().create(config, this);
    FileFetcher.get().initialize(config.get(Configs.BLOCK_UNSAFE_REMOTE_URI));
    SecretPropertyUtils.configureSensitiveKeyKeywords(config);
  }

  private void initCommonComponents() {
    this.kmsClientRegistry = new KmsClientRegistry(config);
    this.secretManager = new SecretManager(config);

    this.metricsSystem = component.metricsSystem();
  }

  private MetadataOperations initMetadataComponents() {
    initEntityStoreAndCatalogManager();

    this.metalakeManager =
        new MetalakeManager(entityStore, idGenerator, catalogManager, lockManager);
    this.internalMetalakeDispatcher = new MetalakeNormalizeDispatcher(metalakeManager);
    this.internalCatalogDispatcher = new CatalogNormalizeDispatcher(catalogManager);

    this.credentialOperationDispatcher =
        new CredentialOperationDispatcher(catalogManager, entityStore, idGenerator, secretManager);
    this.secretPropertyOperationDispatcher =
        new SecretPropertyOperationDispatcher(
            catalogManager, entityStore, idGenerator, secretManager);

    // Fileset dispatcher is created before schema dispatcher so schema can take it directly.
    FilesetOperationDispatcher filesetOperationDispatcher = initInternalFilesetDispatcher();
    SchemaOperationDispatcher schemaOperationDispatcher = component.schemaOperationDispatcher();
    this.internalSchemaDispatcher = component.internalSchemaDispatcher();
    this.internalTableDispatcher = component.internalTableDispatcher();
    this.internalPartitionDispatcher = component.internalPartitionDispatcher();
    TopicOperationDispatcher topicOperationDispatcher = initInternalTopicDispatcher();
    ModelOperationDispatcher modelOperationDispatcher = initInternalModelDispatcher();
    FunctionOperationDispatcher functionOperationDispatcher =
        initInternalFunctionDispatcher(schemaOperationDispatcher);
    this.internalViewDispatcher = component.internalViewDispatcher();
    initSemanticModelDispatcher(schemaOperationDispatcher);

    this.internalStatisticDispatcher = component.internalStatisticDispatcher();
    initInternalAuthorizationComponents();

    this.internalTagDispatcher = component.internalTagDispatcher();
    this.internalPolicyDispatcher = component.internalPolicyDispatcher();

    return new MetadataOperations(
        filesetOperationDispatcher,
        schemaOperationDispatcher,
        topicOperationDispatcher,
        modelOperationDispatcher,
        functionOperationDispatcher);
  }

  private FilesetOperationDispatcher initInternalFilesetDispatcher() {
    FilesetOperationDispatcher filesetOperationDispatcher =
        new FilesetOperationDispatcher(
            catalogManager, entityStore, idGenerator, secretManager, lockManager);
    this.internalFilesetDispatcher =
        new FilesetNormalizeDispatcher(filesetOperationDispatcher, catalogManager);
    return filesetOperationDispatcher;
  }

  private TopicOperationDispatcher initInternalTopicDispatcher() {
    TopicOperationDispatcher topicOperationDispatcher =
        new TopicOperationDispatcher(
            catalogManager, entityStore, idGenerator, secretManager, lockManager);
    this.internalTopicDispatcher =
        new TopicNormalizeDispatcher(topicOperationDispatcher, catalogManager);
    return topicOperationDispatcher;
  }

  private ModelOperationDispatcher initInternalModelDispatcher() {
    ModelOperationDispatcher modelOperationDispatcher =
        new ModelOperationDispatcher(catalogManager, entityStore, idGenerator, secretManager);
    this.internalModelDispatcher =
        new ModelNormalizeDispatcher(modelOperationDispatcher, catalogManager);
    return modelOperationDispatcher;
  }

  private FunctionOperationDispatcher initInternalFunctionDispatcher(
      SchemaOperationDispatcher schemaOperationDispatcher) {
    FunctionOperationDispatcher functionOperationDispatcher =
        new FunctionOperationDispatcher(
            catalogManager,
            schemaOperationDispatcher,
            entityStore,
            idGenerator,
            secretManager,
            lockManager);
    this.internalFunctionDispatcher =
        new FunctionNormalizeDispatcher(functionOperationDispatcher, catalogManager);
    return functionOperationDispatcher;
  }

  private void initSemanticModelDispatcher(SchemaOperationDispatcher schemaOperationDispatcher) {
    SemanticModelOperationDispatcher semanticModelOperationDispatcher =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperationDispatcher, entityStore, idGenerator, secretManager);
    this.internalSemanticModelDispatcher =
        new SemanticModelNormalizeDispatcher(semanticModelOperationDispatcher, catalogManager);
    this.semanticModelDispatcher = internalSemanticModelDispatcher;
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
    this.entityStore = EntityStoreFactory.createEntityStore(config);
    entityStore.initialize(config);

    this.idGenerator = component.idGenerator();
    this.lockManager = component.lockManager();

    // CatalogManager must be initialized before MetalakeManager so force-drop can remove child
    // catalogs through CatalogManager.dropCatalog, the same path used by FilesetCatalogOperations.
    // CatalogManager registers its own change-log listener with compatible entity stores, so no
    // external poller wiring is needed here.
    this.catalogManager =
        new CatalogManager(config, entityStore, idGenerator, secretManager, lockManager);
  }

  private void initGravitinoServerComponents() {
    MetadataOperations metadataOperations = initMetadataComponents();
    initPublicMetadataDispatchers(metadataOperations);

    this.auxServiceManager = new AuxiliaryServiceManager();
    this.auxServiceManager.serviceInit(config);

    JobManager jobManager = new JobManager(config, entityStore, idGenerator, lockManager);
    JobTemplateValidationDispatcher validationDispatcher =
        new JobTemplateValidationDispatcher(jobManager);
    this.internalJobOperationDispatcher = validationDispatcher;
    JobHookDispatcher jobHookDispatcher = new JobHookDispatcher(validationDispatcher);
    this.jobOperationDispatcher = new JobEventDispatcher(eventBus, jobHookDispatcher);

    // Register built-in job template event listener to automatically register templates
    // when metalakes are created
    BuiltInJobTemplateEventListener builtInJobTemplateListener =
        new BuiltInJobTemplateEventListener(jobManager, entityStore, idGenerator, lockManager);
    eventListenerManager.addEventListener("builtin-job-template", builtInJobTemplateListener);
  }

  private void initPublicMetadataDispatchers(MetadataOperations metadataOperations) {
    // Create and initialize metalake related modules, the operation chain is:
    // MetalakeEventDispatcher -> MetalakeNormalizeDispatcher -> MetalakeHookDispatcher ->
    // MetalakeManager
    MetalakeHookDispatcher metalakeHookDispatcher = new MetalakeHookDispatcher(metalakeManager);
    MetalakeNormalizeDispatcher metalakeNormalizeDispatcher =
        new MetalakeNormalizeDispatcher(metalakeHookDispatcher);
    this.metalakeDispatcher = new MetalakeEventDispatcher(eventBus, metalakeNormalizeDispatcher);

    // CatalogEventDispatcher -> CatalogNormalizeDispatcher -> CatalogHookDispatcher ->
    // CatalogManager
    CatalogHookDispatcher catalogHookDispatcher = new CatalogHookDispatcher(catalogManager);
    CatalogNormalizeDispatcher catalogNormalizeDispatcher =
        new CatalogNormalizeDispatcher(catalogHookDispatcher);
    this.catalogDispatcher = new CatalogEventDispatcher(eventBus, catalogNormalizeDispatcher);

    FilesetHookDispatcher filesetHookDispatcher =
        new FilesetHookDispatcher(metadataOperations.filesetOperationDispatcher);
    FilesetNormalizeDispatcher filesetNormalizeDispatcher =
        new FilesetNormalizeDispatcher(filesetHookDispatcher, catalogManager);
    this.filesetDispatcher = new FilesetEventDispatcher(eventBus, filesetNormalizeDispatcher);

    // Schema, table and partition chains: see SchemaTableModule. Partitions have no ownership, so
    // their public chain wraps the internal one without a hook.
    this.schemaDispatcher = component.schemaDispatcher();
    this.tableDispatcher = component.tableDispatcher();
    this.partitionDispatcher = component.partitionDispatcher();

    TopicHookDispatcher topicHookDispatcher =
        new TopicHookDispatcher(metadataOperations.topicOperationDispatcher);
    TopicNormalizeDispatcher topicNormalizeDispatcher =
        new TopicNormalizeDispatcher(topicHookDispatcher, catalogManager);
    this.topicDispatcher = new TopicEventDispatcher(eventBus, topicNormalizeDispatcher);

    ModelHookDispatcher modelHookDispatcher =
        new ModelHookDispatcher(metadataOperations.modelOperationDispatcher);
    ModelNormalizeDispatcher modelNormalizeDispatcher =
        new ModelNormalizeDispatcher(modelHookDispatcher, catalogManager);
    this.modelDispatcher = new ModelEventDispatcher(eventBus, modelNormalizeDispatcher);

    // Create and initialize Function related modules, the operation chain is:
    // FunctionEventDispatcher -> FunctionNormalizeDispatcher -> FunctionHookDispatcher ->
    // FunctionOperationDispatcher
    FunctionHookDispatcher functionHookDispatcher =
        new FunctionHookDispatcher(
            metadataOperations.functionOperationDispatcher, this::internalOwnerDispatcher);
    FunctionNormalizeDispatcher functionNormalizeDispatcher =
        new FunctionNormalizeDispatcher(functionHookDispatcher, catalogManager);
    this.functionDispatcher = new FunctionEventDispatcher(eventBus, functionNormalizeDispatcher);

    this.viewDispatcher = component.viewDispatcher();

    // Semantic Model operation chain: Event -> Normalize -> Hook -> Operation.
    SemanticModelOperationDispatcher semanticModelOperationDispatcher =
        new SemanticModelOperationDispatcher(
            catalogManager,
            metadataOperations.schemaOperationDispatcher,
            entityStore,
            idGenerator,
            secretManager);
    SemanticModelHookDispatcher semanticModelHookDispatcher =
        new SemanticModelHookDispatcher(
            semanticModelOperationDispatcher, this::internalOwnerDispatcher);
    SemanticModelNormalizeDispatcher semanticModelNormalizeDispatcher =
        new SemanticModelNormalizeDispatcher(semanticModelHookDispatcher, catalogManager);
    this.semanticModelDispatcher =
        new SemanticModelEventDispatcher(eventBus, semanticModelNormalizeDispatcher);

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

  private static final class MetadataOperations {
    private final FilesetOperationDispatcher filesetOperationDispatcher;
    private final SchemaOperationDispatcher schemaOperationDispatcher;
    private final TopicOperationDispatcher topicOperationDispatcher;
    private final ModelOperationDispatcher modelOperationDispatcher;
    private final FunctionOperationDispatcher functionOperationDispatcher;

    private MetadataOperations(
        FilesetOperationDispatcher filesetOperationDispatcher,
        SchemaOperationDispatcher schemaOperationDispatcher,
        TopicOperationDispatcher topicOperationDispatcher,
        ModelOperationDispatcher modelOperationDispatcher,
        FunctionOperationDispatcher functionOperationDispatcher) {
      this.filesetOperationDispatcher = filesetOperationDispatcher;
      this.schemaOperationDispatcher = schemaOperationDispatcher;
      this.topicOperationDispatcher = topicOperationDispatcher;
      this.modelOperationDispatcher = modelOperationDispatcher;
      this.functionOperationDispatcher = functionOperationDispatcher;
    }
  }
}
