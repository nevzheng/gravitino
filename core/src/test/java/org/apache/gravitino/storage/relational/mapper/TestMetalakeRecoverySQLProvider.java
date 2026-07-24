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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestMetalakeRecoverySQLProvider {

  private final MetalakeRecoverySQLProvider provider = new MetalakeRecoverySQLProvider();

  @Test
  void testMetalakeRootIsLastAcrossFullAggregate() {
    MetalakeAggregateTable[] aggregateTables = MetalakeAggregateTable.values();

    assertEquals(31, aggregateTables.length);
    assertEquals(MetalakeAggregateTable.METALAKE, aggregateTables[aggregateTables.length - 1]);
    assertEquals(MetalakeAggregateTable.CATALOG, aggregateTables[aggregateTables.length - 2]);
    assertEquals(MetalakeAggregateTable.SCHEMA, aggregateTables[aggregateTables.length - 3]);
  }

  @Test
  void testExactGenerationPredicatesProtectEveryAggregateOperation() {
    for (MetalakeAggregateTable aggregateTable : MetalakeAggregateTable.values()) {
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
  void testBrokenReferenceQueryCoversFullMetalakeTree() {
    String sql = provider.countBrokenGenerationReferences();

    for (String table :
        new String[] {
          "metalake_meta",
          "catalog_meta",
          "schema_meta",
          "table_meta",
          "table_column_version_info",
          "table_version_info",
          "fileset_meta",
          "fileset_version_info",
          "topic_meta",
          "function_meta",
          "function_version_info",
          "model_meta",
          "model_version_info",
          "model_version_alias_rel",
          "view_meta",
          "view_version_info",
          "user_meta",
          "user_role_rel",
          "group_meta",
          "group_role_rel",
          "role_meta",
          "role_meta_securable_object",
          "tag_meta",
          "tag_relation_meta",
          "policy_meta",
          "policy_version_info",
          "policy_relation_meta",
          "statistic_meta",
          "job_template_meta",
          "job_run_meta",
          "owner_meta"
        }) {
      assertTrue(sql.contains(table), () -> "Missing generation reference check for " + table);
    }
    assertTrue(sql.contains("NOT EXISTS"), sql);
    assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
  }

  @Test
  void testIndirectRelationsAreSelectedThroughLiveMetalakeSources() {
    assertSoftDeleteMembership(MetalakeAggregateTable.USER_ROLE, "FROM user_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.GROUP_ROLE, "FROM group_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.SECURABLE_OBJECT, "FROM role_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.TAG_RELATION, "FROM tag_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.POLICY_RELATION, "FROM policy_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.TABLE_VERSION, "FROM table_meta");
    assertSoftDeleteMembership(MetalakeAggregateTable.MODEL_ALIAS, "FROM model_meta");
  }

  @Test
  void testRejectsUnknownAggregateTableParameter() {
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.softDeleteAggregateRows(Map.of("aggregateTable", "metalake_meta")));
  }

  @Test
  void testRootReceiptQueriesUseSqlNullParentSemantics() {
    String latest = EntityDeletionSQLProvider.selectLatestEntityDeletion("METALAKE", null, "root");
    String latestForUpdate =
        EntityDeletionSQLProvider.selectLatestEntityDeletionForUpdate("METALAKE", null, "root");
    String list = EntityDeletionSQLProvider.listEntityDeletions("METALAKE", null, null, null, null);

    assertTrue(latest.contains("parent_id IS NULL"), latest);
    assertTrue(latestForUpdate.contains("parent_id IS NULL"), latestForUpdate);
    assertTrue(latestForUpdate.endsWith("LIMIT 1 FOR UPDATE"), latestForUpdate);
    assertTrue(list.contains("parent_id IS NULL"), list);
    assertFalse(latest.contains("#{parentId}"), latest);
    assertFalse(list.contains("#{parentId}"), list);
  }

  @Test
  void testNonRootReceiptQueriesRetainBoundParentPredicate() {
    String latest = EntityDeletionSQLProvider.selectLatestEntityDeletion("CATALOG", 1L, "child");
    String list = EntityDeletionSQLProvider.listEntityDeletions("CATALOG", 1L, null, null, null);

    assertTrue(latest.contains("parent_id = #{parentId}"), latest);
    assertTrue(list.contains("parent_id = #{parentId}"), list);
  }

  @Test
  void testTimestampSelectionProtectsGloballyReusableMetalakeNames() {
    String sql = provider.selectNewestAggregateDeletedAt();

    assertTrue(sql.contains("FROM metalake_meta WHERE 1 = 1"), sql);
    assertTrue(sql.contains("WHERE entity_type = 'METALAKE'"), sql);
  }

  private void assertSoftDeleteMembership(
      MetalakeAggregateTable aggregateTable, String expectedMembership) {
    String sql = provider.softDeleteAggregateRows(Map.of("aggregateTable", aggregateTable));
    assertTrue(sql.contains(expectedMembership), sql);
    assertTrue(sql.contains("metalake_id = #{metalakeId}"), sql);
  }
}
