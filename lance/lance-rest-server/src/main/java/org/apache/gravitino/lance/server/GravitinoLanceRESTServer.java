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
package org.apache.gravitino.lance.server;

import com.google.common.base.Preconditions;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.di.DaggerGravitinoComponent;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.lance.LanceRESTService;
import org.apache.gravitino.lance.common.config.LanceConfig;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.server.authentication.AuthenticatorContext;
import org.apache.gravitino.server.authentication.ServerAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bootstrap entry point for the Lance REST facade. */
public class GravitinoLanceRESTServer {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoLanceRESTServer.class);

  public static final String CONF_FILE = "gravitino-lance-rest-server.conf";

  private final Config serverConfig;

  private LanceRESTService lanceRESTService;
  private GravitinoEnv gravitinoEnv;

  private final GravitinoComponent.Factory componentFactory;

  /**
   * Creates a standalone server whose environment is built from {@link GravitinoComponent}.
   *
   * @param config the server configuration
   */
  public GravitinoLanceRESTServer(Config config) {
    this(config, DaggerGravitinoComponent.factory());
  }

  /**
   * Creates a standalone server whose environment is built from the component a factory creates.
   *
   * <p>A downstream distribution passes the factory of its own root component, so the standalone
   * server gets the decorators and managed services of that component.
   *
   * @param config the server configuration
   * @param componentFactory creates the component the environment takes its objects from
   */
  @LegacyEnvAccess
  public GravitinoLanceRESTServer(Config config, GravitinoComponent.Factory componentFactory) {
    this.serverConfig = config;
    this.componentFactory = Preconditions.checkNotNull(componentFactory, "componentFactory");
    this.gravitinoEnv = GravitinoEnv.getInstance();
    this.lanceRESTService = new LanceRESTService();
  }

  private void initialize() {
    gravitinoEnv.initializeBaseComponents(serverConfig, componentFactory);
    lanceRESTService.serviceInit(
        serverConfig.getConfigsWithPrefix(LanceConfig.LANCE_CONFIG_PREFIX),
        false,
        standaloneContext(gravitinoEnv.component()));
    ServerAuthenticator.getInstance()
        .initialize(serverConfig, AuthenticatorContext.of(gravitinoEnv::idGenerator));
  }

  private void start() {
    gravitinoEnv.start();
    lanceRESTService.serviceStart();
  }

  private void join() {
    lanceRESTService.join();
  }

  private void stop() throws Exception {
    lanceRESTService.serviceStop();
    LOG.info("Gravitino Lance REST service stopped");
  }

  /**
   * Returns the host services of the base profile, which is all a standalone server builds.
   *
   * @param component the component the environment was initialized from
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
   * Runs the standalone server with an environment built from {@link GravitinoComponent}.
   *
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    run(args, DaggerGravitinoComponent.factory());
  }

  /**
   * Runs the standalone server with an environment built from the component a factory creates. A
   * downstream distribution calls this from its own entry point with the factory of its root
   * component.
   *
   * @param args the command line arguments
   * @param componentFactory creates the component the environment takes its objects from
   */
  public static void run(String[] args, GravitinoComponent.Factory componentFactory) {
    LOG.info("Starting Gravitino Lance REST Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = ServerConfig.loadConfig(confPath, CONF_FILE);
    GravitinoLanceRESTServer lanceRESTServer =
        new GravitinoLanceRESTServer(serverConfig, componentFactory);
    lanceRESTServer.initialize();

    try {
      lanceRESTServer.start();
    } catch (Exception e) {
      LOG.error("Error while running lance REST server", e);
      System.exit(-1);
    }
    LOG.info("Done, Gravitino Lance REST server started.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Thread.sleep(serverConfig.get(ServerConfig.SERVER_SHUTDOWN_TIMEOUT));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted exception:", e);
                  } catch (Exception e) {
                    LOG.error("Error while running clean-up tasks in shutdown hook", e);
                  }
                }));
    lanceRESTServer.join();

    LOG.info("Shutting down Gravitino Lance REST Server ... ");
    try {
      lanceRESTServer.stop();
      LOG.info("Gravitino Lance REST Server has shut down.");
    } catch (Exception e) {
      LOG.error("Error while stopping Gravitino Lance REST Server", e);
    }
  }
}
