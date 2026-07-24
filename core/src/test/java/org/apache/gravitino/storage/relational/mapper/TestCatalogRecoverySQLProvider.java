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
package org.apache.gravitino.storage.relational.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestCatalogRecoverySQLProvider {

  private final CatalogRecoverySQLProvider provider = new CatalogRecoverySQLProvider();

  @Test
  void testCatalogRootIsLastAggregateTable() {
    CatalogAggregateTable[] aggregateTables = CatalogAggregateTable.values();

    assertEquals(CatalogAggregateTable.CATALOG, aggregateTables[aggregateTables.length - 1]);
    assertEquals(CatalogAggregateTable.SCHEMA, aggregateTables[aggregateTables.length - 2]);
  }

  @Test
  void testExactGenerationPredicatesProtectEveryAggregateOperation() {
    for (CatalogAggregateTable aggregateTable : CatalogAggregateTable.values()) {
      Map<String, Object> parameters = Map.of("aggregateTable", aggregateTable);
      String softDelete = provider.softDeleteAggregateRows(parameters);
      String count = provider.countGenerationRows(parameters);
      String restore = provider.restoreGenerationRows(parameters);
      String hardDelete = provider.hardDeleteGenerationRows(parameters);

      assertTrue(softDelete.contains("deleted_at = 0 AND deletion_id IS NULL"), softDelete);
      assertTrue(softDelete.contains("deletion_id = #{deletionId}"), softDelete);
      assertTrue(count.contains("deleted_at = #{deletedAt}"), count);
      assertTrue(count.contains("deletion_id = #{deletionId}"), count);
      assertTrue(restore.contains("deleted_at = #{deletedAt}"), restore);
      assertTrue(restore.contains("deletion_id = #{deletionId}"), restore);
      assertTrue(hardDelete.contains("deleted_at = #{deletedAt}"), hardDelete);
      assertTrue(hardDelete.contains("deletion_id = #{deletionId}"), hardDelete);
    }
  }

  @Test
  void testBrokenReferenceQueryCoversEveryOwnedBaseAndDetailTable() {
    String sql = provider.countBrokenGenerationReferences();

    for (String table :
        new String[] {
          "catalog_meta",
          "schema_meta",
          "table_meta",
          "fileset_meta",
          "topic_meta",
          "function_meta",
          "model_meta",
          "view_meta",
          "table_column_version_info",
          "table_version_info",
          "fileset_version_info",
          "function_version_info",
          "model_version_info",
          "model_version_alias_rel",
          "view_version_info",
          "owner_meta",
          "role_meta_securable_object",
          "tag_relation_meta",
          "policy_relation_meta",
          "statistic_meta",
          "user_meta",
          "group_meta",
          "role_meta",
          "tag_meta",
          "policy_meta"
        }) {
      assertTrue(sql.contains(table), () -> "Missing generation reference check for " + table);
    }
    assertTrue(sql.contains("NOT EXISTS"), sql);
    assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
    assertTrue(sql.contains("owner.deleted_at = 0"), sql);
    assertTrue(sql.contains("role.deleted_at = 0"), sql);
    assertTrue(sql.contains("tag.deleted_at = 0"), sql);
    assertTrue(sql.contains("policy.deleted_at = 0"), sql);
  }

  @Test
  void testDetailRowsAreSelectedThroughTheirLiveBase() {
    assertSoftDeleteMembership(CatalogAggregateTable.TABLE_COLUMN, "FROM table_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.TABLE_VERSION, "FROM table_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.FILESET_VERSION, "FROM fileset_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.FUNCTION_VERSION, "FROM function_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.MODEL_VERSION, "FROM model_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.MODEL_ALIAS, "FROM model_meta");
    assertSoftDeleteMembership(CatalogAggregateTable.VIEW_VERSION, "FROM view_meta");
  }

  @Test
  void testRejectsUnknownAggregateTableParameter() {
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.softDeleteAggregateRows(Map.of("aggregateTable", "catalog_meta")));
  }

  private void assertSoftDeleteMembership(
      CatalogAggregateTable aggregateTable, String expectedMembership) {
    String sql = provider.softDeleteAggregateRows(Map.of("aggregateTable", aggregateTable));
    assertTrue(sql.contains(expectedMembership), sql);
    assertTrue(sql.contains("deleted_at = 0"), sql);
  }
}
