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

/** SQL provider for exact, generation-scoped tag deletion and recovery. */
public class TagRecoverySQLProvider {

  private static final String TAG_COLUMNS =
      "tm.tag_id, tm.tag_name, tm.metalake_id, tm.tag_comment, tm.properties,"
          + " tm.audit_info, tm.current_version, tm.last_version, tm.deleted_at,"
          + " tm.deletion_id";

  private static final String RELATION_COLUMNS =
      "trm.id, trm.tag_id, trm.metadata_object_id, trm.metadata_object_type,"
          + " trm.audit_info, trm.current_version, trm.last_version, trm.deleted_at,"
          + " trm.deletion_id";

  /** Builds the locking read for one live tag below an already-locked metalake. */
  public String lockLiveTag() {
    return "SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE tm.metalake_id = #{metalakeId}"
        + " AND tm.tag_name = #{tagName} AND tm.deleted_at = 0"
        + " AND tm.deletion_id IS NULL FOR UPDATE";
  }

  /** Builds the immutable-ID fence for all deleted tag roots. */
  public String selectDeletedTagsForUpdate() {
    return "<script>SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE (tm.deleted_at > 0 OR tm.deletion_id IS NOT NULL)"
        + " AND tm.tag_id IN"
        + idList("tagIds", "tagId")
        + " ORDER BY tm.tag_id FOR UPDATE</script>";
  }

  /** Builds the read for all live tags below one metalake. */
  public String listLiveTags() {
    return "SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE tm.metalake_id = #{metalakeId}"
        + " AND tm.deleted_at = 0 AND tm.deletion_id IS NULL";
  }

  /** Builds the read for globally live tags matching immutable IDs. */
  public String listLiveTagsByIds() {
    return "<script>SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE tm.deleted_at = 0 AND tm.deletion_id IS NULL"
        + " AND tm.tag_id IN"
        + idList("tagIds", "tagId")
        + "</script>";
  }

  /** Builds the read for independently deleted tag roots below one live metalake. */
  public String listDeletedRootTags() {
    return "SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm LEFT JOIN entity_deletion ed"
        + " ON ed.entity_type = 'TAG' AND ed.entity_id = tm.tag_id"
        + " AND ed.deleted_at = tm.deleted_at AND ed.deletion_id = tm.deletion_id"
        + " WHERE tm.metalake_id = #{metalakeId} AND tm.deleted_at > 0 AND ("
        + "tm.deletion_id IS NULL OR (tm.deletion_id IS NOT NULL"
        + " AND ed.metalake_id = #{metalakeId} AND ed.parent_id = #{metalakeId}))"
        + " ORDER BY tm.deleted_at DESC, tm.tag_id DESC";
  }

  /** Builds the locking read for one exact tag root generation. */
  public String selectTagGenerationForUpdate() {
    return "SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE tm.tag_id = #{tagId}"
        + " AND tm.deleted_at = #{deletedAt} AND tm.deletion_id = #{deletionId}"
        + " FOR UPDATE";
  }

  /** Builds the locking read for all relations captured by one exact tag generation. */
  public String listTagRelationGenerationForUpdate() {
    return "SELECT "
        + RELATION_COLUMNS
        + " FROM tag_relation_meta trm WHERE trm.tag_id = #{tagId}"
        + " AND trm.deleted_at = #{deletedAt} AND trm.deletion_id = #{deletionId}"
        + " ORDER BY trm.id FOR UPDATE";
  }

  /** Builds the query for the newest timestamp touching a tag aggregate or receipt. */
  public String selectNewestTagDeletedAt() {
    return "SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM tag_meta"
        + " WHERE tag_id = #{tagId}"
        + " OR (metalake_id = #{metalakeId} AND tag_name = #{tagName})"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM tag_relation_meta"
        + " WHERE tag_id = #{tagId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
        + " WHERE entity_type = 'TAG' AND (entity_id = #{tagId}"
        + " OR (parent_id = #{metalakeId} AND entity_name = #{tagName}))"
        + ") tag_deletions";
  }

  /** Builds the update that stamps currently live tag relations first. */
  public String softDeleteTagRelations() {
    return "UPDATE tag_relation_meta SET deleted_at = #{deletedAt},"
        + " deletion_id = #{deletionId} WHERE tag_id = #{tagId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the update that stamps the exact live tag root last. */
  public String softDeleteTag() {
    return "UPDATE tag_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + exactTagIdentity()
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the count for one exact tag root generation. */
  public String countTagGeneration() {
    return "SELECT COUNT(*) FROM tag_meta WHERE tag_id = #{tagId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the count for all relations in one exact tag generation. */
  public String countTagRelationGeneration() {
    return "SELECT COUNT(*) FROM tag_relation_meta WHERE tag_id = #{tagId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the count for live logical duplicates of captured tag relations. */
  public String countLiveRelationDuplicates() {
    return "SELECT COUNT(*) FROM tag_relation_meta live WHERE live.tag_id = #{tagId}"
        + " AND live.deleted_at = 0 AND live.deletion_id IS NULL AND EXISTS ("
        + "SELECT 1 FROM tag_relation_meta captured WHERE captured.tag_id = #{tagId}"
        + " AND captured.deleted_at = #{deletedAt}"
        + " AND captured.deletion_id = #{deletionId} AND captured.id <> live.id"
        + " AND captured.metadata_object_id = live.metadata_object_id"
        + " AND captured.metadata_object_type = live.metadata_object_type)";
  }

  /** Builds the exact-generation update that restores tag relations first. */
  public String restoreTagRelations() {
    return "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL"
        + " WHERE tag_id = #{tagId} AND deleted_at = #{deletedAt}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation update that restores the tag root last. */
  public String restoreTag() {
    return "UPDATE tag_meta SET deleted_at = 0, deletion_id = NULL"
        + exactTagIdentity()
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges tag relations first. */
  public String hardDeleteTagRelations() {
    return "DELETE FROM tag_relation_meta WHERE tag_id = #{tagId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges the tag root last. */
  public String hardDeleteTag() {
    return "DELETE FROM tag_meta WHERE tag_id = #{tagId}"
        + " AND metalake_id = #{metalakeId} AND tag_name = #{tagName}"
        + " AND current_version = #{currentVersion} AND deleted_at = #{deletedAt}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Builds the bounded locking read for expired unrecorded tag roots. */
  public String selectLegacyTagRootsForUpdate() {
    return "SELECT "
        + TAG_COLUMNS
        + " FROM tag_meta tm WHERE tm.deleted_at > 0"
        + " AND tm.deleted_at < #{legacyTimeline} AND tm.deletion_id IS NULL"
        + " ORDER BY tm.deleted_at, tm.tag_id LIMIT #{limit} FOR UPDATE";
  }

  /** Builds cleanup for null-token relations owned by expired legacy tag roots. */
  public String hardDeleteLegacyTagRelations() {
    return "<script>DELETE FROM tag_relation_meta WHERE deletion_id IS NULL AND tag_id IN"
        + idList("tagIds", "tagId")
        + "</script>";
  }

  /** Builds cleanup for a selected batch of expired legacy tag roots. */
  public String hardDeleteLegacyTags() {
    return "<script>DELETE FROM tag_meta WHERE deleted_at > 0"
        + " AND deleted_at &lt; #{legacyTimeline} AND deletion_id IS NULL AND tag_id IN"
        + idList("tagIds", "tagId")
        + "</script>";
  }

  private static String exactTagIdentity() {
    return " WHERE tag_id = #{tagId} AND metalake_id = #{metalakeId}"
        + " AND tag_name = #{tagName} AND current_version = #{currentVersion}";
  }

  private static String idList(String collection, String item) {
    return "<foreach collection='"
        + collection
        + "' item='"
        + item
        + "' open='(' separator=',' close=')'>#{"
        + item
        + "}</foreach>";
  }
}
