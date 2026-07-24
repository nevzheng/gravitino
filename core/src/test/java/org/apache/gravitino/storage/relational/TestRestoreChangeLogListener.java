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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.CaffeineEntityCache;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.relational.po.cache.EntityChangeRecord;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestRestoreChangeLogListener {

  private static final NameIdentifier CATALOG = NameIdentifier.of("metalake", "catalog");

  private static final NameIdentifier SCHEMA = NameIdentifier.of("metalake", "catalog", "schema");

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "table");

  private static final Map<Entity.EntityType, NameIdentifier> RECOVERABLE_IDENTIFIERS =
      Map.ofEntries(
          Map.entry(Entity.EntityType.METALAKE, NameIdentifier.of("metalake")),
          Map.entry(Entity.EntityType.CATALOG, CATALOG),
          Map.entry(Entity.EntityType.SCHEMA, SCHEMA),
          Map.entry(Entity.EntityType.TABLE, TABLE),
          Map.entry(
              Entity.EntityType.VIEW, NameIdentifier.of("metalake", "catalog", "schema", "view")),
          Map.entry(
              Entity.EntityType.FILESET,
              NameIdentifier.of("metalake", "catalog", "schema", "fileset")),
          Map.entry(
              Entity.EntityType.TOPIC, NameIdentifier.of("metalake", "catalog", "schema", "topic")),
          Map.entry(
              Entity.EntityType.FUNCTION,
              NameIdentifier.of("metalake", "catalog", "schema", "function")),
          Map.entry(
              Entity.EntityType.MODEL, NameIdentifier.of("metalake", "catalog", "schema", "model")),
          Map.entry(
              Entity.EntityType.POLICY,
              NameIdentifier.of(NamespaceUtil.ofPolicy("metalake"), "policy")),
          Map.entry(
              Entity.EntityType.USER, NameIdentifier.of(NamespaceUtil.ofUser("metalake"), "user")),
          Map.entry(
              Entity.EntityType.GROUP,
              NameIdentifier.of(NamespaceUtil.ofGroup("metalake"), "group")),
          Map.entry(
              Entity.EntityType.ROLE, NameIdentifier.of(NamespaceUtil.ofRole("metalake"), "role")),
          Map.entry(
              Entity.EntityType.TAG, NameIdentifier.of(NamespaceUtil.ofTag("metalake"), "tag")),
          Map.entry(
              Entity.EntityType.JOB_TEMPLATE,
              NameIdentifier.of(NamespaceUtil.ofJobTemplate("metalake"), "template")));

  @Test
  void testFiltersAndParsesRestoreRecords() {
    EntityCache cache = Mockito.mock(EntityCache.class);
    RestoreChangeLogListener listener = new RestoreChangeLogListener(cache);

    listener.onEntityChange(
        List.of(
            change("metalake", "table", TABLE.toString(), OperateType.RESTORE),
            change("metalake", "TABLE", TABLE.toString(), OperateType.DROP),
            change("metalake", "METALAKE", TABLE.toString(), OperateType.RESTORE)));

    verify(cache).invalidate(TABLE, Entity.EntityType.TABLE);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      verify(cache).invalidateRelationEntry(TABLE, Entity.EntityType.TABLE, relationType);
    }
    verify(cache).clear();
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testInvalidatesEveryRecoverableEntityType() {
    EntityCache cache = Mockito.mock(EntityCache.class);
    RestoreChangeLogListener listener = new RestoreChangeLogListener(cache);
    List<EntityChangeRecord> changes = new ArrayList<>();
    RECOVERABLE_IDENTIFIERS.forEach(
        (entityType, identifier) ->
            changes.add(
                change(
                    "metalake",
                    entityType.name().toLowerCase(Locale.ROOT),
                    identifier.toString(),
                    OperateType.RESTORE)));

    listener.onEntityChange(changes);

    verify(cache, times(9)).clear();
    RECOVERABLE_IDENTIFIERS.forEach(
        (entityType, identifier) -> {
          if (entityType == Entity.EntityType.METALAKE
              || entityType == Entity.EntityType.CATALOG
              || entityType == Entity.EntityType.SCHEMA
              || entityType == Entity.EntityType.POLICY
              || entityType == Entity.EntityType.USER
              || entityType == Entity.EntityType.GROUP
              || entityType == Entity.EntityType.ROLE
              || entityType == Entity.EntityType.TAG
              || entityType == Entity.EntityType.JOB_TEMPLATE) {
            return;
          }
          verify(cache).invalidate(identifier, entityType);
          for (SupportsRelationOperations.Type relationType :
              SupportsRelationOperations.Type.values()) {
            verify(cache).invalidateRelationEntry(identifier, entityType, relationType);
          }
        });
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testMalformedOrAmbiguousRestoreClearsCacheAndDoesNotBlockLaterRecord() {
    EntityCache cache = Mockito.mock(EntityCache.class);
    RestoreChangeLogListener listener = new RestoreChangeLogListener(cache);

    listener.onEntityChange(
        List.of(
            change("metalake", "TABLE", "metalake..table", OperateType.RESTORE),
            change(
                "metalake", "TABLE", "metalake.catalog.schema.table.with.dot", OperateType.RESTORE),
            change("other", "TABLE", TABLE.toString(), OperateType.RESTORE),
            change("metalake", "TABLE", TABLE.toString(), OperateType.RESTORE)));

    verify(cache, times(3)).clear();
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

    new RestoreChangeLogListener(cache)
        .onEntityChange(
            List.of(change("metalake", "TABLE", TABLE.toString(), OperateType.RESTORE)));

    Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE));
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE, relationType));
    }
  }

  @Test
  void testSchemaRestoreClearsCachedTreeAndRootRelations() {
    CaffeineEntityCache cache = new CaffeineEntityCache(new Config(false) {});
    SchemaEntity schema =
        SchemaEntity.builder()
            .withId(1L)
            .withNamespace(Namespace.of("metalake", "catalog"))
            .withName("schema")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    TableEntity table =
        TableEntity.builder()
            .withId(2L)
            .withNamespace(Namespace.of("metalake", "catalog", "schema"))
            .withName("table")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    cache.put(schema);
    cache.put(table);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      cache.put(SCHEMA, Entity.EntityType.SCHEMA, relationType, List.<SchemaEntity>of());
      cache.put(TABLE, Entity.EntityType.TABLE, relationType, List.<TableEntity>of());
    }

    new RestoreChangeLogListener(cache)
        .onEntityChange(
            List.of(change("metalake", "SCHEMA", SCHEMA.toString(), OperateType.RESTORE)));

    Assertions.assertFalse(cache.contains(SCHEMA, Entity.EntityType.SCHEMA));
    Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE));
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Assertions.assertFalse(cache.contains(SCHEMA, Entity.EntityType.SCHEMA, relationType));
      Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE, relationType));
    }
  }

  @Test
  void testCatalogRestoreClearsCachedTreeAndRootRelations() {
    CaffeineEntityCache cache = new CaffeineEntityCache(new Config(false) {});
    CatalogEntity catalog =
        CatalogEntity.builder()
            .withId(1L)
            .withNamespace(Namespace.of("metalake"))
            .withName("catalog")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    SchemaEntity schema =
        SchemaEntity.builder()
            .withId(2L)
            .withNamespace(Namespace.of("metalake", "catalog"))
            .withName("schema")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    TableEntity table =
        TableEntity.builder()
            .withId(3L)
            .withNamespace(Namespace.of("metalake", "catalog", "schema"))
            .withName("table")
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    cache.put(catalog);
    cache.put(schema);
    cache.put(table);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      cache.put(CATALOG, Entity.EntityType.CATALOG, relationType, List.<CatalogEntity>of());
      cache.put(SCHEMA, Entity.EntityType.SCHEMA, relationType, List.<SchemaEntity>of());
      cache.put(TABLE, Entity.EntityType.TABLE, relationType, List.<TableEntity>of());
    }

    new RestoreChangeLogListener(cache)
        .onEntityChange(
            List.of(change("metalake", "CATALOG", CATALOG.toString(), OperateType.RESTORE)));

    Assertions.assertFalse(cache.contains(CATALOG, Entity.EntityType.CATALOG));
    Assertions.assertFalse(cache.contains(SCHEMA, Entity.EntityType.SCHEMA));
    Assertions.assertFalse(cache.contains(TABLE, Entity.EntityType.TABLE));
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Assertions.assertFalse(cache.contains(CATALOG, Entity.EntityType.CATALOG, relationType));
      Assertions.assertFalse(cache.contains(SCHEMA, Entity.EntityType.SCHEMA, relationType));
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
    RestoreChangeLogListener listener = new RestoreChangeLogListener(cache);

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
