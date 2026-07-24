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
package org.apache.gravitino.storage.relational;

import static org.apache.gravitino.Configs.GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.apache.gravitino.Configs.VERSION_RETENTION_COUNT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Tests relational garbage collector orchestration. */
public class TestRelationalGarbageCollector {

  @Test
  public void testCollectOrphanedRelationsUntilBatchIsEmpty() throws Exception {
    RelationalBackend backend =
        mock(
            RelationalBackend.class,
            withSettings().extraInterfaces(SupportsOrphanedRelationCleanup.class));
    SupportsOrphanedRelationCleanup orphanedRelationCleanup =
        (SupportsOrphanedRelationCleanup) backend;
    Config config = mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(1000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);
    when(orphanedRelationCleanup.softDeleteOrphanedRelations(
            MetadataObject.Type.TABLE, GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT))
        .thenReturn(2, 0);

    new RelationalGarbageCollector(backend, config).collectAndClean();

    for (MetadataObject.Type type : MetadataObject.Type.values()) {
      int expectedInvocations = type == MetadataObject.Type.TABLE ? 2 : 1;
      verify(orphanedRelationCleanup, times(expectedInvocations))
          .softDeleteOrphanedRelations(type, GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT);
    }
  }

  @Test
  public void testBackendWithoutOrphanedRelationCleanupIsSkipped() {
    RelationalBackend backend = mock(RelationalBackend.class);
    Config config = mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(1000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    Assertions.assertDoesNotThrow(
        () -> new RelationalGarbageCollector(backend, config).collectAndClean());
  }

  @Test
  public void testTableFailureStopsDependentHardDeletesForCycle() throws Exception {
    Config config = mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(600_000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    RelationalBackend backend = mock(RelationalBackend.class);
    when(backend.hardDeleteLegacyData(any(), anyLong())).thenReturn(0);
    when(backend.hardDeleteLegacyData(eq(Entity.EntityType.TABLE), anyLong()))
        .thenThrow(new IllegalStateException("table deletion purge failed"));

    try (RelationalGarbageCollector garbageCollector =
        new RelationalGarbageCollector(backend, config)) {
      garbageCollector.collectAndClean();
    }

    verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.TABLE), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.METALAKE), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.VIEW), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.COLUMN), anyLong());
  }

  @Test
  void testModelFailureStopsDependentHardDeletesForCycle() throws Exception {
    Config config = Mockito.mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(600_000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    RelationalBackend backend = Mockito.mock(RelationalBackend.class);
    when(backend.hardDeleteLegacyData(Mockito.any(), anyLong())).thenReturn(0);
    when(backend.hardDeleteLegacyData(eq(Entity.EntityType.MODEL), anyLong()))
        .thenThrow(new IllegalStateException("model deletion purge failed"));

    try (RelationalGarbageCollector garbageCollector =
        new RelationalGarbageCollector(backend, config)) {
      garbageCollector.collectAndClean();
    }

    InOrder aggregateOrder = inOrder(backend);
    aggregateOrder.verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.TABLE), anyLong());
    aggregateOrder.verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.MODEL), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.FUNCTION), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.METALAKE), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.MODEL_VERSION), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.COLUMN), anyLong());
  }
}
