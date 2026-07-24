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

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.CaffeineEntityCache;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.relational.po.cache.EntityChangeRecord;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestTableRestoreChangeLogListener {

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "table");

  @Test
  void testFiltersAndParsesRestoreRecords() {
    EntityCache cache = Mockito.mock(EntityCache.class);
    TableRestoreChangeLogListener listener = new TableRestoreChangeLogListener(cache);

    listener.onEntityChange(
        List.of(
            change("metalake", "table", TABLE.toString(), OperateType.RESTORE),
            change("metalake", "TABLE", TABLE.toString(), OperateType.DROP),
            change("metalake", "CATALOG", TABLE.toString(), OperateType.RESTORE),
            change("metalake", "TABLE", "metalake.catalog.schema", OperateType.RESTORE),
            change("other", "TABLE", TABLE.toString(), OperateType.RESTORE)));

    verify(cache).invalidate(TABLE, Entity.EntityType.TABLE);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      verify(cache).invalidateRelationEntry(TABLE, Entity.EntityType.TABLE, relationType);
    }
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testMalformedRestoreRecordDoesNotBlockLaterRecord() {
    EntityCache cache = Mockito.mock(EntityCache.class);
    TableRestoreChangeLogListener listener = new TableRestoreChangeLogListener(cache);

    listener.onEntityChange(
        List.of(
            change("metalake", "TABLE", "metalake..table", OperateType.RESTORE),
            change("metalake", "TABLE", TABLE.toString(), OperateType.RESTORE)));

    verify(cache).invalidate(TABLE, Entity.EntityType.TABLE);
  }

  @Test
  void testInvalidatesEntityAndEveryRelationEntry() {
    CaffeineEntityCache cache = new CaffeineEntityCache(new Config(false) {});
    TableEntity table =
        TableEntity.builder()
            .withId(1L)
            .withNamespace(Namespace.of("metalake", "catalog", "schema"))
            .withName("table")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    cache.put(table);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      cache.put(TABLE, Entity.EntityType.TABLE, relationType, List.<TableEntity>of());
    }

    new TableRestoreChangeLogListener(cache)
        .onEntityChange(
            List.of(change("metalake", "TABLE", TABLE.toString(), OperateType.RESTORE)));

    Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE));
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE, relationType));
    }
  }

  @Test
  void testRestoreUsesNextCodeAfterReservedInsertCode() {
    Assertions.assertEquals(4, OperateType.RESTORE.getCode());
    Assertions.assertEquals(OperateType.RESTORE, OperateType.fromCode(4));
    Assertions.assertThrows(IllegalArgumentException.class, () -> OperateType.fromCode(3));
  }

  @Test
  void testCacheFailureForOneRecordDoesNotBlockAnother() {
    NameIdentifier first = NameIdentifier.of("metalake", "catalog", "schema", "first");
    NameIdentifier second = NameIdentifier.of("metalake", "catalog", "schema", "second");
    EntityCache cache = Mockito.mock(EntityCache.class);
    Mockito.doThrow(new IllegalStateException("cache failed"))
        .when(cache)
        .invalidate(first, Entity.EntityType.TABLE);
    TableRestoreChangeLogListener listener = new TableRestoreChangeLogListener(cache);

    listener.onEntityChange(
        List.of(
            change("metalake", "TABLE", first.toString(), OperateType.RESTORE),
            change("metalake", "TABLE", second.toString(), OperateType.RESTORE)));

    verify(cache).invalidate(first, Entity.EntityType.TABLE);
    verify(cache).invalidate(second, Entity.EntityType.TABLE);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      verify(cache, times(1)).invalidateRelationEntry(first, Entity.EntityType.TABLE, relationType);
      verify(cache, times(1))
          .invalidateRelationEntry(second, Entity.EntityType.TABLE, relationType);
    }
  }

  private static EntityChangeRecord change(
      String metalakeName, String entityType, String fullName, OperateType operateType) {
    return new EntityChangeRecord(1L, metalakeName, entityType, fullName, operateType, 0L);
  }
}
