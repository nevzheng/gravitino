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
package org.apache.gravitino.metalake;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.MetadataObjectChange;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.exceptions.MetalakeAlreadyExistsException;
import org.apache.gravitino.exceptions.MetalakeInUseException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NonEmptyMetalakeException;
import org.apache.gravitino.hook.CatalogHookDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.DropCatalogEvent;
import org.apache.gravitino.listener.api.event.DropCatalogPreEvent;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.memory.TestMemoryEntityStore;
import org.apache.gravitino.storage.memory.TestMemoryEntityStore.InMemoryEntityStore;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.gravitino.utils.ThrowableFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class TestMetalakeManager {

  private static MetalakeManager metalakeManager;

  private static EntityStore entityStore;

  private static Config config;

  @BeforeAll
  public static void setUp() throws IllegalAccessException {
    config = Mockito.mock(Config.class);

    doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);

    entityStore = new TestMemoryEntityStore.InMemoryEntityStore();
    entityStore.initialize(config);

    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    metalakeManager = new MetalakeManager(entityStore, new RandomIdGenerator());
  }

  @AfterAll
  public static void tearDown() throws IOException {
    if (entityStore != null) {
      entityStore.close();
      entityStore = null;
    }
  }

  @Test
  public void testCreateMetalake() {
    NameIdentifier ident = NameIdentifier.of("test1");
    Map<String, String> props = ImmutableMap.of("key1", "value1");

    BaseMetalake metalake = metalakeManager.createMetalake(ident, "comment", props);
    Assertions.assertEquals("test1", metalake.name());
    Assertions.assertEquals("comment", metalake.comment());
    testProperties(props, metalake.properties());

    // Test with MetalakeAlreadyExistsException
    Assertions.assertThrows(
        MetalakeAlreadyExistsException.class,
        () -> metalakeManager.createMetalake(ident, "comment", props));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testNullCatalogManagerConstructorStaysUnambiguous() {
    // Compiles only while a single three-argument constructor accepts an uncast null.
    MetalakeManager manager = new MetalakeManager(entityStore, new RandomIdGenerator(), null);
    NameIdentifier ident = NameIdentifier.of("null_catalog_manager_ml");
    manager.createMetalake(ident, "comment", ImmutableMap.of());
    manager.disableMetalake(ident);

    Assertions.assertTrue(manager.dropMetalake(ident, true));
  }

  @Test
  public void testListMetalakes() {
    NameIdentifier ident1 = NameIdentifier.of("test11");
    NameIdentifier ident2 = NameIdentifier.of("test12");
    Map<String, String> props = ImmutableMap.of("key1", "value1");

    metalakeManager.createMetalake(ident1, "comment", props);
    BaseMetalake metalake1 = metalakeManager.loadMetalake(ident1);
    metalakeManager.createMetalake(ident2, "comment", props);
    BaseMetalake metalake2 = metalakeManager.loadMetalake(ident2);

    Set<BaseMetalake> metalakes = Sets.newHashSet(metalakeManager.listMetalakes());
    Assertions.assertTrue(metalakes.contains(metalake1));
    Assertions.assertTrue(metalakes.contains(metalake2));
  }

  @Test
  public void testLoadMetalake() {
    NameIdentifier ident = NameIdentifier.of("test21");
    Map<String, String> props = ImmutableMap.of("key1", "value1");

    BaseMetalake metalake = metalakeManager.createMetalake(ident, "comment", props);
    Assertions.assertEquals("test21", metalake.name());
    Assertions.assertEquals("comment", metalake.comment());
    testProperties(props, metalake.properties());

    BaseMetalake loadedMetalake = metalakeManager.loadMetalake(ident);
    Assertions.assertEquals("test21", loadedMetalake.name());
    Assertions.assertEquals("comment", loadedMetalake.comment());
    testProperties(props, loadedMetalake.properties());

    // Test with NoSuchMetalakeException
    NameIdentifier id = NameIdentifier.of("test3");
    Throwable exception =
        Assertions.assertThrows(
            NoSuchMetalakeException.class, () -> metalakeManager.loadMetalake(id));
    Assertions.assertTrue(exception.getMessage().contains("Metalake test3 does not exist"));
  }

  @Test
  public void testAlterMetalake() throws Exception {
    NameIdentifier ident = NameIdentifier.of("test31");
    Map<String, String> props = ImmutableMap.of("key1", "value1");

    BaseMetalake metalake = metalakeManager.createMetalake(ident, "comment", props);
    Assertions.assertEquals("test31", metalake.name());
    Assertions.assertEquals("comment", metalake.comment());
    testProperties(props, metalake.properties());

    // Test alter name;
    MetalakeChange change = MetalakeChange.rename("test32");
    BaseMetalake alteredMetalake = metalakeManager.alterMetalake(ident, change);
    Assertions.assertEquals("test32", alteredMetalake.name());
    Assertions.assertEquals("comment", alteredMetalake.comment());
    testProperties(props, alteredMetalake.properties());

    // Test alter comment;
    NameIdentifier ident1 = NameIdentifier.of("test32");
    MetalakeChange change1 = MetalakeChange.updateComment("comment2");
    BaseMetalake alteredMetalake1 = metalakeManager.alterMetalake(ident1, change1);
    Assertions.assertEquals("test32", alteredMetalake1.name());
    Assertions.assertEquals("comment2", alteredMetalake1.comment());
    testProperties(props, alteredMetalake1.properties());

    // test alter properties;
    MetalakeChange change2 = MetalakeChange.setProperty("key2", "value2");
    MetalakeChange change3 = MetalakeChange.setProperty("key3", "value3");
    MetalakeChange change4 = MetalakeChange.removeProperty("key3");

    BaseMetalake alteredMetalake2 =
        metalakeManager.alterMetalake(ident1, change2, change3, change4);
    Assertions.assertEquals("test32", alteredMetalake2.name());
    Assertions.assertEquals("comment2", alteredMetalake2.comment());
    Map<String, String> expectedProps = ImmutableMap.of("key1", "value1", "key2", "value2");
    testProperties(expectedProps, alteredMetalake2.properties());

    // Test with NoSuchMetalakeException
    NameIdentifier id = NameIdentifier.of("test3");
    Throwable exception =
        Assertions.assertThrows(
            NoSuchMetalakeException.class, () -> metalakeManager.alterMetalake(id, change));
    Assertions.assertTrue(exception.getMessage().contains("Metalake test3 does not exist"));

    // Test the audit info
    UserPrincipal userPrincipal = new UserPrincipal("test");
    MetalakeChange change5 = MetalakeChange.setProperty("key5", "value5");
    alteredMetalake =
        PrincipalUtils.doAs(userPrincipal, () -> metalakeManager.alterMetalake(ident1, change5));
    Assertions.assertEquals(userPrincipal.getName(), alteredMetalake.auditInfo().lastModifier());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, alteredMetalake.auditInfo().creator());
  }

  @Test
  public void testDropMetalake() {
    NameIdentifier ident = NameIdentifier.of("test41");
    Map<String, String> props = ImmutableMap.of("key1", "value1");

    BaseMetalake metalake = metalakeManager.createMetalake(ident, "comment", props);
    Assertions.assertEquals("test41", metalake.name());
    Assertions.assertEquals("comment", metalake.comment());
    testProperties(props, metalake.properties());

    metalakeManager.disableMetalake(ident);
    boolean dropped = metalakeManager.dropMetalake(ident);
    Assertions.assertTrue(dropped, "metalake should be dropped");

    // Test with NoSuchMetalakeException
    NameIdentifier ident1 = NameIdentifier.of("test42");
    boolean dropped1 = metalakeManager.dropMetalake(ident1);
    Assertions.assertFalse(dropped1, "metalake should be non-existent");
  }

  @Test
  public void testDropMetalakeReturnsFalseWhenConcurrentDeleteWins() throws IOException {
    InMemoryEntityStore store = Mockito.spy(new InMemoryEntityStore());
    store.initialize(config);
    MetalakeManager manager = new MetalakeManager(store, new RandomIdGenerator());
    NameIdentifier ident = NameIdentifier.of("concurrently_deleted_metalake");
    manager.createMetalake(ident, "comment", ImmutableMap.of());
    Mockito.doThrow(
            new NoSuchEntityException(
                NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE, "metalake", ident.toString()))
        .when(store)
        .delete(ident, EntityType.METALAKE, true);

    Assertions.assertFalse(manager.dropMetalake(ident, true));
    store.close();
  }

  @Test
  public void testListInUseMetalakes() {
    // Create some metalakes with different in-use status
    NameIdentifier ident1 = NameIdentifier.of("metalake1");
    NameIdentifier ident2 = NameIdentifier.of("metalake2");
    NameIdentifier ident3 = NameIdentifier.of("metalake3");

    // Create metalakes - by default they should be in-use (PROPERTY_IN_USE defaults to true)
    metalakeManager.createMetalake(ident1, "comment1", ImmutableMap.of());
    metalakeManager.createMetalake(ident2, "comment2", ImmutableMap.of());
    metalakeManager.createMetalake(ident3, "comment3", ImmutableMap.of());

    // Disable metalake2
    metalakeManager.disableMetalake(ident2);

    // List in-use metalakes
    List<String> inUseMetalakes = MetalakeManager.listInUseMetalakes(entityStore);

    // Should contain metalake1 and metalake3, but not metalake2
    Assertions.assertTrue(inUseMetalakes.contains("metalake1"));
    Assertions.assertFalse(inUseMetalakes.contains("metalake2"));
    Assertions.assertTrue(inUseMetalakes.contains("metalake3"));

    // Cleanup
    metalakeManager.dropMetalake(ident1, true);
    metalakeManager.dropMetalake(ident2, true);
    metalakeManager.dropMetalake(ident3, true);
  }

  @Test
  public void testForceDropMetalakeClosesCachedCatalogs() throws Exception {
    // Dropping a metalake must release the resources of its catalogs (for example JDBC
    // connection pools) exactly as dropping each catalog does, instead of leaving the cached
    // catalog instances alive until cache expiry.
    Config catalogConfig = new Config(false) {};
    catalogConfig.set(Configs.CATALOG_LOAD_ISOLATED, false);
    InMemoryEntityStore store = new InMemoryEntityStore();
    store.initialize(catalogConfig);
    CatalogManager catalogManager =
        new CatalogManager(
            catalogConfig, store, new RandomIdGenerator(), new SecretManager(catalogConfig));
    MetalakeManager manager =
        MetalakeManager.withCatalogDispatcher(store, new RandomIdGenerator(), () -> catalogManager);

    NameIdentifier metalakeIdent = NameIdentifier.of("force_drop_closes_catalogs_ml");
    manager.createMetalake(metalakeIdent, "comment", ImmutableMap.of());
    NameIdentifier catalogIdent = NameIdentifier.of(metalakeIdent.name(), "cached_catalog");
    catalogManager.createCatalog(
        catalogIdent,
        Catalog.Type.RELATIONAL,
        "test",
        "comment",
        ImmutableMap.of(
            "provider", "test", "key1", "value1", "key2", "value2", "key5-1", "value3"));
    // createCatalog caches the wrapper; loadCatalog keeps it warm the same way a schema listing
    // against the catalog would.
    catalogManager.loadCatalog(catalogIdent);
    CatalogManager.CatalogWrapper wrapper =
        catalogManager.getCatalogCache().getIfPresent(catalogIdent);
    Assertions.assertNotNull(wrapper);
    Assertions.assertNotNull(wrapper.catalog());

    Assertions.assertTrue(manager.dropMetalake(metalakeIdent, true));

    Assertions.assertNull(catalogManager.getCatalogCache().getIfPresent(catalogIdent));
    Assertions.assertFalse(store.exists(catalogIdent, Entity.EntityType.CATALOG));
    // The cache removal listener retires the wrapper asynchronously; once cleaned up, the wrapper
    // drops its catalog reference.
    await().atMost(10, TimeUnit.SECONDS).until(() -> wrapper.catalog() == null);

    catalogManager.close();
    store.close();
  }

  @Test
  public void testForceDropMetalakeDropsCatalogsThroughTheCatalogChain() throws Exception {
    // A forced metalake drop must drop each child catalog the way a user's forced catalog drop
    // does: through the catalog chain, so the drop events fire and the catalog hook removes the
    // catalog from the authorization plugins before the catalog manager drops it.
    Config catalogConfig = new Config(false) {};
    catalogConfig.set(Configs.CATALOG_LOAD_ISOLATED, false);
    InMemoryEntityStore store = new InMemoryEntityStore();
    store.initialize(catalogConfig);
    CatalogManager catalogManager =
        new CatalogManager(
            catalogConfig, store, new RandomIdGenerator(), new SecretManager(catalogConfig));
    // The catalog hook reaches the authorization plugins through GravitinoEnv's catalog manager.
    AuthorizationPlugin plugin = Mockito.mock(AuthorizationPlugin.class);
    CatalogManager pluginCatalogs = Mockito.mock(CatalogManager.class);
    Mockito.doAnswer(
            invocation -> {
              NameIdentifier ident = invocation.getArgument(0);
              BaseCatalog<?> catalog = Mockito.mock(BaseCatalog.class);
              Mockito.when(catalog.name()).thenReturn(ident.name());
              Mockito.when(catalog.getAuthorizationPlugin()).thenReturn(plugin);
              ThrowableFunction<BaseCatalog, Object> operation = invocation.getArgument(1);
              return operation.apply(catalog);
            })
        .when(pluginCatalogs)
        .doWithCatalog(Mockito.any(), Mockito.any());
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", pluginCatalogs, true);
    try {
      EventBus eventBus = Mockito.mock(EventBus.class);
      CatalogDispatcher publicCatalogs =
          new CatalogEventDispatcher(
              eventBus, new CatalogNormalizeDispatcher(new CatalogHookDispatcher(catalogManager)));
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              store, new RandomIdGenerator(), () -> publicCatalogs);

      NameIdentifier metalakeIdent = NameIdentifier.of("force_drop_through_chain_ml");
      manager.createMetalake(metalakeIdent, "comment", ImmutableMap.of());
      List<NameIdentifier> catalogIdents =
          List.of(
              NameIdentifier.of(metalakeIdent.name(), "child_catalog_1"),
              NameIdentifier.of(metalakeIdent.name(), "child_catalog_2"));
      for (NameIdentifier catalogIdent : catalogIdents) {
        catalogManager.createCatalog(
            catalogIdent,
            Catalog.Type.RELATIONAL,
            "test",
            "comment",
            ImmutableMap.of("key1", "value1", "key2", "value2", "key5-1", "value3"));
      }
      manager.disableMetalake(metalakeIdent);

      Assertions.assertTrue(manager.dropMetalake(metalakeIdent, true));

      ArgumentCaptor<BaseEvent> events = ArgumentCaptor.forClass(BaseEvent.class);
      Mockito.verify(eventBus, Mockito.times(2 * catalogIdents.size()))
          .dispatchEvent(events.capture());
      ArgumentCaptor<MetadataObjectChange> changes =
          ArgumentCaptor.forClass(MetadataObjectChange.class);
      Mockito.verify(plugin, Mockito.times(catalogIdents.size()))
          .onMetadataUpdated(changes.capture());
      for (NameIdentifier catalogIdent : catalogIdents) {
        Assertions.assertTrue(
            events.getAllValues().stream()
                .anyMatch(
                    e -> e instanceof DropCatalogPreEvent && catalogIdent.equals(e.identifier())),
            "no drop pre-event for " + catalogIdent);
        Assertions.assertTrue(
            events.getAllValues().stream()
                .anyMatch(
                    e ->
                        e instanceof DropCatalogEvent
                            && catalogIdent.equals(e.identifier())
                            && ((DropCatalogEvent) e).isExists()),
            "no drop event for " + catalogIdent);
        Assertions.assertTrue(
            changes.getAllValues().stream()
                .map(
                    change -> ((MetadataObjectChange.RemoveMetadataObject) change).metadataObject())
                .anyMatch(
                    object ->
                        object.type() == MetadataObject.Type.CATALOG
                            && object.name().equals(catalogIdent.name())),
            "the authorization plugin was not told " + catalogIdent + " is removed");
        Assertions.assertFalse(store.exists(catalogIdent, Entity.EntityType.CATALOG));
      }
      Assertions.assertFalse(store.exists(metalakeIdent, Entity.EntityType.METALAKE));
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
      catalogManager.close();
      store.close();
    }
  }

  @Test
  public void testForceDropMetalakeWithoutCatalogDispatcher() throws Exception {
    // Without a catalog dispatcher, a forced drop leaves the child catalogs to the entity store,
    // which removes them with the metalake.
    InMemoryEntityStore store = new InMemoryEntityStore();
    store.initialize(config);
    MetalakeManager manager = new MetalakeManager(store, new RandomIdGenerator());
    NameIdentifier metalakeIdent = NameIdentifier.of("force_drop_without_dispatcher_ml");
    manager.createMetalake(metalakeIdent, "comment", ImmutableMap.of());

    Assertions.assertTrue(manager.dropMetalake(metalakeIdent, true));
    Assertions.assertFalse(store.exists(metalakeIdent, Entity.EntityType.METALAKE));

    store.close();
  }

  @Test
  public void testForceDropMetalakeAfterDisableDropsLeftoverCatalogs() throws Exception {
    // Mirrors IT tearDown: disableMetalake then dropMetalake(force=true) while a catalog entity
    // may still remain. CatalogManager.dropCatalog requires metalake-in-use on the catalog.
    InMemoryEntityStore store = new InMemoryEntityStore();
    store.initialize(config);
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Mockito.when(catalogManager.dropCatalog(Mockito.any(NameIdentifier.class), Mockito.eq(true)))
        .thenReturn(true);
    Mockito.doNothing()
        .when(catalogManager)
        .setMetalakeInUseStatus(Mockito.any(NameIdentifier.class), Mockito.anyBoolean());

    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    MetalakeManager manager =
        MetalakeManager.withCatalogDispatcher(store, new RandomIdGenerator(), () -> catalogManager);

    NameIdentifier metalakeIdent = NameIdentifier.of("force_drop_leftover_ml");
    manager.createMetalake(metalakeIdent, "comment", ImmutableMap.of());
    store.put(
        CatalogEntity.builder()
            .withId(99L)
            .withName("leftover_catalog")
            .withNamespace(Namespace.of(metalakeIdent.name()))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build(),
        true);

    manager.disableMetalake(metalakeIdent);
    Assertions.assertTrue(manager.dropMetalake(metalakeIdent, true));
    Mockito.verify(catalogManager)
        .dropCatalog(NameIdentifier.of(metalakeIdent.name(), "leftover_catalog"), true);

    store.close();
  }

  @Test
  public void testFailedForceDropKeepsDisabledMetalakeDisabled() throws Exception {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogManager);
      NameIdentifier ident = NameIdentifier.of("metalake_force_drop_failure");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      manager.disableMetalake(ident);
      Assertions.assertFalse(MetalakeManager.metalakeInUse(entityStore, ident));

      entityStore.put(
          CatalogEntity.builder()
              .withId(new RandomIdGenerator().nextId())
              .withName("catalog1")
              .withNamespace(Namespace.of(ident.name()))
              .withType(Catalog.Type.RELATIONAL)
              .withProvider("hive")
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build(),
          false);

      Mockito.doThrow(new RuntimeException("catalog drop failed"))
          .when(catalogManager)
          .dropCatalog(Mockito.any(), Mockito.anyBoolean());

      Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      // A failed force drop must not leave a user-disabled metalake re-enabled.
      Assertions.assertFalse(
          MetalakeManager.metalakeInUse(entityStore, ident),
          "failed force drop must keep the user-disabled metalake disabled");
      Assertions.assertTrue(entityStore.exists(ident, EntityType.METALAKE));
      Assertions.assertTrue(
          entityStore.exists(NameIdentifier.of(ident.name(), "catalog1"), EntityType.CATALOG));
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
    }
  }

  @Test
  public void testForceDropRestoresDisabledMetalakeWhenDeleteFails() throws Exception {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    try {
      EntityStore spyStore = Mockito.spy(entityStore);
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              spyStore, new RandomIdGenerator(), () -> catalogManager);
      NameIdentifier ident = NameIdentifier.of("metalake_force_drop_delete_failure");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      manager.disableMetalake(ident);
      Assertions.assertFalse(MetalakeManager.metalakeInUse(spyStore, ident));

      // No catalogs, so phase-1 catalog cleanup succeeds and re-enables the metalake; make the
      // phase-2 metalake delete fail so the temporary enable outlives the cleanup handler.
      Mockito.doThrow(new IOException("metalake delete failed"))
          .when(spyStore)
          .delete(ident, EntityType.METALAKE, true);

      Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      Assertions.assertFalse(
          MetalakeManager.metalakeInUse(spyStore, ident),
          "a force drop whose metalake delete fails must keep the user-disabled metalake disabled");
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
    }
  }

  @Test
  public void testFailedForceDropDoesNotDisableMetalakeItDidNotEnable() throws Exception {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogManager);
      NameIdentifier ident = NameIdentifier.of("metalake_force_drop_already_enabled");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      // Left enabled: this stands in for a metalake a concurrent user has enabled. The force drop
      // does not enable it, so a failure must not re-disable it and clobber that user's enable.
      Assertions.assertTrue(MetalakeManager.metalakeInUse(entityStore, ident));

      entityStore.put(
          CatalogEntity.builder()
              .withId(new RandomIdGenerator().nextId())
              .withName("catalog1")
              .withNamespace(Namespace.of(ident.name()))
              .withType(Catalog.Type.RELATIONAL)
              .withProvider("hive")
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build(),
          false);

      Mockito.doThrow(new RuntimeException("catalog drop failed"))
          .when(catalogManager)
          .dropCatalog(Mockito.any(), Mockito.anyBoolean());

      Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      Assertions.assertTrue(
          MetalakeManager.metalakeInUse(entityStore, ident),
          "a failed force drop must not disable a metalake it did not enable");
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
    }
  }

  @Test
  public void testFailedForceDropRestoresWhenTemporaryEnableFailsMidway() throws Exception {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogManager);
      NameIdentifier ident = NameIdentifier.of("metalake_force_drop_enable_midfail");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      manager.disableMetalake(ident);
      Assertions.assertFalse(MetalakeManager.metalakeInUse(entityStore, ident));

      entityStore.put(
          CatalogEntity.builder()
              .withId(new RandomIdGenerator().nextId())
              .withName("catalog1")
              .withNamespace(Namespace.of(ident.name()))
              .withType(Catalog.Type.RELATIONAL)
              .withProvider("hive")
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build(),
          false);

      // The temporary enable flips the metalake in-use, then fails while propagating that to the
      // catalog. A metalake this force drop enabled must not be left enabled.
      Mockito.doThrow(new RuntimeException("catalog in-use update failed"))
          .when(catalogManager)
          .setMetalakeInUseStatus(Mockito.any(), Mockito.anyBoolean());

      Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      Assertions.assertFalse(
          MetalakeManager.metalakeInUse(entityStore, ident),
          "a temporary enable that fails partway must not leave the metalake enabled");
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
    }
  }

  @Test
  public void testForceDropDeleteFailureDoesNotDisableAlreadyEnabledMetalake() throws Exception {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    Object originalEnvCatalogManager =
        FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    try {
      EntityStore spyStore = Mockito.spy(entityStore);
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              spyStore, new RandomIdGenerator(), () -> catalogManager);
      NameIdentifier ident = NameIdentifier.of("metalake_force_drop_enabled_delete_fail");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      // Left enabled (a concurrent user's enable); no catalogs, so phase 1 succeeds without this
      // operation enabling it, then the phase-2 metalake delete fails.
      Assertions.assertTrue(MetalakeManager.metalakeInUse(spyStore, ident));

      Mockito.doThrow(new IOException("metalake delete failed"))
          .when(spyStore)
          .delete(ident, EntityType.METALAKE, true);

      Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      Assertions.assertTrue(
          MetalakeManager.metalakeInUse(spyStore, ident),
          "a delete-failed force drop must not disable a metalake it did not enable");
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "catalogManager", originalEnvCatalogManager, true);
    }
  }

  @Test
  public void testForceDropDropsEachCatalogThroughTheSuppliedDispatcher() throws Exception {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    Mockito.when(catalogs.dropCatalog(Mockito.any(NameIdentifier.class), Mockito.eq(true)))
        .thenReturn(true);
    CatalogManager envCatalogManager = Mockito.mock(CatalogManager.class);
    Object original = useEnvCatalogManager(envCatalogManager);
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogs);
      NameIdentifier ident = NameIdentifier.of("force_drop_supplied_dispatcher_ml");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      putCatalog(entityStore, ident, "catalog1");
      putCatalog(entityStore, ident, "catalog2");
      manager.disableMetalake(ident);

      Assertions.assertTrue(manager.dropMetalake(ident, true));

      Mockito.verify(catalogs).dropCatalog(NameIdentifier.of(ident.name(), "catalog1"), true);
      Mockito.verify(catalogs).dropCatalog(NameIdentifier.of(ident.name(), "catalog2"), true);
      Mockito.verify(catalogs, Mockito.times(2))
          .dropCatalog(Mockito.any(NameIdentifier.class), Mockito.anyBoolean());
      // The catalog manager only marks the catalogs in use; it never drops them itself.
      Mockito.verify(envCatalogManager, Mockito.never())
          .dropCatalog(Mockito.any(NameIdentifier.class), Mockito.anyBoolean());
      Assertions.assertFalse(entityStore.exists(ident, EntityType.METALAKE));
    } finally {
      useEnvCatalogManager(original);
    }
  }

  @Test
  public void testNonForceDropOfMetalakeWithCatalogsIsRefused() throws Exception {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    AtomicInteger lookups = new AtomicInteger();
    Object original = useEnvCatalogManager(Mockito.mock(CatalogManager.class));
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore,
              new RandomIdGenerator(),
              () -> {
                lookups.incrementAndGet();
                return catalogs;
              });
      NameIdentifier ident = NameIdentifier.of("non_force_drop_with_catalogs_ml");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      putCatalog(entityStore, ident, "catalog1");
      manager.disableMetalake(ident);

      Assertions.assertThrows(
          NonEmptyMetalakeException.class, () -> manager.dropMetalake(ident, false));

      Assertions.assertEquals(0, lookups.get(), "a drop without force must not drop catalogs");
      Mockito.verifyNoInteractions(catalogs);
      Assertions.assertTrue(entityStore.exists(ident, EntityType.METALAKE));
      Assertions.assertTrue(
          entityStore.exists(NameIdentifier.of(ident.name(), "catalog1"), EntityType.CATALOG));
    } finally {
      useEnvCatalogManager(original);
    }
  }

  @Test
  public void testNonForceDropOfInUseMetalakeIsRefused() {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    MetalakeManager manager =
        MetalakeManager.withCatalogDispatcher(entityStore, new RandomIdGenerator(), () -> catalogs);
    NameIdentifier ident = NameIdentifier.of("non_force_drop_in_use_ml");
    manager.createMetalake(ident, "comment", ImmutableMap.of());

    Assertions.assertThrows(MetalakeInUseException.class, () -> manager.dropMetalake(ident, false));

    Mockito.verifyNoInteractions(catalogs);
    Assertions.assertTrue(MetalakeManager.metalakeInUse(entityStore, ident));
  }

  @Test
  public void testForceDropStopsWhenACatalogDropFailsMidway() throws Exception {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    AtomicInteger drops = new AtomicInteger();
    Mockito.when(catalogs.dropCatalog(Mockito.any(NameIdentifier.class), Mockito.eq(true)))
        .thenAnswer(
            invocation -> {
              if (drops.incrementAndGet() == 2) {
                throw new RuntimeException("catalog drop failed");
              }
              return true;
            });
    Object original = useEnvCatalogManager(Mockito.mock(CatalogManager.class));
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogs);
      NameIdentifier ident = NameIdentifier.of("force_drop_fails_midway_ml");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      putCatalog(entityStore, ident, "catalog1");
      putCatalog(entityStore, ident, "catalog2");
      putCatalog(entityStore, ident, "catalog3");
      manager.disableMetalake(ident);

      RuntimeException thrown =
          Assertions.assertThrows(RuntimeException.class, () -> manager.dropMetalake(ident, true));

      Assertions.assertEquals("catalog drop failed", thrown.getMessage());
      // The drop stops at the failing catalog, and the metalake is kept and disabled again.
      Mockito.verify(catalogs, Mockito.times(2))
          .dropCatalog(Mockito.any(NameIdentifier.class), Mockito.eq(true));
      Assertions.assertTrue(entityStore.exists(ident, EntityType.METALAKE));
      Assertions.assertFalse(MetalakeManager.metalakeInUse(entityStore, ident));
    } finally {
      useEnvCatalogManager(original);
    }
  }

  @Test
  public void testForceDropOfEmptyMetalakeDropsNoCatalog() throws Exception {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    Object original = useEnvCatalogManager(Mockito.mock(CatalogManager.class));
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore, new RandomIdGenerator(), () -> catalogs);
      NameIdentifier ident = NameIdentifier.of("force_drop_empty_ml");
      manager.createMetalake(ident, "comment", ImmutableMap.of());
      manager.disableMetalake(ident);

      Assertions.assertTrue(manager.dropMetalake(ident, true));

      Mockito.verifyNoInteractions(catalogs);
      Assertions.assertFalse(entityStore.exists(ident, EntityType.METALAKE));
    } finally {
      useEnvCatalogManager(original);
    }
  }

  @Test
  public void testCatalogDispatcherIsReadOnEachForceDrop() throws Exception {
    CatalogDispatcher catalogs = Mockito.mock(CatalogDispatcher.class);
    AtomicInteger lookups = new AtomicInteger();
    Object original = useEnvCatalogManager(Mockito.mock(CatalogManager.class));
    try {
      MetalakeManager manager =
          MetalakeManager.withCatalogDispatcher(
              entityStore,
              new RandomIdGenerator(),
              () -> {
                lookups.incrementAndGet();
                return catalogs;
              });
      Assertions.assertEquals(0, lookups.get(), "the dispatcher must not be read when built");

      for (String name : List.of("lazy_dispatcher_ml1", "lazy_dispatcher_ml2")) {
        NameIdentifier ident = NameIdentifier.of(name);
        manager.createMetalake(ident, "comment", ImmutableMap.of());
        manager.disableMetalake(ident);
        Assertions.assertTrue(manager.dropMetalake(ident, true));
      }

      Assertions.assertEquals(2, lookups.get());
    } finally {
      useEnvCatalogManager(original);
    }
  }

  /** Installs a catalog manager in GravitinoEnv and returns the one it replaced. */
  private static Object useEnvCatalogManager(Object catalogManager) throws IllegalAccessException {
    Object original = FieldUtils.readField(GravitinoEnv.getInstance(), "catalogManager", true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    return original;
  }

  private static void putCatalog(EntityStore store, NameIdentifier metalake, String name)
      throws IOException {
    store.put(
        CatalogEntity.builder()
            .withId(new RandomIdGenerator().nextId())
            .withName(name)
            .withNamespace(Namespace.of(metalake.name()))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build(),
        false);
  }

  private void testProperties(Map<String, String> expectedProps, Map<String, String> testProps) {
    expectedProps.forEach(
        (k, v) -> {
          Assertions.assertEquals(v, testProps.get(k));
        });

    Assertions.assertFalse(testProps.containsKey(StringIdentifier.ID_KEY));
  }
}
