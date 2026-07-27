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
package org.apache.gravitino.iceberg;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Singleton;
import javax.servlet.Servlet;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.auxiliary.GravitinoAuxiliaryService;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.IcebergAuthenticationFilter;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.IcebergExceptionMapper;
import org.apache.gravitino.iceberg.service.IcebergHealthCheckPathMatcher;
import org.apache.gravitino.iceberg.service.IcebergObjectMapperProvider;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.cleanup.IcebergCleanupJobStore;
import org.apache.gravitino.iceberg.service.cleanup.IcebergCleanupManager;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionContextStore;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionMetricsSource;
import org.apache.gravitino.iceberg.service.deletion.IcebergTableDeletionLifecycle;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeJobStore;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeManager;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceHookDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableHookDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewHookDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationExecutor;
import org.apache.gravitino.iceberg.service.metrics.IcebergMetricsManager;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProviderFactory;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.server.web.HealthAliasServlet;
import org.apache.gravitino.server.web.HttpAuditFilter;
import org.apache.gravitino.server.web.HttpServerMetricsSource;
import org.apache.gravitino.server.web.JettyServer;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.server.web.filter.IcebergRESTAuthInterceptionService;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RESTService implements GravitinoAuxiliaryService {

  private static Logger LOG = LoggerFactory.getLogger(RESTService.class);

  private JettyServer server;

  public static final String SERVICE_NAME = "iceberg-rest";
  public static final String ICEBERG_SPEC = "/iceberg/*";
  private static final String ICEBERG_REST_SPEC_PACKAGE =
      "org.apache.gravitino.iceberg.service.rest";

  @FunctionalInterface
  private interface CheckedClose {
    void run() throws Exception;
  }

  private IcebergCatalogWrapperManager icebergCatalogWrapperManager;
  private IcebergMetricsManager icebergMetricsManager;
  private Optional<IcebergCleanupManager> cleanupManager;
  private Optional<IcebergPurgeManager> purgeManager;
  private IcebergTableDeletionLifecycle deletionLifecycle;
  private IcebergConfigProvider configProvider;
  private MetricsSystem metricsSystem;
  private IcebergDeletionMetricsSource deletionMetricsSource;
  private boolean auxMode;

  private void initServer(IcebergConfig icebergConfig) {
    validateDeletionMode(icebergConfig, auxMode);
    JettyServerConfig serverConfig = JettyServerConfig.fromConfig(icebergConfig);
    server =
        new JettyServer() {
          @Override
          protected javax.servlet.Filter createAuthenticationFilter() {
            return new IcebergAuthenticationFilter();
          }
        };
    this.metricsSystem = GravitinoEnv.getInstance().metricsSystem();
    server.initialize(serverConfig, SERVICE_NAME, false /* shouldEnableUI */);

    ResourceConfig config = new ResourceConfig();
    config.packages(getIcebergRESTPackages(icebergConfig));

    config.register(IcebergObjectMapperProvider.class).register(JacksonFeature.class);
    config.register(IcebergExceptionMapper.class);
    HttpServerMetricsSource httpServerMetricsSource =
        new HttpServerMetricsSource(MetricsSource.ICEBERG_REST_SERVER_METRIC_NAME, config, server);
    metricsSystem.register(httpServerMetricsSource);

    Map<String, String> configProperties = icebergConfig.getAllConfig();
    this.configProvider = IcebergConfigProviderFactory.create(configProperties);
    configProvider.initialize(configProperties);
    String metalakeName = configProvider.getMetalakeName();
    boolean skipAuthorizationForRestBackend =
        icebergConfig.get(IcebergConfig.ICEBERG_REST_DISABLE_REST_AUTHZ);

    Boolean enableAuth = GravitinoEnv.getInstance().config().get(Configs.ENABLE_AUTHORIZATION);
    EventBus eventBus = GravitinoEnv.getInstance().eventBus();
    this.icebergCatalogWrapperManager =
        new IcebergCatalogWrapperManager(configProperties, configProvider, auxMode, metalakeName);
    IcebergRESTServerContext authorizationContext =
        IcebergRESTServerContext.create(
            configProvider,
            enableAuth,
            auxMode,
            skipAuthorizationForRestBackend,
            icebergCatalogWrapperManager);
    Optional<IcebergPurgeJobStore> purgeStore =
        auxMode
            ? Optional.of(new IcebergPurgeJobStore(GravitinoEnv.getInstance().idGenerator()))
            : Optional.empty();
    this.deletionMetricsSource =
        new IcebergDeletionMetricsSource(
            () ->
                purgeStore
                    .map(store -> store.countEligibleActions(System.currentTimeMillis()))
                    .orElse(0L));
    this.deletionLifecycle =
        new IcebergTableDeletionLifecycle(
            icebergCatalogWrapperManager, icebergConfig, auxMode, deletionMetricsSource);
    Optional<IcebergTableDeletionLifecycle> deletionLifecycleForOperations =
        auxMode ? Optional.of(deletionLifecycle) : Optional.empty();
    this.icebergMetricsManager = new IcebergMetricsManager(icebergConfig);
    this.purgeManager = Optional.empty();
    // Both modes initialize durable relational job storage. Standalone uses only this legacy job
    // lane because it is not authoritative for Gravitino table_meta and therefore cannot offer
    // retained UNDROP. Auxiliary mode additionally runs deletion-action based purge below.
    this.cleanupManager =
        Optional.of(
            new IcebergCleanupManager(
                new IcebergCleanupJobStore(GravitinoEnv.getInstance().idGenerator()),
                icebergConfig));
    if (auxMode) {
      this.purgeManager =
          Optional.of(
              new IcebergPurgeManager(
                  purgeStore.orElseThrow(),
                  new IcebergDeletionContextStore(),
                  icebergCatalogWrapperManager,
                  icebergConfig,
                  deletionMetricsSource));
    }
    // The raw namespace operation executor is shared with the table and view hook dispatchers so
    // their orphan-schema cleanup can probe namespace existence without firing namespace events.
    IcebergNamespaceOperationDispatcher namespaceOperationDispatcher =
        new IcebergNamespaceOperationExecutor(
            icebergCatalogWrapperManager, cleanupManager, deletionLifecycleForOperations);

    // Table: HookDispatcher -> EventDispatcher -> OperationExecutor
    IcebergTableOperationDispatcher icebergTableOperationDispatcher =
        new IcebergTableOperationExecutor(
            icebergCatalogWrapperManager, cleanupManager, deletionLifecycleForOperations);
    IcebergTableOperationDispatcher icebergTableEventDispatcher =
        new IcebergTableEventDispatcher(icebergTableOperationDispatcher, eventBus, metalakeName);
    if (authorizationContext.isAuthorizationEnabled() || auxMode) {
      icebergTableEventDispatcher =
          new IcebergTableHookDispatcher(
              icebergTableEventDispatcher,
              namespaceOperationDispatcher,
              authorizationContext.isAuthorizationEnabled());
    }
    IcebergTableOperationDispatcher icebergTableDispatcher = icebergTableEventDispatcher;

    // View: HookDispatcher -> EventDispatcher -> OperationExecutor
    IcebergViewOperationDispatcher icebergViewOperationDispatcher =
        new IcebergViewOperationExecutor(icebergCatalogWrapperManager);
    IcebergViewOperationDispatcher icebergViewEventDispatcher =
        new IcebergViewEventDispatcher(icebergViewOperationDispatcher, eventBus, metalakeName);
    if (authorizationContext.isAuthorizationEnabled()) {
      icebergViewEventDispatcher =
          new IcebergViewHookDispatcher(
              icebergViewEventDispatcher, namespaceOperationDispatcher, metalakeName);
    }
    IcebergViewOperationDispatcher icebergViewDispatcher = icebergViewEventDispatcher;

    // Namespace: HookDispatcher -> EventDispatcher -> OperationExecutor
    IcebergNamespaceOperationDispatcher icebergNamespaceEventDispatcher =
        new IcebergNamespaceEventDispatcher(namespaceOperationDispatcher, eventBus, metalakeName);
    if (authorizationContext.isAuthorizationEnabled()) {
      icebergNamespaceEventDispatcher =
          new IcebergNamespaceHookDispatcher(icebergNamespaceEventDispatcher);
    }
    IcebergNamespaceOperationDispatcher icebergNamespaceDispatcher =
        icebergNamespaceEventDispatcher;

    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            if (authorizationContext.isAuthorizationEnabled()) {
              bind(IcebergRESTAuthInterceptionService.class)
                  .to(InterceptionService.class)
                  .in(Singleton.class);
            }
            bind(icebergCatalogWrapperManager).to(IcebergCatalogWrapperManager.class).ranked(1);
            bind(icebergMetricsManager).to(IcebergMetricsManager.class).ranked(1);
            bind(deletionLifecycle).to(IcebergTableDeletionLifecycle.class).ranked(1);
            cleanupManager.ifPresent(
                manager -> bind(manager).to(IcebergCleanupManager.class).ranked(1));
            bind(icebergTableDispatcher).to(IcebergTableOperationDispatcher.class).ranked(1);
            bind(icebergViewDispatcher).to(IcebergViewOperationDispatcher.class).ranked(1);
            bind(icebergNamespaceDispatcher)
                .to(IcebergNamespaceOperationDispatcher.class)
                .ranked(1);
          }
        });

    Servlet servlet = new ServletContainer(config);
    server.addServlet(servlet, ICEBERG_SPEC);
    server.addFilter(
        new HttpAuditFilter(
            eventBus,
            EventSource.GRAVITINO_ICEBERG_REST_SERVER,
            new IcebergHealthCheckPathMatcher()),
        ICEBERG_SPEC);
    server.addCustomFilters(ICEBERG_SPEC);
    server.addSystemFilters(ICEBERG_SPEC);

    // Root-level aliases for health checks to improve compatibility with various monitoring
    // systems that expect a /health endpoint.
    server.addServlet(new HealthAliasServlet("/iceberg"), "/health/*");
    server.addServlet(new HealthAliasServlet("/iceberg"), "/health.html");
    metricsSystem.register(deletionMetricsSource);
  }

  static void validateDeletionMode(IcebergConfig icebergConfig, boolean auxMode) {
    if (!auxMode && icebergConfig.get(IcebergConfig.SOFT_DELETE_ENABLED)) {
      throw new IllegalArgumentException(
          "Iceberg REST soft delete requires Gravitino table_meta authority and is not supported "
              + "in standalone mode; disable gravitino.iceberg-rest.soft-delete.enabled");
    }
  }

  @Override
  public String shortName() {
    return SERVICE_NAME;
  }

  @Override
  public void serviceInit(Map<String, String> properties, boolean auxMode) {
    this.auxMode = auxMode;
    IcebergConfig icebergConfig = new IcebergConfig(properties);
    initServer(icebergConfig);
    LOG.info("Iceberg REST service init. Running in {} mode", auxMode ? "auxiliary" : "standalone");
  }

  @Override
  public void serviceStart() {
    try {
      icebergMetricsManager.start();
      cleanupManager.ifPresent(IcebergCleanupManager::start);
      purgeManager.ifPresent(IcebergPurgeManager::start);
      if (server != null) {
        server.start();
        LOG.info("Iceberg REST service started");
      }
    } catch (Exception e) {
      // Stop the components we already started so they don't outlive a failed startup.
      try {
        closeComponents();
      } catch (Exception closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw new RuntimeException(e);
    }
  }

  @Override
  public void serviceStop() throws Exception {
    Exception failure = null;
    try {
      if (server != null) {
        server.stop();
        LOG.info("Iceberg REST service stopped");
      }
    } catch (Exception e) {
      failure = e;
    }
    try {
      closeComponents();
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Returns the durable purge runtime when relational table metadata is authoritative. */
  public Optional<IcebergPurgeManager> purgeManager() {
    return purgeManager;
  }

  public void join() {
    if (server != null) {
      server.join();
    }
  }

  private String[] getIcebergRESTPackages(IcebergConfig icebergConfig) {
    List<String> packages = Lists.newArrayList(ICEBERG_REST_SPEC_PACKAGE);
    packages.addAll(icebergConfig.get(IcebergConfig.REST_API_EXTENSION_PACKAGES));
    return packages.toArray(new String[0]);
  }

  private void closeComponents() throws Exception {
    List<Exception> failures = Lists.newArrayList();
    close(failures, this::unregisterDeletionMetrics);
    close(failures, () -> purgeManager.ifPresent(IcebergPurgeManager::close));
    close(failures, () -> cleanupManager.ifPresent(IcebergCleanupManager::close));
    close(
        failures,
        () -> {
          if (configProvider != null) {
            configProvider.close();
          }
        });
    close(
        failures,
        () -> {
          if (icebergCatalogWrapperManager != null) {
            icebergCatalogWrapperManager.close();
          }
        });
    close(
        failures,
        () -> {
          if (icebergMetricsManager != null) {
            icebergMetricsManager.close();
          }
        });
    if (!failures.isEmpty()) {
      Exception failure = failures.get(0);
      failures.stream().skip(1).forEach(failure::addSuppressed);
      throw failure;
    }
  }

  private void unregisterDeletionMetrics() {
    if (metricsSystem != null && deletionMetricsSource != null) {
      metricsSystem.unregister(deletionMetricsSource);
      deletionMetricsSource = null;
    }
  }

  private static void close(List<Exception> failures, CheckedClose close) {
    try {
      close.run();
    } catch (Exception e) {
      failures.add(e);
    }
  }
}
