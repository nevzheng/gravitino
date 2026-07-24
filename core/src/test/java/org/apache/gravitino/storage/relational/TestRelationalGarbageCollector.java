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

import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.apache.gravitino.Configs.VERSION_RETENTION_COUNT;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class TestRelationalGarbageCollector {

  @Test
  void testTableFailureStopsDependentHardDeletesForCycle() throws Exception {
    Config config = Mockito.mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(600_000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    RelationalBackend backend = Mockito.mock(RelationalBackend.class);
    when(backend.hardDeleteLegacyData(Mockito.any(), anyLong())).thenReturn(0);
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

  @Test
  void testFilesetFailureStopsDependentHardDeletesForCycle() throws Exception {
    Config config = Mockito.mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(600_000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    RelationalBackend backend = Mockito.mock(RelationalBackend.class);
    when(backend.hardDeleteLegacyData(Mockito.any(), anyLong())).thenReturn(0);
    when(backend.hardDeleteLegacyData(eq(Entity.EntityType.FILESET), anyLong()))
        .thenThrow(new IllegalStateException("fileset deletion purge failed"));

    try (RelationalGarbageCollector garbageCollector =
        new RelationalGarbageCollector(backend, config)) {
      garbageCollector.collectAndClean();
    }

    InOrder aggregateOrder = inOrder(backend);
    aggregateOrder.verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.TABLE), anyLong());
    aggregateOrder.verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.FILESET), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.FUNCTION), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.METALAKE), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.COLUMN), anyLong());
  }

  @Test
  void testViewFailureStopsDependentHardDeletesForCycle() throws Exception {
    Config config = Mockito.mock(Config.class);
    when(config.get(STORE_DELETE_AFTER_TIME)).thenReturn(600_000L);
    when(config.get(VERSION_RETENTION_COUNT)).thenReturn(1L);

    RelationalBackend backend = Mockito.mock(RelationalBackend.class);
    when(backend.hardDeleteLegacyData(Mockito.any(), anyLong())).thenReturn(0);
    when(backend.hardDeleteLegacyData(eq(Entity.EntityType.VIEW), anyLong()))
        .thenThrow(new IllegalStateException("view deletion purge failed"));

    try (RelationalGarbageCollector garbageCollector =
        new RelationalGarbageCollector(backend, config)) {
      garbageCollector.collectAndClean();
    }

    verify(backend).hardDeleteLegacyData(eq(Entity.EntityType.VIEW), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.METALAKE), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.COLUMN), anyLong());
    verify(backend, never()).hardDeleteLegacyData(eq(Entity.EntityType.MODEL_VERSION), anyLong());
  }
}
