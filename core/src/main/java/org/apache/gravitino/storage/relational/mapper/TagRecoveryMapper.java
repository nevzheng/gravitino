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

import java.util.List;
import org.apache.gravitino.storage.relational.po.TagMetadataObjectRelPO;
import org.apache.gravitino.storage.relational.po.TagPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped tag deletion and recovery. */
public interface TagRecoveryMapper {

  /** Result map for tag root rows used by recovery. */
  @Results(
      id = "tagRecoveryResultMap",
      value = {
        @Result(property = "tagId", column = "tag_id", id = true),
        @Result(property = "tagName", column = "tag_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "comment", column = "tag_comment"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  TagPO tagRecoveryResultMap();

  /** Result map for tag-relation rows used by recovery. */
  @Results(
      id = "tagRelationRecoveryResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "tagId", column = "tag_id"),
        @Result(property = "metadataObjectId", column = "metadata_object_id"),
        @Result(property = "metadataObjectType", column = "metadata_object_type"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  TagMetadataObjectRelPO tagRelationRecoveryResultMap();

  /** Locks the live metalake that serializes tag and tag-relation changes. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 AND deletion_id IS NULL FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks one live tag under the already-locked metalake. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "lockLiveTag")
  TagPO lockLiveTag(@Param("metalakeId") long metalakeId, @Param("tagName") String tagName);

  /** Locks deleted tag roots whose immutable IDs must not be overwritten. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "selectDeletedTagsForUpdate")
  List<TagPO> selectDeletedTagsForUpdate(@Param("tagIds") List<Long> tagIds);

  /** Lists live tag roots below one metalake. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "listLiveTags")
  List<TagPO> listLiveTags(@Param("metalakeId") long metalakeId);

  /** Lists globally live tag roots matching immutable IDs. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "listLiveTagsByIds")
  List<TagPO> listLiveTagsByIds(@Param("tagIds") List<Long> tagIds);

  /** Lists independently deleted tag roots below one live metalake. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "listDeletedRootTags")
  List<TagPO> listDeletedRootTags(@Param("metalakeId") long metalakeId);

  /** Locks one exact tag root generation. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "selectTagGenerationForUpdate")
  TagPO selectTagGenerationForUpdate(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks all relations captured by one exact tag generation. */
  @ResultMap("tagRelationRecoveryResultMap")
  @SelectProvider(
      type = TagRecoverySQLProvider.class,
      method = "listTagRelationGenerationForUpdate")
  List<TagMetadataObjectRelPO> listTagRelationGenerationForUpdate(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Returns the newest timestamp touching one tag aggregate or deletion receipt. */
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "selectNewestTagDeletedAt")
  Long selectNewestTagDeletedAt(
      @Param("tagId") long tagId,
      @Param("metalakeId") long metalakeId,
      @Param("tagName") String tagName);

  /** Stamps currently live tag relations before the tag root. */
  @UpdateProvider(type = TagRecoverySQLProvider.class, method = "softDeleteTagRelations")
  int softDeleteTagRelations(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Stamps the exact live tag root after its relations. */
  @UpdateProvider(type = TagRecoverySQLProvider.class, method = "softDeleteTag")
  int softDeleteTag(
      @Param("tagId") long tagId,
      @Param("metalakeId") long metalakeId,
      @Param("tagName") String tagName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the tag root in one exact deletion generation. */
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "countTagGeneration")
  int countTagGeneration(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the relations in one exact tag deletion generation. */
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "countTagRelationGeneration")
  int countTagRelationGeneration(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts live logical duplicates of relations captured by an exact generation. */
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "countLiveRelationDuplicates")
  int countLiveRelationDuplicates(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations from one exact generation before its root. */
  @UpdateProvider(type = TagRecoverySQLProvider.class, method = "restoreTagRelations")
  int restoreTagRelations(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact tag root after its relations. */
  @UpdateProvider(type = TagRecoverySQLProvider.class, method = "restoreTag")
  int restoreTag(
      @Param("tagId") long tagId,
      @Param("metalakeId") long metalakeId,
      @Param("tagName") String tagName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes relations from one exact generation before its tag root. */
  @DeleteProvider(type = TagRecoverySQLProvider.class, method = "hardDeleteTagRelations")
  int hardDeleteTagRelations(
      @Param("tagId") long tagId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the exact tag root after its relations. */
  @DeleteProvider(type = TagRecoverySQLProvider.class, method = "hardDeleteTag")
  int hardDeleteTag(
      @Param("tagId") long tagId,
      @Param("metalakeId") long metalakeId,
      @Param("tagName") String tagName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks one bounded batch of expired unrecorded tag roots. */
  @ResultMap("tagRecoveryResultMap")
  @SelectProvider(type = TagRecoverySQLProvider.class, method = "selectLegacyTagRootsForUpdate")
  List<TagPO> selectLegacyTagRootsForUpdate(
      @Param("legacyTimeline") long legacyTimeline, @Param("limit") int limit);

  /** Permanently deletes null-token relations owned by selected legacy tag roots. */
  @DeleteProvider(type = TagRecoverySQLProvider.class, method = "hardDeleteLegacyTagRelations")
  int hardDeleteLegacyTagRelations(@Param("tagIds") List<Long> tagIds);

  /** Permanently deletes selected expired legacy tag roots. */
  @DeleteProvider(type = TagRecoverySQLProvider.class, method = "hardDeleteLegacyTags")
  int hardDeleteLegacyTags(
      @Param("tagIds") List<Long> tagIds, @Param("legacyTimeline") long legacyTimeline);
}
