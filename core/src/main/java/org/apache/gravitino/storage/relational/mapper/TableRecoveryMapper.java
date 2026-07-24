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

import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped table deletion and recovery. */
public interface TableRecoveryMapper {

  /** Locks the live parent schema to serialize table drop and restore transactions across nodes. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live table row under the already-locked parent schema. */
  @Select({
    "SELECT table_id AS tableId, table_name AS tableName, metalake_id AS metalakeId,",
    "catalog_id AS catalogId, schema_id AS schemaId, current_version AS currentVersion,",
    "last_version AS lastVersion, deleted_at AS deletedAt FROM table_meta",
    "WHERE schema_id = #{schemaId} AND table_name = #{tableName}",
    "AND deleted_at = 0 FOR UPDATE"
  })
  TablePO selectLiveTableForUpdate(
      @Param("schemaId") long schemaId, @Param("tableName") String tableName);

  /** Returns the newest timestamp for either the table ID or its current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion",
    "WHERE entity_type = 'TABLE' AND (entity_id = #{tableId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{tableName}))"
  })
  Long selectNewestTableDeletedAt(
      @Param("tableId") long tableId,
      @Param("schemaId") long schemaId,
      @Param("tableName") String tableName);

  /** Selects the table row for one exact deletion generation. */
  @Select({
    "SELECT table_id AS tableId, table_name AS tableName, metalake_id AS metalakeId,",
    "catalog_id AS catalogId, schema_id AS schemaId, current_version AS currentVersion,",
    "last_version AS lastVersion, deleted_at AS deletedAt FROM table_meta",
    "WHERE table_id = #{tableId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  TablePO selectTableGeneration(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live table snapshot. */
  @Update({
    "UPDATE table_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE table_id = #{tableId} AND schema_id = #{schemaId}",
    "AND table_name = #{tableName} AND current_version = #{tableVersion}",
    "AND deleted_at = 0"
  })
  int softDeleteTableMeta(
      @Param("tableId") long tableId,
      @Param("schemaId") long schemaId,
      @Param("tableName") String tableName,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the live owner relation for the table. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes every live column-version row owned by the table. */
  @Update({
    "UPDATE table_column_version_info SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId}",
    "WHERE table_id = #{tableId} AND deleted_at = 0"
  })
  int softDeleteColumns(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the table. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{tableId} AND type = 'TABLE' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the table and its columns. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE deleted_at = 0 AND (",
    "(metadata_object_type = 'TABLE' AND metadata_object_id = #{tableId}) OR",
    "(metadata_object_type = 'COLUMN' AND EXISTS (SELECT 1",
    "FROM table_column_version_info tc WHERE tc.table_id = #{tableId}",
    "AND tc.column_id = tag_relation_meta.metadata_object_id)))"
  })
  int softDeleteTagRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live table statistics. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = 0"
  })
  int softDeleteStatistics(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live policy relations for the table. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = 0"
  })
  int softDeletePolicyRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the table detail row for the captured version. */
  @Update({
    "UPDATE table_version_info SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE table_id = #{tableId} AND version = #{tableVersion} AND deleted_at = 0"
  })
  int softDeleteTableVersion(
      @Param("tableId") long tableId,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations that belong to the exact deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores column-version rows that belong to the exact deletion generation. */
  @Update({
    "UPDATE table_column_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE table_id = #{tableId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreColumns(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores role grants that belong to the exact deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{tableId} AND type = 'TABLE'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations that belong to the exact table deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId} AND (",
    "(metadata_object_type = 'TABLE' AND metadata_object_id = #{tableId}) OR",
    "(metadata_object_type = 'COLUMN' AND EXISTS (SELECT 1",
    "FROM table_column_version_info tc WHERE tc.table_id = #{tableId}",
    "AND tc.column_id = tag_relation_meta.metadata_object_id)))"
  })
  int restoreTagRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores statistics that belong to the exact deletion generation. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreStatistics(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores policy relations that belong to the exact deletion generation. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{tableId} AND metadata_object_type = 'TABLE'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restorePolicyRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the table detail row that belongs to the exact deletion generation. */
  @Update({
    "UPDATE table_version_info SET deleted_at = 0, deletion_id = NULL",
    "WHERE table_id = #{tableId} AND version = #{tableVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTableVersion(
      @Param("tableId") long tableId,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact table row after every child component has been verified. */
  @Update({
    "UPDATE table_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE table_id = #{tableId} AND schema_id = #{schemaId}",
    "AND table_name = #{tableName} AND current_version = #{tableVersion}",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTableMeta(
      @Param("tableId") long tableId,
      @Param("schemaId") long schemaId,
      @Param("tableName") String tableName,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact table deletion generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{tableId}",
    "AND metadata_object_type = 'TABLE' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact table deletion generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{tableId}",
    "AND type = 'TABLE' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact table deletion generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId} AND (",
    "(metadata_object_type = 'TABLE' AND metadata_object_id = #{tableId}) OR",
    "(metadata_object_type = 'COLUMN' AND EXISTS (SELECT 1",
    "FROM table_column_version_info tc WHERE tc.table_id = #{tableId}",
    "AND tc.column_id = tag_relation_meta.metadata_object_id",
    "AND tc.deleted_at = #{deletedAt} AND tc.deletion_id = #{deletionId})))"
  })
  int hardDeleteTagRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes statistics from one exact table deletion generation. */
  @Delete({
    "DELETE FROM statistic_meta WHERE metadata_object_id = #{tableId}",
    "AND metadata_object_type = 'TABLE' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteStatistics(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes policy relations from one exact table deletion generation. */
  @Delete({
    "DELETE FROM policy_relation_meta WHERE metadata_object_id = #{tableId}",
    "AND metadata_object_type = 'TABLE' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeletePolicyRelations(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes column rows from one exact table deletion generation. */
  @Delete({
    "DELETE FROM table_column_version_info",
    "WHERE table_id = #{tableId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteColumns(
      @Param("tableId") long tableId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the detail row from one exact table deletion generation. */
  @Delete({
    "DELETE FROM table_version_info WHERE table_id = #{tableId}",
    "AND version = #{tableVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTableVersion(
      @Param("tableId") long tableId,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the base row from one exact table deletion generation. */
  @Delete({
    "DELETE FROM table_meta WHERE table_id = #{tableId}",
    "AND schema_id = #{schemaId} AND table_name = #{tableName}",
    "AND current_version = #{tableVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTableMeta(
      @Param("tableId") long tableId,
      @Param("schemaId") long schemaId,
      @Param("tableName") String tableName,
      @Param("tableVersion") long tableVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
