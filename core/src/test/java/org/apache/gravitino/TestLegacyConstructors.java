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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.catalog.ViewOperationDispatcher;
import org.apache.gravitino.hook.SchemaHookDispatcher;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.lock.TreeLock;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.stats.storage.MemoryPartitionStatsStorageFactory;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the deprecated constructors kept for callers outside this repository: they must still exist
 * and read the lock manager from {@code GravitinoEnv} each time, not once at construction.
 */
@SuppressWarnings("deprecation")
public class TestLegacyConstructors {

  private static final NameIdentifier IDENT = NameIdentifier.of("metalake", "catalog");

  private final GravitinoEnv env = GravitinoEnv.getInstance();
  private final CatalogManager catalogManager = mock(CatalogManager.class);
  private final EntityStore store = mock(EntityStore.class);
  private final IdGenerator idGenerator = mock(IdGenerator.class);
  private final SecretManager secretManager = mock(SecretManager.class);
  private final SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);

  private Object savedLockManager;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    savedLockManager = FieldUtils.readField(env, "lockManager", true);
    setEnvLockManager(null);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    FieldUtils.writeField(env, "lockManager", savedLockManager, true);
  }

  @Test
  void testDeprecatedConstructorsResolveTheLockManagerLazily() throws Exception {
    List<Object> legacy =
        List.of(
            new FilesetOperationDispatcher(catalogManager, store, idGenerator, secretManager),
            new SchemaOperationDispatcher(catalogManager, store, idGenerator, secretManager),
            new SchemaOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, mock(FilesetDispatcher.class)),
            new TopicOperationDispatcher(catalogManager, store, idGenerator, secretManager),
            new TableOperationDispatcher(catalogManager, store, idGenerator, secretManager),
            new TableOperationDispatcher(
                catalogManager, store, idGenerator, this::schemaDispatcher, secretManager),
            new ViewOperationDispatcher(catalogManager, store, idGenerator, secretManager),
            new ViewOperationDispatcher(
                catalogManager, store, idGenerator, this::schemaDispatcher, secretManager),
            new SchemaHookDispatcher(mock(SchemaDispatcher.class)));

    // Built while GravitinoEnv has no lock manager, so nothing may have been captured yet.
    LockManager first = mock(LockManager.class);
    setEnvLockManager(first);
    for (Object dispatcher : legacy) {
      Assertions.assertSame(first, lockManagerOf(dispatcher), dispatcher.getClass().getName());
    }

    LockManager second = mock(LockManager.class);
    setEnvLockManager(second);
    for (Object dispatcher : legacy) {
      Assertions.assertSame(second, lockManagerOf(dispatcher), dispatcher.getClass().getName());
    }
  }

  @Test
  void testNewConstructorsKeepTheGivenLockManager() throws Exception {
    LockManager lockManager = mock(LockManager.class);
    List<Object> injected =
        List.of(
            new FilesetOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, lockManager),
            new SchemaOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, lockManager),
            new SchemaOperationDispatcher(
                catalogManager,
                store,
                idGenerator,
                secretManager,
                mock(FilesetDispatcher.class),
                lockManager),
            new TopicOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, lockManager),
            new TableOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, lockManager),
            new TableOperationDispatcher(
                catalogManager,
                store,
                idGenerator,
                this::schemaDispatcher,
                secretManager,
                lockManager),
            new ViewOperationDispatcher(
                catalogManager, store, idGenerator, secretManager, lockManager),
            new ViewOperationDispatcher(
                catalogManager,
                store,
                idGenerator,
                this::schemaDispatcher,
                secretManager,
                lockManager),
            new SchemaHookDispatcher(mock(SchemaDispatcher.class), lockManager));

    setEnvLockManager(mock(LockManager.class));
    for (Object dispatcher : injected) {
      Assertions.assertSame(
          lockManager, lockManagerOf(dispatcher), dispatcher.getClass().getName());
    }
  }

  @Test
  void testRestoredConstructorsForwardToTheEnvLockManager(@TempDir Path stagingDir)
      throws Exception {
    Config config = new Config(false) {};
    config.set(Configs.SERVICE_ADMINS, List.of("admin"));
    config.set(
        Configs.PARTITION_STATS_STORAGE_FACTORY_CLASS,
        MemoryPartitionStatsStorageFactory.class.getName());
    config.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    when(store.list(any(Namespace.class), eq(BaseMetalake.class), eq(Entity.EntityType.METALAKE)))
        .thenReturn(Collections.emptyList());

    // Built while GravitinoEnv has no lock manager, so nothing may have been captured yet.
    CatalogManager legacyCatalogManager =
        new CatalogManager(config, store, idGenerator, secretManager);
    StatisticManager statisticManager = new StatisticManager(store, idGenerator, config);
    JobManager jobManager = new JobManager(config, store, idGenerator);
    try {
      List<Object> legacy =
          new ArrayList<>(
              List.of(
                  legacyCatalogManager,
                  new AccessControlManager(store, idGenerator, config),
                  statisticManager,
                  jobManager,
                  new BuiltInJobTemplateEventListener(jobManager, store, idGenerator),
                  new FunctionOperationDispatcher(
                      catalogManager,
                      mock(SchemaOperationDispatcher.class),
                      store,
                      idGenerator,
                      secretManager)));

      LockManager first = lockManagerWithTreeLocks();
      setEnvLockManager(first);
      // A metalake manager lists the metalakes under a tree lock while it is built.
      legacy.add(new MetalakeManager(store, idGenerator));
      legacy.add(new MetalakeManager(store, idGenerator, catalogManager));
      for (Object manager : legacy) {
        forwardedLockManager(manager).createTreeLock(IDENT);
        verify(first).createTreeLock(IDENT);
        clearInvocations(first);
      }

      LockManager second = lockManagerWithTreeLocks();
      setEnvLockManager(second);
      for (Object manager : legacy) {
        forwardedLockManager(manager).createTreeLock(IDENT);
        verify(second).createTreeLock(IDENT);
        clearInvocations(second);
      }
      verify(first, never()).createTreeLock(IDENT);
    } finally {
      jobManager.close();
      statisticManager.close();
      legacyCatalogManager.close();
    }
  }

  @Test
  void testDeprecatedListInUseMetalakesUsesTheEnvLockManager() throws Exception {
    LockManager lockManager = mock(LockManager.class);
    when(lockManager.createTreeLock(any())).thenReturn(mock(TreeLock.class));
    when(store.list(any(Namespace.class), eq(BaseMetalake.class), eq(Entity.EntityType.METALAKE)))
        .thenReturn(Collections.emptyList());
    setEnvLockManager(lockManager);

    Assertions.assertEquals(Collections.emptyList(), MetalakeManager.listInUseMetalakes(store));
    verify(lockManager).createTreeLock(any());
  }

  private SchemaDispatcher schemaDispatcher() {
    return schemaDispatcher;
  }

  private static LockManager lockManagerWithTreeLocks() {
    LockManager lockManager = mock(LockManager.class);
    when(lockManager.createTreeLock(any())).thenReturn(mock(TreeLock.class));
    return lockManager;
  }

  private void setEnvLockManager(LockManager lockManager) throws IllegalAccessException {
    FieldUtils.writeField(env, "lockManager", lockManager, true);
  }

  @SuppressWarnings("unchecked")
  private static LockManager lockManagerOf(Object dispatcher) throws IllegalAccessException {
    return ((Supplier<LockManager>) field(dispatcher, "lockManagerSupplier")).get();
  }

  private static LockManager forwardedLockManager(Object manager) throws IllegalAccessException {
    return (LockManager) field(manager, "lockManager");
  }

  private static Object field(Object target, String name) throws IllegalAccessException {
    return FieldUtils.readField(target, name, true);
  }
}
