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
package org.apache.gravitino.iceberg.server;

import com.google.common.base.Preconditions;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.di.GravitinoProfiles;
import org.apache.gravitino.iceberg.RESTService;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.server.authentication.AuthenticatorContext;
import org.apache.gravitino.server.authentication.ServerAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GravitinoIcebergRESTServer {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoIcebergRESTServer.class);

  public static final String CONF_FILE = "gravitino-iceberg-rest-server.conf";

  private final ServerConfig serverConfig;

  private RESTService icebergRESTService;
  // Built by initialize() from the factory.
  private GravitinoComponent component;

  private final GravitinoComponent.Factory componentFactory;

  /**
   * Creates a standalone server built from {@link GravitinoComponent}.
   *
   * @param config the server configuration
   */
  public GravitinoIcebergRESTServer(ServerConfig config) {
    this(config, DaggerGravitinoComponent.factory());
  }

  /**
   * Creates a standalone server built from the component a factory creates.
   *
   * <p>A downstream distribution passes the factory of its own root component, so the standalone
   * server gets the decorators and managed services of that component.
   *
   * @param config the server configuration
   * @param componentFactory creates the component the server takes its services from
   */
  public GravitinoIcebergRESTServer(
      ServerConfig config, GravitinoComponent.Factory componentFactory) {
    this.serverConfig = config;
    this.componentFactory = Preconditions.checkNotNull(componentFactory, "componentFactory");
    this.icebergRESTService = new RESTService();
  }

  private void initialize() {
    this.component = componentFactory.create(serverConfig);
    GravitinoProfiles.base(component);
    icebergRESTService.serviceInit(
        serverConfig.getConfigsWithPrefix(IcebergConfig.ICEBERG_CONFIG_PREFIX),
        false,
        standaloneContext(component));
    // The base profile builds no id generator.
    ServerAuthenticator.getInstance().initialize(serverConfig, AuthenticatorContext.of(() -> null));
  }

  private void start() throws Exception {
    component.lifecycle().start();
    icebergRESTService.serviceStart();
  }

  private void join() {
    icebergRESTService.join();
  }

  private void stop() throws Exception {
    icebergRESTService.serviceStop();
    LOG.info("Gravitino Iceberg REST service stopped");
  }

  /**
   * Returns the host services of the base profile, which is all a standalone server builds.
   *
   * @param component the component the server was initialized from
   * @return the context of the standalone service
   */
  static AuxiliaryContext standaloneContext(GravitinoComponent component) {
    return AuxiliaryContext.standalone(
        component.config(),
        component.eventBus(),
        component.metricsSystem(),
        component.secretManager(),
        component.kmsClientRegistry());
  }

  /**
   * Runs the standalone server built from {@link GravitinoComponent}.
   *
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    run(args, DaggerGravitinoComponent.factory());
  }

  /**
   * Runs the standalone server built from the component a factory creates. A downstream
   * distribution calls this from its own entry point with the factory of its root component.
   *
   * @param args the command line arguments
   * @param componentFactory creates the component the server takes its services from
   */
  public static void run(String[] args, GravitinoComponent.Factory componentFactory) {
    LOG.info("Starting Gravitino Iceberg REST Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = ServerConfig.loadConfig(confPath, CONF_FILE);
    Preconditions.checkArgument(
        !serverConfig.get(Configs.ENABLE_AUTHORIZATION),
        "Iceberg REST server standalone mode doesn't support authorization.");
    GravitinoIcebergRESTServer icebergRESTServer =
        new GravitinoIcebergRESTServer(serverConfig, componentFactory);
    icebergRESTServer.initialize();

    try {
      icebergRESTServer.start();
    } catch (Exception e) {
      LOG.error("Error while running jettyServer", e);
      System.exit(-1);
    }
    LOG.info("Done, Gravitino Iceberg REST server started.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    // Register some clean-up tasks that need to be done before shutting down
                    Thread.sleep(serverConfig.get(ServerConfig.SERVER_SHUTDOWN_TIMEOUT));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted exception:", e);
                  } catch (Exception e) {
                    LOG.error("Error while running clean-up tasks in shutdown hook", e);
                  }
                }));
    icebergRESTServer.join();

    LOG.info("Shutting down Gravitino Iceberg REST Server ... ");
    try {
      icebergRESTServer.stop();
      LOG.info("Gravitino Iceberg REST Server has shut down.");
    } catch (Exception e) {
      LOG.error("Error while stopping Gravitino Iceberg REST Server", e);
    }
  }
}
