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
import org.apache.gravitino.storage.relational.po.ViewPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped view deletion and recovery. */
public interface ViewRecoveryMapper {

  /** Result map for view base rows used by recovery. */
  @Results(
      id = "viewRecoveryResultMap",
      value = {
        @Result(property = "viewId", column = "view_id", id = true),
        @Result(property = "viewName", column = "view_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaId", column = "schema_id"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  ViewPO viewRecoveryResultMap();

  /** Locks the live parent schema to serialize child mutations and recovery. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live view under the already-locked schema. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE schema_id = #{schemaId} AND view_name = #{viewName}",
    "AND deleted_at = 0 FOR UPDATE"
  })
  ViewPO selectLiveViewForUpdate(
      @Param("schemaId") long schemaId, @Param("viewName") String viewName);

  /** Locks a recoverable tombstone whose immutable ID would otherwise be overwritten. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE view_id = #{viewId} AND deleted_at > 0",
    "AND deletion_id IS NOT NULL FOR UPDATE"
  })
  ViewPO selectRecordedDeletedViewForUpdate(@Param("viewId") long viewId);

  /** Returns the newest generation timestamp for either the ID or current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion WHERE entity_type = 'VIEW'",
    "AND (entity_id = #{viewId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{viewName}))"
  })
  Long selectNewestViewDeletedAt(
      @Param("viewId") long viewId,
      @Param("schemaId") long schemaId,
      @Param("viewName") String viewName);

  /** Lists deleted view base rows below one live schema, newest first. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at > 0",
    "ORDER BY deleted_at DESC, view_id DESC"
  })
  List<ViewPO> listDeletedViews(@Param("schemaId") long schemaId);

  /** Lists live view identities below one schema. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0"
  })
  List<ViewPO> listLiveViews(@Param("schemaId") long schemaId);

  /** Lists globally live rows for candidate immutable IDs. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE deleted_at = 0 AND view_id IN",
    "<foreach collection='viewIds' item='viewId' open='(' separator=',' close=')'>",
    "#{viewId}",
    "</foreach>",
    "</script>"
  })
  List<ViewPO> listLiveViewsByIds(@Param("viewIds") List<Long> viewIds);

  /** Selects the view base row for one exact deletion generation. */
  @ResultMap("viewRecoveryResultMap")
  @Select({
    "SELECT view_id, view_name, metalake_id, catalog_id, schema_id, audit_info,",
    "current_version, last_version, deleted_at, deletion_id FROM view_meta",
    "WHERE view_id = #{viewId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  ViewPO selectViewGeneration(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the captured current-version row for one exact deletion generation. */
  @Select({
    "SELECT COUNT(*) FROM view_version_info WHERE view_id = #{viewId}",
    "AND version = #{viewVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int countCurrentVersionGeneration(
      @Param("viewId") long viewId,
      @Param("viewVersion") long viewVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live view base row. */
  @Update({
    "UPDATE view_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE view_id = #{viewId} AND schema_id = #{schemaId}",
    "AND view_name = #{viewName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = 0"
  })
  int softDeleteViewMeta(
      @Param("viewId") long viewId,
      @Param("schemaId") long schemaId,
      @Param("viewName") String viewName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live version retained by the view at drop time. */
  @Update({
    "UPDATE view_version_info SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE view_id = #{viewId} AND deleted_at = 0"
  })
  int softDeleteViewVersions(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live owner relations for the view. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{viewId}",
    "AND metadata_object_type = 'VIEW' AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the view. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{viewId}",
    "AND type = 'VIEW' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the view. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{viewId} AND metadata_object_type = 'VIEW'",
    "AND deleted_at = 0"
  })
  int softDeleteTagRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations in one exact view deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{viewId} AND metadata_object_type = 'VIEW'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores role grants in one exact view deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{viewId} AND type = 'VIEW'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations in one exact view deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{viewId} AND metadata_object_type = 'VIEW'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTagRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores every retained version in one exact view deletion generation. */
  @Update({
    "UPDATE view_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE view_id = #{viewId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreViewVersions(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact view base row after its aggregate has been verified. */
  @Update({
    "UPDATE view_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE view_id = #{viewId} AND schema_id = #{schemaId}",
    "AND view_name = #{viewName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreViewMeta(
      @Param("viewId") long viewId,
      @Param("schemaId") long schemaId,
      @Param("viewName") String viewName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{viewId}",
    "AND metadata_object_type = 'VIEW' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{viewId}",
    "AND type = 'VIEW' AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE metadata_object_id = #{viewId}",
    "AND metadata_object_type = 'VIEW' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTagRelations(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes every retained version from one exact generation. */
  @Delete({
    "DELETE FROM view_version_info WHERE view_id = #{viewId}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteViewVersions(
      @Param("viewId") long viewId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the view base row from one exact generation. */
  @Delete({
    "DELETE FROM view_meta WHERE view_id = #{viewId} AND schema_id = #{schemaId}",
    "AND view_name = #{viewName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteViewMeta(
      @Param("viewId") long viewId,
      @Param("schemaId") long schemaId,
      @Param("viewName") String viewName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
