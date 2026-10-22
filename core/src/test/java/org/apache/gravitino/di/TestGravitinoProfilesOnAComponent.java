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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.util.Collections;
import java.util.Properties;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.EntityStoreFactory;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.catalog.SemanticModelNormalizeDispatcher;
import org.apache.gravitino.catalog.SemanticModelOperationDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.listener.StatisticEventDispatcher;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.secret.memory.InMemorySecretsProvider;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.stats.storage.MemoryPartitionStatsStorageFactory;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Runs each {@link GravitinoProfiles profile} on a component the Dagger factory builds, over a
 * mocked entity store, and checks what the profile leaves the caller able to use.
 */
public class TestGravitinoProfilesOnAComponent {

  @AfterEach
  public void tearDown() {
    HierarchicalSchemaUtil.seedSchemaSeparator(null);
  }

  @Test
  public void testMetadataProfileReadsTheStoreThroughTheInternalChains() throws Exception {
    Config config = config();
    EntityStore entityStore = relationStore();
    try (MockedStatic<EntityStoreFactory> factory = mockStatic(EntityStoreFactory.class)) {
      factory.when(() -> EntityStoreFactory.createEntityStore(config)).thenReturn(entityStore);
      GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);
      try {
        GravitinoProfiles.metadata(component);

        verify(entityStore).initialize(config);
        assertSame(entityStore, component.entityStore());
        assertNotNull(component.catalogAccess());
        assertSemanticModelChain(component.internalSemanticModelDispatcher());
        assertInstanceOf(StatisticManager.class, component.internalStatisticDispatcher());

        clearInvocations(entityStore);
        assertEquals(0, component.internalMetalakeDispatcher().listMetalakes().length);
        verify(entityStore).list(Namespace.empty(), BaseMetalake.class, EntityType.METALAKE);

        component.lifecycle().start();
      } finally {
        component.lifecycle().close();
      }
    }

    // The lifecycle stops what the profile built, the entity store among them.
    verify(entityStore).close();
  }

  @Test
  public void testFullProfileWrapsTheInternalChainsInThePublicOnes() throws Exception {
    Config config = config();
    EntityStore entityStore = relationStore();
    try (MockedStatic<EntityStoreFactory> factory = mockStatic(EntityStoreFactory.class)) {
      factory.when(() -> EntityStoreFactory.createEntityStore(config)).thenReturn(entityStore);
      GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);
      try {
        GravitinoProfiles.full(component);

        assertNotNull(component.auxiliaryServiceManager());
        assertNotNull(component.jobOperationDispatcher());
        assertSame(
            component.internalPartitionDispatcher(),
            FieldUtils.readField(component.partitionDispatcher(), "dispatcher", true));
        assertInstanceOf(StatisticEventDispatcher.class, component.statisticDispatcher());
        assertSame(
            component.internalStatisticDispatcher(),
            FieldUtils.readField(component.statisticDispatcher(), "dispatcher", true));
      } finally {
        component.lifecycle().close();
      }
    }

    verify(entityStore).close();
  }

  @Test
  public void testBaseProfileBuildsTheRegistriesAndTheLifecycleClosesThem() throws Exception {
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(new Config(false) {});

    GravitinoProfiles.base(component);
    KmsClientRegistry kmsClients = component.kmsClientRegistry();
    SecretManager secretManager = component.secretManager();
    KmsReference reference = new KmsReference("missing", "key");

    // Both registries are optional: with nothing configured they are empty.
    assertThrows(IllegalArgumentException.class, () -> kmsClients.getClient(reference));
    assertTrue(secretManager.getRegistry().listProviders().isEmpty());

    component.lifecycle().close();

    assertThrows(IllegalStateException.class, () -> kmsClients.getClient(reference));
    assertThrows(IllegalStateException.class, () -> secretManager.getRegistry().listProviders());
  }

  @Test
  public void testBaseProfileLoadsTheConfiguredSecretProviders() throws Exception {
    Config config = new Config(false) {};
    Properties properties = new Properties();
    properties.setProperty(SecretProviderRegistry.GRAVITINO_SECRET_PROVIDERS, "memory");
    properties.setProperty(
        SecretProviderRegistry.GRAVITINO_SECRET_PROVIDER_PREFIX
            + "memory."
            + SecretProviderRegistry.CLASS_NAME,
        InMemorySecretsProvider.class.getName());
    config.loadFromProperties(properties);
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(config);

    GravitinoProfiles.base(component);
    SecretProviderRegistry registry = component.secretManager().getRegistry();

    assertEquals(1, registry.listProviders().size());
    assertEquals("memory", registry.getProvider("memory").type());
    component.lifecycle().close();
    assertThrows(IllegalStateException.class, registry::listProviders);
  }

  private static void assertSemanticModelChain(Object dispatcher) throws IllegalAccessException {
    assertInstanceOf(SemanticModelNormalizeDispatcher.class, dispatcher);
    assertInstanceOf(
        SemanticModelOperationDispatcher.class,
        FieldUtils.readField(dispatcher, "dispatcher", true));
  }

  private static Config config() {
    Config config = new Config(false) {};
    config.set(Configs.ENABLE_AUTHORIZATION, false);
    config.set(Configs.SERVICE_ADMINS, Collections.singletonList("admin"));
    config.set(
        Configs.PARTITION_STATS_STORAGE_FACTORY_CLASS,
        MemoryPartitionStatsStorageFactory.class.getCanonicalName());
    return config;
  }

  private static EntityStore relationStore() {
    return mock(
        EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
  }
}
