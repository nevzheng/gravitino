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
import org.apache.gravitino.storage.relational.po.ModelPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped model deletion and recovery. */
public interface ModelRecoveryMapper {

  /** Result map for model base rows used by recovery. */
  @Results(
      id = "modelRecoveryResultMap",
      value = {
        @Result(property = "modelId", column = "model_id", id = true),
        @Result(property = "modelName", column = "model_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaId", column = "schema_id"),
        @Result(property = "modelLatestVersion", column = "model_latest_version"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  ModelPO modelRecoveryResultMap();

  /** Locks the live parent schema to serialize child drop and restore transactions. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live model under the already-locked parent. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE schema_id = #{schemaId} AND model_name = #{modelName}",
    "AND deleted_at = 0 FOR UPDATE"
  })
  ModelPO selectLiveModelForUpdate(
      @Param("schemaId") long schemaId, @Param("modelName") String modelName);

  /** Locks and returns one live model by immutable ID. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE model_id = #{modelId} AND deleted_at = 0 FOR UPDATE"
  })
  ModelPO selectLiveModelByIdForUpdate(@Param("modelId") long modelId);

  /** Locks a recoverable tombstone whose immutable ID would otherwise be overwritten. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE model_id = #{modelId} AND deleted_at > 0",
    "AND deletion_id IS NOT NULL FOR UPDATE"
  })
  ModelPO selectRecordedDeletedModelForUpdate(@Param("modelId") long modelId);

  /** Returns the newest generation timestamp for either the ID or current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion WHERE entity_type = 'MODEL'",
    "AND (entity_id = #{modelId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{modelName}))"
  })
  Long selectNewestModelDeletedAt(
      @Param("modelId") long modelId,
      @Param("schemaId") long schemaId,
      @Param("modelName") String modelName);

  /** Lists deleted model base rows below one live schema, newest first. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at > 0",
    "ORDER BY deleted_at DESC, model_id DESC"
  })
  List<ModelPO> listDeletedModels(@Param("schemaId") long schemaId);

  /** Lists live model identities below one schema. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0"
  })
  List<ModelPO> listLiveModels(@Param("schemaId") long schemaId);

  /** Lists globally live rows for candidate immutable IDs. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE deleted_at = 0 AND model_id IN",
    "<foreach collection='modelIds' item='modelId' open='(' separator=',' close=')'>",
    "#{modelId}",
    "</foreach>",
    "</script>"
  })
  List<ModelPO> listLiveModelsByIds(@Param("modelIds") List<Long> modelIds);

  /** Selects the model base row for one exact deletion generation. */
  @ResultMap("modelRecoveryResultMap")
  @Select({
    "SELECT model_id, model_name, metalake_id, catalog_id, schema_id,",
    "model_latest_version, audit_info, deleted_at, deletion_id FROM model_meta",
    "WHERE model_id = #{modelId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  ModelPO selectModelGeneration(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live model base row. */
  @Update({
    "UPDATE model_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE model_id = #{modelId} AND schema_id = #{schemaId}",
    "AND model_name = #{modelName} AND model_latest_version = #{modelLatestVersion}",
    "AND deleted_at = 0"
  })
  int softDeleteModelMeta(
      @Param("modelId") long modelId,
      @Param("schemaId") long schemaId,
      @Param("modelName") String modelName,
      @Param("modelLatestVersion") long modelLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live URI row retained by the model at drop time. */
  @Update({
    "UPDATE model_version_info SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE model_id = #{modelId} AND deleted_at = 0"
  })
  int softDeleteModelVersions(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live alias retained by the model at drop time. */
  @Update({
    "UPDATE model_version_alias_rel SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE model_id = #{modelId} AND deleted_at = 0"
  })
  int softDeleteModelAliases(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live owner relations for the model. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{modelId}",
    "AND metadata_object_type = 'MODEL' AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the model. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{modelId}",
    "AND type = 'MODEL' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the model. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = 0"
  })
  int softDeleteTagRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live statistics for the model. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = 0"
  })
  int softDeleteStatistics(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live policy relations for the model. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = 0"
  })
  int softDeletePolicyRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores URI rows in one exact model deletion generation. */
  @Update({
    "UPDATE model_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE model_id = #{modelId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreModelVersions(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores aliases in one exact model deletion generation. */
  @Update({
    "UPDATE model_version_alias_rel SET deleted_at = 0, deletion_id = NULL",
    "WHERE model_id = #{modelId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreModelAliases(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations in one exact model deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores role grants in one exact model deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{modelId} AND type = 'MODEL'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations in one exact model deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTagRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores statistics in one exact model deletion generation. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreStatistics(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores policy relations in one exact model deletion generation. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{modelId} AND metadata_object_type = 'MODEL'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restorePolicyRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact model base row after its cohort has been verified. */
  @Update({
    "UPDATE model_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE model_id = #{modelId} AND schema_id = #{schemaId}",
    "AND model_name = #{modelName} AND model_latest_version = #{modelLatestVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreModelMeta(
      @Param("modelId") long modelId,
      @Param("schemaId") long schemaId,
      @Param("modelName") String modelName,
      @Param("modelLatestVersion") long modelLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{modelId}",
    "AND metadata_object_type = 'MODEL' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{modelId}",
    "AND type = 'MODEL' AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE metadata_object_id = #{modelId}",
    "AND metadata_object_type = 'MODEL' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTagRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes statistics from one exact generation. */
  @Delete({
    "DELETE FROM statistic_meta WHERE metadata_object_id = #{modelId}",
    "AND metadata_object_type = 'MODEL' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteStatistics(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes policy relations from one exact generation. */
  @Delete({
    "DELETE FROM policy_relation_meta WHERE metadata_object_id = #{modelId}",
    "AND metadata_object_type = 'MODEL' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeletePolicyRelations(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes aliases from one exact generation. */
  @Delete({
    "DELETE FROM model_version_alias_rel WHERE model_id = #{modelId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteModelAliases(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes URI rows from one exact generation. */
  @Delete({
    "DELETE FROM model_version_info WHERE model_id = #{modelId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteModelVersions(
      @Param("modelId") long modelId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the model base row from one exact generation. */
  @Delete({
    "DELETE FROM model_meta WHERE model_id = #{modelId} AND schema_id = #{schemaId}",
    "AND model_name = #{modelName} AND model_latest_version = #{modelLatestVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteModelMeta(
      @Param("modelId") long modelId,
      @Param("schemaId") long schemaId,
      @Param("modelName") String modelName,
      @Param("modelLatestVersion") long modelLatestVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
