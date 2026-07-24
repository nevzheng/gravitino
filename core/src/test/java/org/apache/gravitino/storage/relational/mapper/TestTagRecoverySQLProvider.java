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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestTagRecoverySQLProvider {

  private final TagRecoverySQLProvider provider = new TagRecoverySQLProvider();

  @Test
  void testAggregateMutationsKeepRelationsBeforeRootAndUseExactGeneration() {
    List<List<String>> operations =
        List.of(
            List.of(provider.softDeleteTagRelations(), provider.softDeleteTag()),
            List.of(provider.restoreTagRelations(), provider.restoreTag()),
            List.of(provider.hardDeleteTagRelations(), provider.hardDeleteTag()));
    for (List<String> statements : operations) {
      assertTrue(statements.get(0).contains("tag_relation_meta"), statements.get(0));
      assertTrue(statements.get(1).contains("tag_meta"), statements.get(1));
      for (String sql : statements) {
        assertTrue(sql.contains("#{deletedAt}"), sql);
        assertTrue(sql.contains("#{deletionId}"), sql);
      }
    }
  }

  @Test
  void testDeleteCapturesOnlyUnownedLiveRelationsAndRoot() {
    String relations = provider.softDeleteTagRelations();
    assertTrue(relations.contains("tag_id = #{tagId}"), relations);
    assertTrue(relations.contains("deleted_at = 0 AND deletion_id IS NULL"), relations);

    String root = provider.softDeleteTag();
    assertTrue(root.contains("metalake_id = #{metalakeId}"), root);
    assertTrue(root.contains("current_version = #{currentVersion}"), root);
    assertTrue(root.contains("deleted_at = 0 AND deletion_id IS NULL"), root);
  }

  @Test
  void testRootListingExposesLegacyAndOnlyReceiptBackedRecordedTags() {
    String roots = provider.listDeletedRootTags();
    assertTrue(roots.contains("LEFT JOIN entity_deletion"), roots);
    assertTrue(roots.contains("ed.entity_type = 'TAG'"), roots);
    assertTrue(roots.contains("tm.deletion_id IS NULL OR"), roots);
    assertTrue(roots.contains("ed.parent_id = #{metalakeId}"), roots);
  }

  @Test
  void testValidationDoesNotRequireMetadataObjectRootLiveness() {
    String generation = provider.listTagRelationGenerationForUpdate();
    assertTrue(generation.contains("tag_id = #{tagId}"), generation);
    assertTrue(generation.contains("ORDER BY trm.id FOR UPDATE"), generation);
    assertTrue(!generation.contains("table_meta"), generation);
    assertTrue(!generation.contains("metadata_object_type = 'TABLE'"), generation);

    String duplicates = provider.countLiveRelationDuplicates();
    assertTrue(duplicates.contains("captured.metadata_object_id = live.metadata_object_id"));
    assertTrue(duplicates.contains("captured.metadata_object_type = live.metadata_object_type"));
  }

  @Test
  void testTimestampAndLegacyCleanupCoverRelationsWithoutTakingOtherTokens() {
    String newest = provider.selectNewestTagDeletedAt();
    assertTrue(newest.contains("FROM tag_meta"), newest);
    assertTrue(newest.contains("FROM tag_relation_meta"), newest);
    assertTrue(newest.contains("FROM entity_deletion"), newest);

    String legacyRelations = provider.hardDeleteLegacyTagRelations();
    assertTrue(legacyRelations.contains("deletion_id IS NULL"), legacyRelations);
    assertTrue(legacyRelations.contains("tag_id IN"), legacyRelations);
  }
}
