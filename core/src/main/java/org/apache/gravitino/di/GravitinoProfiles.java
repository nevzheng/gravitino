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

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.apache.gravitino.secret.SecretPropertyUtils;
import org.apache.gravitino.utils.FileFetcher;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;

/**
 * The initialization profiles of a {@link GravitinoComponent}: the order in which each kind of
 * server requests the objects of its component.
 *
 * <p>The component builds each object the first time it is requested, and some objects must be
 * built before others: the entity store before the catalog manager, the catalog manager before the
 * metalake manager, the fileset chain before the schema chain, every host service before the
 * auxiliary services. A profile requests them in that order. {@link #base} is what a standalone
 * REST server builds, {@link #metadata} what an embedded metadata client builds, and {@link #full}
 * what the Gravitino server builds. {@code GravitinoEnv} runs the same profiles and then takes the
 * objects from the component.
 *
 * <p>Each profile first applies the {@link #seedStatics(Config) static settings} of the
 * configuration.
 */
public final class GravitinoProfiles {

  private GravitinoProfiles() {}

  /**
   * Applies the settings of a configuration that static code reads: whether the file fetcher blocks
   * unsafe remote URIs, the keywords that mark a property as sensitive, and the schema separator.
   *
   * @param config the configuration
   */
  public static void seedStatics(Config config) {
    FileFetcher.get().initialize(config.get(Configs.BLOCK_UNSAFE_REMOTE_URI));
    SecretPropertyUtils.configureSensitiveKeyKeywords(config);
    HierarchicalSchemaUtil.seedSchemaSeparator(config);
  }

  /**
   * Builds the base profile, used by the standalone Iceberg and Lance REST servers: the KMS client
   * registry, the secret manager, the metrics system, the event listener manager and the event bus.
   *
   * @param component the component
   */
  public static void base(GravitinoComponent component) {
    seedStatics(component.config());
    common(component);
    component.eventListenerManager();
    // The bus depends on the audit log manager, which registers its listener when it is built.
    component.eventBus();
  }

  /**
   * Builds the metadata profile, used for normalized metadata operations without a server: the
   * common services, the entity store and catalog manager, and the internal chains. It builds no
   * event listeners, audit logging, metadata hooks, auxiliary services or job management.
   *
   * @param component the component
   */
  public static void metadata(GravitinoComponent component) {
    seedStatics(component.config());
    common(component);
    internalMetadata(component);
  }

  /**
   * Builds the full profile, used by the Gravitino server: the base profile, the internal and
   * public chains, the auxiliary services, the job chains, and the listener that registers the
   * built-in job templates of new metalakes.
   *
   * @param component the component
   */
  public static void full(GravitinoComponent component) {
    base(component);
    internalMetadata(component);
    publicMetadata(component);

    // Building the manager loads and initializes the auxiliary services, after every host service
    // they may use is built.
    component.auxiliaryServiceManager();

    // The job manager is built after the auxiliary services are initialized, as before.
    component.internalJobOperationDispatcher();
    component.jobOperationDispatcher();

    // Register built-in job template event listener to automatically register templates
    // when metalakes are created
    component
        .eventListenerManager()
        .addEventListener("builtin-job-template", component.builtInJobTemplateEventListener());
  }

  /**
   * Starts the managed services of a component that a profile built. The services a downstream
   * component contributes (see {@link GravitinoComponent#contributedServices()}) are built and
   * registered first, after what they depend on, so they start in the same dependency order as the
   * others and close in reverse.
   *
   * <p>If a service fails to start, the services already started are closed and the failure is
   * rethrown.
   *
   * @param component the component
   * @throws Exception if a service fails to start
   */
  public static void start(GravitinoComponent component) throws Exception {
    ManagedServices services = component.managedServices();
    component.contributedServices().forEach(services::register);
    services.start();
  }

  private static void common(GravitinoComponent component) {
    component.kmsClientRegistry();
    component.secretManager();
    component.metricsSystem();
  }

  private static void internalMetadata(GravitinoComponent component) {
    // Building the entity store connects to its backend, so it is built before anything else of
    // the metadata profile.
    component.entityStore();
    component.idGenerator();
    component.lockManager();
    // CatalogManager is initialized before MetalakeManager, as before; a force drop removes the
    // child catalogs through the public catalog chain around it.
    component.catalogAccess();

    // Building the internal metalake chain builds the metalake manager, after the catalog manager,
    // as before; the managed services close it.
    component.internalMetalakeDispatcher();
    component.internalCatalogDispatcher();

    component.credentialOperationDispatcher();
    component.secretPropertyOperationDispatcher();

    // The fileset chain is built before the schema chain, which drops filesets through it.
    component.internalFilesetDispatcher();
    component.internalSchemaDispatcher();
    component.internalTableDispatcher();
    component.internalPartitionDispatcher();
    component.internalTopicDispatcher();
    component.internalModelDispatcher();
    component.internalFunctionDispatcher();
    component.internalViewDispatcher();
    component.internalSemanticModelDispatcher();

    component.internalStatisticDispatcher();
    if (authorizationEnabled(component)) {
      component.internalAccessControlDispatcher();
      component.internalOwnerDispatcher();
      component.bulkManager();
      component.futureGrantManager();
    }

    component.internalTagDispatcher();
    component.internalPolicyDispatcher();
  }

  private static void publicMetadata(GravitinoComponent component) {
    // Each public chain is event -> normalize -> hook -> operation; see the modules of
    // GravitinoComponent. Partitions have no ownership, so their public chain wraps the internal
    // one without a hook.
    component.metalakeDispatcher();
    component.catalogDispatcher();
    component.filesetDispatcher();
    component.schemaDispatcher();
    component.tableDispatcher();
    component.partitionDispatcher();
    component.topicDispatcher();
    component.modelDispatcher();
    component.functionDispatcher();
    component.viewDispatcher();
    component.semanticModelDispatcher();

    component.statisticDispatcher();

    if (authorizationEnabled(component)) {
      component.accessControlDispatcher();
      component.ownerDispatcher();
    }

    component.tagDispatcher();
    component.policyDispatcher();
  }

  private static boolean authorizationEnabled(GravitinoComponent component) {
    return component.config().get(Configs.ENABLE_AUTHORIZATION);
  }
}
