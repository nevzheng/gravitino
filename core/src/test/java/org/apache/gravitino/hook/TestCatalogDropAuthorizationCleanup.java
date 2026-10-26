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
package org.apache.gravitino.hook;

import static org.apache.gravitino.TestCatalog.PROPERTY_KEY1;
import static org.apache.gravitino.TestCatalog.PROPERTY_KEY2;
import static org.apache.gravitino.TestCatalog.PROPERTY_KEY5_PREFIX;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.authorization.TestCleanupResultAuthorization;
import org.apache.gravitino.exceptions.AuthorizationPluginException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.memory.TestMemoryEntityStore.InMemoryEntityStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Drops catalogs through the hook dispatcher, a real {@link CatalogManager} and a real catalog
 * authorization plugin, to check that a catalog is only dropped once its privileges are removed.
 */
public class TestCatalogDropAuthorizationCleanup {
  private static final String METALAKE = "metalake";
  private static final String[] ENV_FIELDS = {
    "catalogManager", "internalCatalogDispatcher", "internalAccessControlDispatcher", "lockManager"
  };

  private final Map<String, Object> savedEnvFields = Maps.newHashMap();
  private EntityStore entityStore;
  private CatalogManager catalogManager;
  private CatalogHookDispatcher hookDispatcher;

  @BeforeEach
  public void setUp() throws Exception {
    TestCleanupResultAuthorization.reset();
    Config config = new Config(false) {};
    config.set(Configs.CATALOG_LOAD_ISOLATED, false);
    entityStore = new InMemoryEntityStore();
    entityStore.initialize(config);
    entityStore.put(
        BaseMetalake.builder()
            .withId(1L)
            .withName(METALAKE)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .withVersion(SchemaVersion.V_0_1)
            .build(),
        true);
    catalogManager =
        new CatalogManager(config, entityStore, new RandomIdGenerator(), new SecretManager(config));

    GravitinoEnv env = GravitinoEnv.getInstance();
    for (String field : ENV_FIELDS) {
      savedEnvFields.put(field, FieldUtils.readField(env, field, true));
    }
    FieldUtils.writeField(env, "catalogManager", catalogManager, true);
    FieldUtils.writeField(env, "internalCatalogDispatcher", catalogManager, true);
    // A non-null access control dispatcher is how the hooks know authorization is enabled.
    FieldUtils.writeField(
        env, "internalAccessControlDispatcher", Mockito.mock(AccessControlDispatcher.class), true);
    FieldUtils.writeField(env, "lockManager", new LockManager(config), true);
    hookDispatcher = new CatalogHookDispatcher(catalogManager);
  }

  @AfterEach
  public void tearDown() throws Exception {
    GravitinoEnv env = GravitinoEnv.getInstance();
    for (Map.Entry<String, Object> field : savedEnvFields.entrySet()) {
      FieldUtils.writeField(env, field.getKey(), field.getValue(), true);
    }
    catalogManager.close();
    entityStore.close();
    TestCleanupResultAuthorization.reset();
  }

  @Test
  public void testDropCatalogRemovesPrivilegesThenDrops() {
    NameIdentifier ident = createCatalog("cleaned", true);

    Assertions.assertTrue(hookDispatcher.dropCatalog(ident, true));

    Assertions.assertEquals(1, TestCleanupResultAuthorization.METADATA_UPDATES.get());
    Assertions.assertFalse(catalogManager.catalogExists(ident));
  }

  @Test
  public void testDropCatalogKeepsCatalogWhenPluginCleanupFails() {
    NameIdentifier ident = createCatalog("kept", true);
    TestCleanupResultAuthorization.metadataUpdateResult = Boolean.FALSE;

    AuthorizationPluginException e =
        Assertions.assertThrows(
            AuthorizationPluginException.class, () -> hookDispatcher.dropCatalog(ident, true));

    Assertions.assertTrue(e.getMessage().contains(ident.toString()), e.getMessage());
    Assertions.assertEquals(1, TestCleanupResultAuthorization.METADATA_UPDATES.get());
    Assertions.assertTrue(catalogManager.catalogExists(ident));
  }

  @Test
  public void testDropCatalogSucceedsOnRetryAfterPluginCleanupRecovers() {
    NameIdentifier ident = createCatalog("retried", true);
    TestCleanupResultAuthorization.metadataUpdateResult = Boolean.FALSE;
    Assertions.assertThrows(
        AuthorizationPluginException.class, () -> hookDispatcher.dropCatalog(ident, true));

    TestCleanupResultAuthorization.metadataUpdateResult = Boolean.TRUE;

    Assertions.assertTrue(hookDispatcher.dropCatalog(ident, true));
    Assertions.assertEquals(2, TestCleanupResultAuthorization.METADATA_UPDATES.get());
    Assertions.assertFalse(catalogManager.catalogExists(ident));
  }

  @Test
  public void testDropCatalogWithoutAuthorizationProviderIsNotBlocked() {
    NameIdentifier ident = createCatalog("plain", false);
    TestCleanupResultAuthorization.metadataUpdateResult = Boolean.FALSE;

    Assertions.assertTrue(hookDispatcher.dropCatalog(ident, true));

    Assertions.assertEquals(0, TestCleanupResultAuthorization.METADATA_UPDATES.get());
    Assertions.assertFalse(catalogManager.catalogExists(ident));
  }

  private NameIdentifier createCatalog(String name, boolean withAuthorization) {
    NameIdentifier ident = NameIdentifier.of(METALAKE, name);
    ImmutableMap.Builder<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put(PROPERTY_KEY1, "value1")
            .put(PROPERTY_KEY2, "value2")
            .put(PROPERTY_KEY5_PREFIX + "1", "value3");
    if (withAuthorization) {
      properties.put(Catalog.AUTHORIZATION_PROVIDER, TestCleanupResultAuthorization.SHORT_NAME);
    }
    catalogManager.createCatalog(
        ident, Catalog.Type.RELATIONAL, "test", "comment", properties.build());
    return ident;
  }
}
