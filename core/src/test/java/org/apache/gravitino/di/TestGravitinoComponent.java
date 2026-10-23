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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.catalog.PartitionOperationDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.lifecycle.ManagedService;
import org.apache.gravitino.lifecycle.ManagedServices;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.stats.storage.MemoryPartitionStatsStorageFactory;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.tag.TagManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestGravitinoComponent {

  @TempDir Path stagingDir;

  private Config config;
  private GravitinoComponent component;

  /**
   * Returns a configuration under which every object the component owns can be built against fake
   * leaves.
   *
   * @return the configuration
   */
  static Config testConfig() {
    Config config = new Config(false) {};
    config.set(
        Configs.PARTITION_STATS_STORAGE_FACTORY_CLASS,
        MemoryPartitionStatsStorageFactory.class.getCanonicalName());
    config.set(Configs.SERVICE_ADMINS, Collections.singletonList("admin"));
    return config;
  }

  @BeforeEach
  public void setUp() {
    config = testConfig();
    component = DaggerGravitinoComponent.factory().create(config);
  }

  @Test
  public void testConfigIsTheBoundInstance() {
    assertSame(config, component.config());
  }

  @Test
  public void testOwnedObjectsAreSingletons() {
    assertNotNull(component.lockManager());
    assertSame(component.lockManager(), component.lockManager());
    assertSame(component.idGenerator(), component.idGenerator());
    assertSame(component.metricsSystem(), component.metricsSystem());
    assertSame(component.secretManager(), component.secretManager());
    assertSame(component.kmsClientRegistry(), component.kmsClientRegistry());
    assertSame(component.eventBus(), component.eventBus());
    assertSame(component.eventListenerManager(), component.eventListenerManager());
    assertSame(component.auditLogManager(), component.auditLogManager());
  }

  @Test
  public void testOwnedObjectsMatchWhatGravitinoEnvBuilt() {
    assertInstanceOf(RandomIdGenerator.class, component.idGenerator());
    assertNotNull(component.metricsSystem().getMetricsSource(MetricsSource.JVM_METRIC_NAME));
  }

  @Test
  public void testSingletonsArePerComponent() {
    GravitinoComponent other = DaggerGravitinoComponent.factory().create(config);

    assertNotSame(component.lockManager(), other.lockManager());
    assertNotSame(component.idGenerator(), other.idGenerator());
    assertNotSame(component.metricsSystem(), other.metricsSystem());
    assertNotSame(component.secretManager(), other.secretManager());
    assertNotSame(component.eventBus(), other.eventBus());
  }

  @Test
  public void testDispatcherChainsAreSingletonsAndPublicIsNotInternal() throws Exception {
    Config graphConfig = testConfig();
    graphConfig.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    // While authorization is disabled both owner chains are the one no-op dispatcher.
    graphConfig.set(Configs.ENABLE_AUTHORIZATION, true);
    ModuleTestComponent graph = ModuleTestComponent.create(graphConfig, new FakeLeaves());
    List<List<Supplier<Object>>> chains =
        List.of(
            List.of(graph::metalakeDispatcher, graph::internalMetalakeDispatcher),
            List.of(graph::catalogDispatcher, graph::internalCatalogDispatcher),
            List.of(graph::schemaDispatcher, graph::internalSchemaDispatcher),
            List.of(graph::tableDispatcher, graph::internalTableDispatcher),
            List.of(graph::partitionDispatcher, graph::internalPartitionDispatcher),
            List.of(graph::viewDispatcher, graph::internalViewDispatcher),
            List.of(graph::filesetDispatcher, graph::internalFilesetDispatcher),
            List.of(graph::topicDispatcher, graph::internalTopicDispatcher),
            List.of(graph::modelDispatcher, graph::internalModelDispatcher),
            List.of(graph::functionDispatcher, graph::internalFunctionDispatcher),
            List.of(graph::semanticModelDispatcher, graph::internalSemanticModelDispatcher),
            List.of(graph::tagDispatcher, graph::internalTagDispatcher),
            List.of(graph::policyDispatcher, graph::internalPolicyDispatcher),
            List.of(graph::ownerDispatcher, graph::internalOwnerDispatcher),
            List.of(graph::statisticDispatcher, graph::internalStatisticDispatcher),
            List.of(graph::accessControlDispatcher, graph::internalAccessControlDispatcher),
            List.of(graph::jobOperationDispatcher, graph::internalJobOperationDispatcher));
    try {
      for (List<Supplier<Object>> chain : chains) {
        Object publicChain = chain.get(0).get();
        Object internalChain = chain.get(1).get();
        assertSame(publicChain, chain.get(0).get());
        assertSame(internalChain, chain.get(1).get());
        assertNotSame(publicChain, internalChain);
      }
    } finally {
      graph.jobOperationDispatcher().close();
      graph.internalStatisticDispatcher().close();
      graph.metalakeManager().close();
      graph.catalogManager().close();
    }
  }

  @Test
  public void testManagedServicesRegisterInDependencyOrder() throws Exception {
    Config graphConfig = testConfig();
    graphConfig.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    ModuleTestComponent graph = ModuleTestComponent.create(graphConfig, new FakeLeaves());
    ManagedServices services = graph.managedServices();
    try {
      // Asking for the metalake manager builds what it depends on first.
      MetalakeManager metalakeManager = graph.metalakeManager();

      List<ManagedService> registered = services.services();
      int lockManager = registered.indexOf(graph.lockManager());
      int catalogManager = registered.indexOf(graph.catalogManager());
      int metalakes = registered.indexOf(metalakeManager);
      assertTrue(lockManager >= 0, registered.toString());
      assertTrue(lockManager < catalogManager, registered.toString());
      assertTrue(registered.indexOf(graph.metricsSystem()) < catalogManager, registered.toString());
      assertTrue(catalogManager < metalakes, registered.toString());
      // Only what was asked for is built: the job manager is not.
      assertEquals(metalakes, registered.size() - 1, registered.toString());
    } finally {
      services.close();
    }
    assertEquals(ManagedServices.State.CLOSED, services.state());
  }

  @Test
  public void testExposesNoUndecoratedImplementation() {
    // The managers and operation dispatchers under the chains are bound @Undecorated, which only
    // this package can name, and the component hands out only the chains around them.
    assertFalse(Modifier.isPublic(Undecorated.class.getModifiers()));
    List<Class<?>> undecorated =
        List.of(
            TagManager.class,
            PolicyManager.class,
            OwnerManager.class,
            StatisticManager.class,
            AccessControlManager.class,
            JobManager.class,
            JobTemplateValidationDispatcher.class,
            SchemaOperationDispatcher.class,
            PartitionOperationDispatcher.class,
            FilesetOperationDispatcher.class,
            TopicOperationDispatcher.class,
            ModelOperationDispatcher.class,
            FunctionOperationDispatcher.class);
    for (Method method : GravitinoComponent.class.getMethods()) {
      for (Class<?> type : undecorated) {
        assertFalse(
            type.isAssignableFrom(method.getReturnType()),
            method.getName() + " exposes " + type.getSimpleName());
      }
    }
  }
}
