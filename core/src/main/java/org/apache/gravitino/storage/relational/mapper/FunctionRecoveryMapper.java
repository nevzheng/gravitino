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
import org.apache.gravitino.storage.relational.po.FunctionPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped function deletion and recovery. */
public interface FunctionRecoveryMapper {

  /** Result map for function base rows used by recovery. */
  @Results(
      id = "functionRecoveryResultMap",
      value = {
        @Result(property = "functionId", column = "function_id", id = true),
        @Result(property = "functionName", column = "function_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaId", column = "schema_id"),
        @Result(property = "functionCurrentVersion", column = "function_current_version"),
        @Result(property = "functionLatestVersion", column = "function_latest_version"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  FunctionPO functionRecoveryResultMap();

  /** Locks the live parent schema to serialize child drop and restore transactions. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live function under the already-locked parent. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE schema_id = #{schemaId}",
    "AND function_name = #{functionName} AND deleted_at = 0 FOR UPDATE"
  })
  FunctionPO selectLiveFunctionForUpdate(
      @Param("schemaId") long schemaId, @Param("functionName") String functionName);

  /** Locks a recoverable tombstone whose immutable ID would otherwise be overwritten. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE function_id = #{functionId}",
    "AND deleted_at > 0 AND deletion_id IS NOT NULL FOR UPDATE"
  })
  FunctionPO selectRecordedDeletedFunctionForUpdate(@Param("functionId") long functionId);

  /** Returns the newest generation timestamp for either the ID or current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion WHERE entity_type = 'FUNCTION'",
    "AND (entity_id = #{functionId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{functionName}))"
  })
  Long selectNewestFunctionDeletedAt(
      @Param("functionId") long functionId,
      @Param("schemaId") long schemaId,
      @Param("functionName") String functionName);

  /** Lists deleted function base rows below one live schema, newest first. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE schema_id = #{schemaId} AND deleted_at > 0",
    "ORDER BY deleted_at DESC, function_id DESC"
  })
  List<FunctionPO> listDeletedFunctions(@Param("schemaId") long schemaId);

  /** Lists live function identities below one schema. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE schema_id = #{schemaId} AND deleted_at = 0"
  })
  List<FunctionPO> listLiveFunctions(@Param("schemaId") long schemaId);

  /** Lists globally live rows for candidate immutable IDs. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE deleted_at = 0 AND function_id IN",
    "<foreach collection='functionIds' item='functionId' open='(' separator=',' close=')'>",
    "#{functionId}",
    "</foreach>",
    "</script>"
  })
  List<FunctionPO> listLiveFunctionsByIds(@Param("functionIds") List<Long> functionIds);

  /** Selects the function base row for one exact deletion generation. */
  @ResultMap("functionRecoveryResultMap")
  @Select({
    "SELECT function_id, function_name, metalake_id, catalog_id, schema_id,",
    "function_current_version, function_latest_version, audit_info, deleted_at,",
    "deletion_id FROM function_meta WHERE function_id = #{functionId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  FunctionPO selectFunctionGeneration(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the captured current-version row for one exact deletion generation. */
  @Select({
    "SELECT COUNT(*) FROM function_version_info WHERE function_id = #{functionId}",
    "AND version = #{functionVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int countCurrentVersionGeneration(
      @Param("functionId") long functionId,
      @Param("functionVersion") long functionVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live function base row. */
  @Update({
    "UPDATE function_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE function_id = #{functionId} AND schema_id = #{schemaId}",
    "AND function_name = #{functionName} AND function_current_version = #{functionVersion}",
    "AND function_latest_version = #{functionLatestVersion}",
    "AND deleted_at = 0"
  })
  int softDeleteFunctionMeta(
      @Param("functionId") long functionId,
      @Param("schemaId") long schemaId,
      @Param("functionName") String functionName,
      @Param("functionVersion") long functionVersion,
      @Param("functionLatestVersion") long functionLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live version retained by the function at drop time. */
  @Update({
    "UPDATE function_version_info SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE function_id = #{functionId} AND deleted_at = 0"
  })
  int softDeleteFunctionVersions(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live owner relations for the function. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{functionId}",
    "AND metadata_object_type = 'FUNCTION' AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the function. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{functionId}",
    "AND type = 'FUNCTION' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the function. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{functionId} AND metadata_object_type = 'FUNCTION'",
    "AND deleted_at = 0"
  })
  int softDeleteTagRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations in one exact function deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{functionId} AND metadata_object_type = 'FUNCTION'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores role grants in one exact function deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{functionId} AND type = 'FUNCTION'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations in one exact function deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{functionId} AND metadata_object_type = 'FUNCTION'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTagRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores every retained version in one exact function deletion generation. */
  @Update({
    "UPDATE function_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE function_id = #{functionId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreFunctionVersions(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact function base row after its cohort has been verified. */
  @Update({
    "UPDATE function_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE function_id = #{functionId} AND schema_id = #{schemaId}",
    "AND function_name = #{functionName} AND function_current_version = #{functionVersion}",
    "AND function_latest_version = #{functionLatestVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreFunctionMeta(
      @Param("functionId") long functionId,
      @Param("schemaId") long schemaId,
      @Param("functionName") String functionName,
      @Param("functionVersion") long functionVersion,
      @Param("functionLatestVersion") long functionLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{functionId}",
    "AND metadata_object_type = 'FUNCTION' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{functionId}",
    "AND type = 'FUNCTION' AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE metadata_object_id = #{functionId}",
    "AND metadata_object_type = 'FUNCTION' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTagRelations(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes every retained version from one exact generation. */
  @Delete({
    "DELETE FROM function_version_info WHERE function_id = #{functionId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteFunctionVersions(
      @Param("functionId") long functionId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the function base row from one exact generation. */
  @Delete({
    "DELETE FROM function_meta WHERE function_id = #{functionId} AND schema_id = #{schemaId}",
    "AND function_name = #{functionName} AND function_current_version = #{functionVersion}",
    "AND function_latest_version = #{functionLatestVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteFunctionMeta(
      @Param("functionId") long functionId,
      @Param("schemaId") long schemaId,
      @Param("functionName") String functionName,
      @Param("functionVersion") long functionVersion,
      @Param("functionLatestVersion") long functionLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
