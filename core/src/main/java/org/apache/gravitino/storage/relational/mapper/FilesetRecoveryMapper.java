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
import org.apache.gravitino.storage.relational.po.FilesetPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped fileset deletion and recovery. */
public interface FilesetRecoveryMapper {

  /** Result map for fileset base rows used by recovery. */
  @Results(
      id = "filesetRecoveryResultMap",
      value = {
        @Result(property = "filesetId", column = "fileset_id", id = true),
        @Result(property = "filesetName", column = "fileset_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaId", column = "schema_id"),
        @Result(property = "type", column = "type"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  FilesetPO filesetRecoveryResultMap();

  /** Locks the live parent schema to serialize child mutations and recovery. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live fileset under the already-locked schema. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE schema_id = #{schemaId} AND fileset_name = #{filesetName}",
    "AND deleted_at = 0 FOR UPDATE"
  })
  FilesetPO selectLiveFilesetForUpdate(
      @Param("schemaId") long schemaId, @Param("filesetName") String filesetName);

  /** Locks a recoverable tombstone whose immutable ID would otherwise be overwritten. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE fileset_id = #{filesetId} AND deleted_at > 0",
    "AND deletion_id IS NOT NULL FOR UPDATE"
  })
  FilesetPO selectRecordedDeletedFilesetForUpdate(@Param("filesetId") long filesetId);

  /** Returns the newest generation timestamp for either the ID or current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion WHERE entity_type = 'FILESET'",
    "AND (entity_id = #{filesetId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{filesetName}))"
  })
  Long selectNewestFilesetDeletedAt(
      @Param("filesetId") long filesetId,
      @Param("schemaId") long schemaId,
      @Param("filesetName") String filesetName);

  /** Lists deleted fileset base rows below one live schema, newest first. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at > 0",
    "ORDER BY deleted_at DESC, fileset_id DESC"
  })
  List<FilesetPO> listDeletedFilesets(@Param("schemaId") long schemaId);

  /** Lists live fileset identities below one schema. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0"
  })
  List<FilesetPO> listLiveFilesets(@Param("schemaId") long schemaId);

  /** Lists globally live rows for candidate immutable IDs. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE deleted_at = 0 AND fileset_id IN",
    "<foreach collection='filesetIds' item='filesetId' open='(' separator=',' close=')'>",
    "#{filesetId}",
    "</foreach>",
    "</script>"
  })
  List<FilesetPO> listLiveFilesetsByIds(@Param("filesetIds") List<Long> filesetIds);

  /** Selects the fileset base row for one exact deletion generation. */
  @ResultMap("filesetRecoveryResultMap")
  @Select({
    "SELECT fileset_id, fileset_name, metalake_id, catalog_id, schema_id, type, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM fileset_meta",
    "WHERE fileset_id = #{filesetId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  FilesetPO selectFilesetGeneration(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts current-version location rows captured by one exact deletion generation. */
  @Select({
    "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = #{filesetId}",
    "AND version = #{filesetVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int countCurrentVersionGeneration(
      @Param("filesetId") long filesetId,
      @Param("filesetVersion") long filesetVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live fileset base row. */
  @Update({
    "UPDATE fileset_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE fileset_id = #{filesetId} AND schema_id = #{schemaId}",
    "AND fileset_name = #{filesetName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = 0"
  })
  int softDeleteFilesetMeta(
      @Param("filesetId") long filesetId,
      @Param("schemaId") long schemaId,
      @Param("filesetName") String filesetName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live version-location row retained at drop time. */
  @Update({
    "UPDATE fileset_version_info SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE fileset_id = #{filesetId} AND deleted_at = 0"
  })
  int softDeleteFilesetVersions(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live owner relations for the fileset. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{filesetId}",
    "AND metadata_object_type = 'FILESET' AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the fileset. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{filesetId}",
    "AND type = 'FILESET' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the fileset. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = 0"
  })
  int softDeleteTagRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live statistics for the fileset. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = 0"
  })
  int softDeleteStatistics(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live policy relations for the fileset. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = 0"
  })
  int softDeletePolicyRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations in one exact fileset deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores role grants in one exact fileset deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{filesetId} AND type = 'FILESET'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations in one exact fileset deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTagRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores statistics in one exact fileset deletion generation. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreStatistics(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores policy relations in one exact fileset deletion generation. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{filesetId} AND metadata_object_type = 'FILESET'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restorePolicyRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores every retained version-location row in one exact generation. */
  @Update({
    "UPDATE fileset_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE fileset_id = #{filesetId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreFilesetVersions(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact fileset base row after its cohort has been verified. */
  @Update({
    "UPDATE fileset_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE fileset_id = #{filesetId} AND schema_id = #{schemaId}",
    "AND fileset_name = #{filesetName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreFilesetMeta(
      @Param("filesetId") long filesetId,
      @Param("schemaId") long schemaId,
      @Param("filesetName") String filesetName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{filesetId}",
    "AND metadata_object_type = 'FILESET' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{filesetId}",
    "AND type = 'FILESET' AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE metadata_object_id = #{filesetId}",
    "AND metadata_object_type = 'FILESET' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTagRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes statistics from one exact generation. */
  @Delete({
    "DELETE FROM statistic_meta WHERE metadata_object_id = #{filesetId}",
    "AND metadata_object_type = 'FILESET' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteStatistics(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes policy relations from one exact generation. */
  @Delete({
    "DELETE FROM policy_relation_meta WHERE metadata_object_id = #{filesetId}",
    "AND metadata_object_type = 'FILESET' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeletePolicyRelations(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes every version-location row from one exact generation. */
  @Delete({
    "DELETE FROM fileset_version_info WHERE fileset_id = #{filesetId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteFilesetVersions(
      @Param("filesetId") long filesetId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the fileset base row from one exact generation. */
  @Delete({
    "DELETE FROM fileset_meta WHERE fileset_id = #{filesetId} AND schema_id = #{schemaId}",
    "AND fileset_name = #{filesetName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteFilesetMeta(
      @Param("filesetId") long filesetId,
      @Param("schemaId") long schemaId,
      @Param("filesetName") String filesetName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
