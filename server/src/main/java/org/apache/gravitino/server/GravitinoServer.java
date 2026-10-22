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
package org.apache.gravitino.server;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.servlet.Servlet;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.authorization.EnvironmentAuthorizerContext;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lifecycle.Lifecycle;
import org.apache.gravitino.lineage.LineageConfig;
import org.apache.gravitino.lineage.LineageService;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.server.authentication.AuthenticatorContext;
import org.apache.gravitino.server.authentication.ServerAuthenticator;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.web.ConfigServlet;
import org.apache.gravitino.server.web.HealthAliasServlet;
import org.apache.gravitino.server.web.HttpAuditFilter;
import org.apache.gravitino.server.web.HttpServerMetricsSource;
import org.apache.gravitino.server.web.JettyServer;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.OutOfMemoryErrorListener;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.server.web.VersioningFilter;
import org.apache.gravitino.server.web.filter.AccessControlNotAllowedFilter;
import org.apache.gravitino.server.web.mapper.ErrorExceptionMapper;
import org.apache.gravitino.server.web.mapper.JsonMappingExceptionMapper;
import org.apache.gravitino.server.web.mapper.JsonParseExceptionMapper;
import org.apache.gravitino.server.web.mapper.JsonProcessingExceptionMapper;
import org.apache.gravitino.server.web.mapper.NotFoundExceptionMapper;
import org.apache.gravitino.server.web.mapper.ParamExceptionMapper;
import org.apache.gravitino.server.web.mapper.WebApplicationExceptionMapper;
import org.apache.gravitino.server.web.ui.WebUIFilter;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GravitinoServer extends ResourceConfig {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoServer.class);

  private static final String API_ANY_PATH = "/api/*";

  // Servlets mounted outside API_ANY_PATH that still need request-context tracking and
  // audit-on-failure coverage. VersioningFilter is intentionally not applied to these: it
  // negotiates Gravitino's "application/vnd.gravitino.vN+json" media type for the Jersey-managed
  // REST surface, and none of these servlets are part of it or read that header. /metrics and
  // /prometheus/metrics are registered directly on JettyServer's servlet context, outside the
  // Jersey-managed REST surface. See GH-12760.
  private static final ImmutableList<String> ROOT_MOUNTED_PATHS =
      ImmutableList.<String>builder()
          .add("/configs")
          .addAll(JettyServer.METRICS_PATH_SPECS)
          .build();

  public static final String CONF_FILE = "gravitino.conf";

  public static final String WEBSERVER_CONF_PREFIX = "gravitino.server.webserver.";

  public static final String SERVER_NAME = "Gravitino-webserver";

  private final ServerConfig serverConfig;

  private final JettyServer server;

  private final GravitinoEnv gravitinoEnv;

  // Null for a server built with the deprecated constructor, which binds from the env's getters.
  @Nullable private final GravitinoComponent.Factory componentFactory;

  // The component the environment was initialized from; set by initialize().
  @Nullable private GravitinoComponent component;

  private final LineageService lineageService;

  private final GravitinoAuthorizerProvider authorizerProvider;

  // Whether initialize() installed the provider's authorizer in the environment.
  private boolean installedInEnv;

  private final Lifecycle lifecycle;

  // The stop actions of the lifecycle, in stop order. Each runs at most once, whether the
  // lifecycle or stop() runs it.
  private final List<Lifecycle.Action> stopActions;

  private final AtomicBoolean isStopped = new AtomicBoolean(false);

  /**
   * Creates a server. {@link #initialize()} initializes the environment from the component that
   * {@code componentFactory} creates and binds the dispatchers the REST resources inject from that
   * component's provision methods.
   *
   * <p>A downstream server passes the factory of its own root component, which extends {@link
   * GravitinoComponent}, so the chains its decorators change reach the REST resources, and reads
   * that component back through {@link #component()} to bind its own services.
   *
   * @param config the server configuration
   * @param gravitinoEnv the environment to initialize
   * @param componentFactory creates the component the environment and the REST resources use
   */
  public GravitinoServer(
      ServerConfig config, GravitinoEnv gravitinoEnv, GravitinoComponent.Factory componentFactory) {
    this(
        config,
        gravitinoEnv,
        Preconditions.checkNotNull(componentFactory, "componentFactory"),
        new JettyServer(),
        new LineageService(),
        GravitinoAuthorizerProvider.getInstance());
  }

  /**
   * Creates a server over the process environment. {@link #initialize()} initializes it from the
   * component that {@code componentFactory} creates; see {@link #GravitinoServer(ServerConfig,
   * GravitinoEnv, GravitinoComponent.Factory)}.
   *
   * @param config the server configuration
   * @param componentFactory creates the component the environment and the REST resources use
   */
  public GravitinoServer(ServerConfig config, GravitinoComponent.Factory componentFactory) {
    this(config, processEnv(), componentFactory);
  }

  /**
   * Creates a server that initializes the environment with {@code
   * GravitinoEnv.initializeFullComponents(Config)} and binds the dispatchers the REST resources
   * inject from the environment's getters, so a {@code GravitinoEnv} subclass that overrides them
   * keeps working. A subclass that overrides {@code initializeFullComponents} must call the
   * overridden method: it builds the component that {@code GravitinoEnv.start()} starts, and
   * without it the server fails to start with an {@link IllegalStateException}.
   *
   * @param config the server configuration
   * @param gravitinoEnv the environment to initialize
   * @deprecated Change dispatcher chains with {@code DispatcherDecorator}s in a downstream
   *     component instead of overriding {@code GravitinoEnv} getters, and use {@link
   *     #GravitinoServer(ServerConfig, GravitinoEnv, GravitinoComponent.Factory)}.
   */
  @Deprecated
  public GravitinoServer(ServerConfig config, GravitinoEnv gravitinoEnv) {
    this(
        config,
        gravitinoEnv,
        null,
        new JettyServer(),
        new LineageService(),
        GravitinoAuthorizerProvider.getInstance());
  }

  @VisibleForTesting
  GravitinoServer(
      ServerConfig config,
      GravitinoEnv gravitinoEnv,
      @Nullable GravitinoComponent.Factory componentFactory,
      JettyServer server,
      LineageService lineageService,
      GravitinoAuthorizerProvider authorizerProvider) {
    this.serverConfig = config;
    this.server = server;
    this.gravitinoEnv = gravitinoEnv;
    this.componentFactory = componentFactory;
    this.lineageService = lineageService;
    this.authorizerProvider = authorizerProvider;
    Lifecycle.Action shutdownEnv = once(gravitinoEnv::shutdown);
    Lifecycle.Action closeAuthorizer = once(this::closeAuthorizer);
    Lifecycle.Action stopJetty = once(server::stop);
    this.stopActions = ImmutableList.of(stopJetty, closeAuthorizer, shutdownEnv);
    // Start order: environment, then Jetty. Stop order is the reverse, so Jetty stops taking
    // requests before the authorizer closes and before the environment shuts down.
    this.lifecycle =
        Lifecycle.builder()
            .add("Gravitino environment", gravitinoEnv::start, shutdownEnv)
            .addStopOnly("Gravitino authorizer", closeAuthorizer)
            .add("Jetty server", server::start, stopJetty)
            .build();
  }

  public void initialize() {
    if (componentFactory != null) {
      gravitinoEnv.initializeFullComponents(serverConfig, componentFactory);
      this.component =
          Preconditions.checkNotNull(
              gravitinoEnv.component(), "The environment holds no component after initialization");
    } else {
      // The deprecated constructor: a GravitinoEnv subclass may override this method.
      gravitinoEnv.initializeFullComponents(serverConfig);
    }

    JettyServerConfig jettyServerConfig =
        JettyServerConfig.fromConfig(serverConfig, WEBSERVER_CONF_PREFIX);
    server.initialize(jettyServerConfig, SERVER_NAME);
    server.addMetricsServlets(metricsSystem());

    ServerAuthenticator.getInstance()
        .initialize(serverConfig, AuthenticatorContext.of(gravitinoEnv::idGenerator));

    installAuthorizer();

    lineageService.initialize(
        new LineageConfig(serverConfig.getConfigsWithPrefix(LineageConfig.LINEAGE_CONFIG_PREFIX)));

    // initialize Jersey REST API resources.
    initializeRestApi(jettyServerConfig);
  }

  public ServerConfig serverConfig() {
    return serverConfig;
  }

  /**
   * Returns the component {@link #initialize()} initialized the environment from: the one the
   * factory given to the constructor created. A downstream server reads its own root component here
   * to bind its own services into HK2.
   *
   * @return the component
   * @throws IllegalStateException before {@link #initialize()}, or on a server built with the
   *     deprecated {@link #GravitinoServer(ServerConfig, GravitinoEnv)}
   */
  protected GravitinoComponent component() {
    Preconditions.checkState(
        component != null,
        "The server has no component: it is not initialized, or it binds from GravitinoEnv getters");
    return component;
  }

  private void initializeRestApi(JettyServerConfig jettyServerConfig) {
    HashSet<String> restApiPackagesSet = new HashSet<>();
    restApiPackagesSet.add("org.apache.gravitino.server.web.rest");
    restApiPackagesSet.addAll(serverConfig.get(Configs.REST_API_EXTENSION_PACKAGES));
    restApiPackagesSet.addAll(lineageService.getRESTPackages());
    packages(restApiPackagesSet.toArray(new String[0]));

    boolean enableAuthorization = serverConfig.get(Configs.ENABLE_AUTHORIZATION);
    register(dispatcherBinder(enableAuthorization));
    register(JsonProcessingExceptionMapper.class);
    register(new OutOfMemoryErrorListener());
    register(ErrorExceptionMapper.class);
    register(JsonParseExceptionMapper.class);
    register(JsonMappingExceptionMapper.class);
    register(ParamExceptionMapper.class);
    register(NotFoundExceptionMapper.class);
    register(WebApplicationExceptionMapper.class);
    register(new ObjectMapperProvider(jettyServerConfig.isIncludeErrorStackTrace()))
        .register(JacksonFeature.class);
    property(CommonProperties.JSON_JACKSON_DISABLED_MODULES, "DefaultScalaModule");

    if (!enableAuthorization) {
      register(AccessControlNotAllowedFilter.class);
    }

    HttpServerMetricsSource httpServerMetricsSource =
        new HttpServerMetricsSource(MetricsSource.GRAVITINO_SERVER_METRIC_NAME, this, server);
    metricsSystem().register(httpServerMetricsSource);
    EventBus eventBus = eventBus();

    Servlet servlet = new ServletContainer(this);
    server.addServlet(servlet, API_ANY_PATH);
    Servlet configServlet = new ConfigServlet(serverConfig);
    server.addServlet(configServlet, "/configs");

    // Root-level aliases for enterprise GTMs that require probes at well-known root paths.
    // Forwards /health, /health/live, /health/ready, and /health.html to the canonical
    // /api/health/* endpoints. Not part of ROOT_MOUNTED_PATHS below: HealthAliasServlet forwards
    // every request into /api/health*, which API_ANY_PATH already covers via the servlet
    // container's FORWARD dispatcher type, so binding the filters again here would double-log
    // every probe.
    server.addServlet(new HealthAliasServlet(), "/health/*");
    server.addServlet(new HealthAliasServlet(), "/health.html");

    // API_ANY_PATH keeps its original, unabridged filter set: this is the only pathspec that is
    // part of the Jersey-managed REST surface, so it's the only one VersioningFilter applies to.
    server.addFilter(new RequestContextFilter(eventBus), API_ANY_PATH);
    server.addFilter(new HttpAuditFilter(eventBus, EventSource.GRAVITINO_SERVER), API_ANY_PATH);
    server.addFilter(
        new VersioningFilter(jettyServerConfig.isIncludeErrorStackTrace()), API_ANY_PATH);

    // GH-12760: servlets mounted outside API_ANY_PATH used to receive none of the filters below
    // (no request-context tracking, no audit-on-failure, no custom filters), with nothing in the
    // build catching it. Every pathSpec a servlet is registered under above must appear in
    // ROOT_MOUNTED_PATHS, unless it forwards into an already-covered path (see HealthAliasServlet
    // above) or is added to the exemption list documented on
    // TestGravitinoServer#testEveryServletPathIsCoveredByAuditFilter. RequestContextFilter is
    // given the event bus here too (not just API_ANY_PATH) so query-parameter capture
    // (see RequestContextFilter's class doc) applies uniformly, not only to the REST API.
    for (String pathSpec : ROOT_MOUNTED_PATHS) {
      server.addFilter(new RequestContextFilter(eventBus), pathSpec);
      server.addFilter(new HttpAuditFilter(eventBus, EventSource.GRAVITINO_SERVER), pathSpec);
    }

    // Custom filters are registered once, across every filtered path in a single call, so a
    // filter whose init() isn't safe to run more than once per JVM only runs it once rather than
    // once per pathSpec.
    List<String> customFilterPaths = new ArrayList<>(ROOT_MOUNTED_PATHS);
    customFilterPaths.add(API_ANY_PATH);
    server.addCustomFilters(customFilterPaths.toArray(new String[0]));

    // Only API_ANY_PATH requires authentication today. /configs must stay open for the Web UI's
    // pre-login OAuth bootstrap (see docs/gravitino-server-config.md). Secret provider discovery
    // lives under /api/metalakes/{metalake}/secrets/providers (see GH-12921).
    server.addSystemFilters(API_ANY_PATH);
    if (server.isWebUiEnabled()) {
      server.addFilter(new WebUIFilter(), "/"); // Redirect to the /ui/index html page.
      server.addFilter(new WebUIFilter(), "/ui/*"); // Redirect to the static html file.
    }
  }

  public void start() throws Exception {
    lifecycle.start();
  }

  public void join() {
    server.join();
  }

  public void stop() throws IOException {
    Exception failure = null;
    try {
      if (lifecycle.state() != Lifecycle.State.STARTED) {
        // Never started, for example when initialize() failed, or a failed start stopped only
        // what it had started: still release the rest. No stop action runs twice.
        failure = runAll(stopActions);
      }
      lifecycle.close();
    } catch (Exception e) {
      failure = addFailure(failure, e);
    }

    try {
      lineageService.close();
    } catch (Exception e) {
      failure = addFailure(failure, e);
    }

    if (failure instanceof IOException) {
      throw (IOException) failure;
    } else if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    } else if (failure != null) {
      throw new IOException("Failed to stop Gravitino server", failure);
    }
  }

  void gracefulStop() throws IOException {
    if (!isStopped.compareAndSet(false, true)) {
      return;
    }
    stop();
  }

  public static void main(String[] args) {
    LOG.info("Starting Gravitino Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = loadConfig(confPath);
    GravitinoServer server = new GravitinoServer(serverConfig, DaggerGravitinoComponent.factory());

    try {
      server.initialize();
      // Instantiates GravitinoServer
      server.start();
    } catch (Exception e) {
      LOG.error("Error while running jettyServer", e);
      System.exit(-1);
    }
    LOG.info("Done, Gravitino server started.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Thread.sleep(server.serverConfig.get(ServerConfig.SERVER_SHUTDOWN_TIMEOUT));
                    server.gracefulStop();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted exception:", e);
                  } catch (Exception e) {
                    LOG.error("Error while running clean-up tasks in shutdown hook", e);
                  }
                }));

    server.join();

    LOG.info("Shutting down Gravitino Server ... ");
    try {
      server.gracefulStop();
      LOG.info("Gravitino Server has shut down.");
    } catch (Exception e) {
      LOG.error("Error while stopping Gravitino Server", e);
    }
  }

  static ServerConfig loadConfig(String confPath) {
    ServerConfig serverConfig = new ServerConfig();
    try {
      if (confPath.isEmpty()) {
        // Load default conf
        serverConfig.loadFromFile(CONF_FILE);
      } else {
        Properties properties = serverConfig.loadPropertiesFromFile(new File(confPath));
        serverConfig.loadFromProperties(properties);
      }
    } catch (Exception exception) {
      throw new IllegalArgumentException("Failed to load conf from file " + confPath, exception);
    }
    return serverConfig;
  }

  /**
   * Returns the binder of the dispatchers the REST resources inject: from the component's provision
   * methods, or, for the deprecated constructor, from the environment's getters.
   */
  private AbstractBinder dispatcherBinder(boolean enableAuthorization) {
    return component != null
        ? new GravitinoComponentBinder(component, enableAuthorization, lineageService)
        : new GravitinoEnvBinder(gravitinoEnv, enableAuthorization, lineageService);
  }

  /**
   * Builds the authorizer of the environment's component, so it is initialized before the server
   * takes requests, and installs it in the provider for the code that reads it statically. The
   * component closes it. An environment that holds no component, which only a {@code GravitinoEnv}
   * subclass given to the deprecated constructor can produce, gets an authorizer the provider
   * builds from the environment's getters and closes.
   */
  private void installAuthorizer() {
    GravitinoComponent built = component != null ? component : gravitinoEnv.component();
    if (built != null) {
      authorizerProvider.install(built.gravitinoAuthorizer());
      return;
    }
    authorizerProvider.initialize(serverConfig, EnvironmentAuthorizerContext.of(gravitinoEnv));
    gravitinoEnv.setGravitinoAuthorizer(authorizerProvider.getGravitinoAuthorizer());
    installedInEnv = true;
  }

  /**
   * Takes the authorizer back from the provider, and from the environment if the server installed
   * it there. The provider closes only an authorizer it built; the component closes its own.
   */
  private void closeAuthorizer() throws IOException {
    if (installedInEnv) {
      gravitinoEnv.setGravitinoAuthorizer(null);
      installedInEnv = false;
    }
    authorizerProvider.close();
  }

  // The server still holds the process environment, which its REST resources and the code it
  // loads read; it is removed with the facade.
  @LegacyEnvAccess
  private static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }

  private MetricsSystem metricsSystem() {
    return component != null ? component.metricsSystem() : gravitinoEnv.metricsSystem();
  }

  private EventBus eventBus() {
    return component != null ? component.eventBus() : gravitinoEnv.eventBus();
  }

  /** Returns an action that runs {@code action} the first time it is run, and then does nothing. */
  private static Lifecycle.Action once(Lifecycle.Action action) {
    AtomicBoolean ran = new AtomicBoolean();
    return () -> {
      if (ran.compareAndSet(false, true)) {
        action.run();
      }
    };
  }

  private static Exception runAll(List<Lifecycle.Action> actions) {
    Exception failure = null;
    for (Lifecycle.Action action : actions) {
      try {
        action.run();
      } catch (Exception e) {
        failure = addFailure(failure, e);
      }
    }
    return failure;
  }

  private static Exception addFailure(Exception first, Exception next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }
}
