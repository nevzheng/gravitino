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
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped metalake-tree deletion and recovery. */
public interface MetalakeRecoveryMapper {

  /** Result map for metalake base rows used by recovery. */
  @Results(
      id = "metalakeRecoveryResultMap",
      value = {
        @Result(property = "metalakeId", column = "metalake_id", id = true),
        @Result(property = "metalakeName", column = "metalake_name"),
        @Result(property = "metalakeComment", column = "metalake_comment"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "schemaVersion", column = "schema_version"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  MetalakePO metalakeRecoveryResultMap();

  /** Result map for catalog rows below a metalake recovery root. */
  @Results(
      id = "metalakeRecoveryCatalogResultMap",
      value = {
        @Result(property = "catalogId", column = "catalog_id", id = true),
        @Result(property = "catalogName", column = "catalog_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "type", column = "type"),
        @Result(property = "provider", column = "provider"),
        @Result(property = "catalogComment", column = "catalog_comment"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  CatalogPO metalakeRecoveryCatalogResultMap();

  /** Result map for schema rows below a metalake recovery root. */
  @Results(
      id = "metalakeRecoverySchemaResultMap",
      value = {
        @Result(property = "schemaId", column = "schema_id", id = true),
        @Result(property = "schemaName", column = "schema_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaComment", column = "schema_comment"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  SchemaPO metalakeRecoverySchemaResultMap();

  /** Locks the live metalake recovery root. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks every live catalog below one metalake in deterministic identifier order. */
  @Select({
    "SELECT catalog_id FROM catalog_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0",
    "ORDER BY catalog_id FOR UPDATE"
  })
  List<Long> lockLiveCatalogs(@Param("metalakeId") long metalakeId);

  /** Locks every live schema below one metalake in deterministic identifier order. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0",
    "ORDER BY schema_id FOR UPDATE"
  })
  List<Long> lockLiveSchemas(@Param("metalakeId") long metalakeId);

  /** Locks tokenized metalake rows whose IDs must not be overwritten outside recovery. */
  @ResultMap("metalakeRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT metalake_id, metalake_name, metalake_comment, properties, audit_info,",
    "schema_version, current_version, last_version, deleted_at, deletion_id",
    "FROM metalake_meta WHERE deleted_at > 0 AND deletion_id IS NOT NULL AND metalake_id IN",
    "<foreach collection='metalakeIds' item='metalakeId' open='(' separator=',' close=')'>",
    "#{metalakeId}",
    "</foreach>",
    "ORDER BY metalake_id FOR UPDATE",
    "</script>"
  })
  List<MetalakePO> selectRecordedDeletedMetalakesForUpdate(
      @Param("metalakeIds") List<Long> metalakeIds);

  /** Lists globally live metalake rows matching candidate immutable IDs. */
  @ResultMap("metalakeRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT metalake_id, metalake_name, metalake_comment, properties, audit_info,",
    "schema_version, current_version, last_version, deleted_at, deletion_id",
    "FROM metalake_meta WHERE deleted_at = 0 AND metalake_id IN",
    "<foreach collection='metalakeIds' item='metalakeId' open='(' separator=',' close=')'>",
    "#{metalakeId}",
    "</foreach>",
    "</script>"
  })
  List<MetalakePO> listLiveMetalakesByIds(@Param("metalakeIds") List<Long> metalakeIds);

  /** Lists independently recorded and legacy metalake-root tombstones. */
  @ResultMap("metalakeRecoveryResultMap")
  @Select({
    "SELECT mm.metalake_id, mm.metalake_name, mm.metalake_comment, mm.properties,",
    "mm.audit_info, mm.schema_version, mm.current_version, mm.last_version,",
    "mm.deleted_at, mm.deletion_id FROM metalake_meta mm LEFT JOIN entity_deletion ed",
    "ON ed.entity_type = 'METALAKE' AND ed.entity_id = mm.metalake_id",
    "AND ed.deleted_at = mm.deleted_at AND ed.deletion_id = mm.deletion_id",
    "WHERE mm.deleted_at > 0 AND (mm.deletion_id IS NULL OR",
    "(mm.deletion_id IS NOT NULL AND ed.parent_id IS NULL))",
    "ORDER BY mm.deleted_at DESC, mm.metalake_id DESC"
  })
  List<MetalakePO> listDeletedRootMetalakes();

  /** Selects and locks one exact recorded metalake root generation. */
  @ResultMap("metalakeRecoveryResultMap")
  @Select({
    "SELECT metalake_id, metalake_name, metalake_comment, properties, audit_info,",
    "schema_version, current_version, last_version, deleted_at, deletion_id",
    "FROM metalake_meta WHERE metalake_id = #{metalakeId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId} FOR UPDATE"
  })
  MetalakePO selectMetalakeGenerationForUpdate(
      @Param("metalakeId") long metalakeId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks every catalog row captured by one exact metalake deletion generation. */
  @ResultMap("metalakeRecoveryCatalogResultMap")
  @Select({
    "SELECT catalog_id, catalog_name, metalake_id, type, provider, catalog_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM catalog_meta",
    "WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}",
    "ORDER BY catalog_id FOR UPDATE"
  })
  List<CatalogPO> listMetalakeCatalogGenerationForUpdate(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Locks every schema row captured by one exact metalake deletion generation. */
  @ResultMap("metalakeRecoverySchemaResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}",
    "ORDER BY schema_id FOR UPDATE"
  })
  List<SchemaPO> listMetalakeSchemaGenerationForUpdate(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Counts live entity roots below one metalake. */
  @Select({
    "SELECT",
    "(SELECT COUNT(*) FROM catalog_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM schema_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM table_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM fileset_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM topic_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM function_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM model_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM view_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM user_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM group_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM role_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM tag_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM policy_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM job_template_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM job_run_meta WHERE metalake_id = #{metalakeId} AND deleted_at = 0)"
  })
  int countLiveDescendants(@Param("metalakeId") long metalakeId);

  /** Counts base rows whose required current detail row is absent from the same generation. */
  @Select({
    "SELECT",
    "(SELECT COUNT(*) FROM table_meta tm WHERE tm.deleted_at = #{deletedAt}",
    "AND tm.deletion_id = #{deletionId} AND NOT EXISTS (SELECT 1 FROM table_version_info tv",
    "WHERE tv.table_id = tm.table_id AND tv.version = tm.current_version",
    "AND tv.deleted_at = #{deletedAt} AND tv.deletion_id = #{deletionId})) +",
    "(SELECT COUNT(*) FROM fileset_meta fm WHERE fm.deleted_at = #{deletedAt}",
    "AND fm.deletion_id = #{deletionId} AND NOT EXISTS (SELECT 1 FROM fileset_version_info fv",
    "WHERE fv.fileset_id = fm.fileset_id AND fv.version = fm.current_version",
    "AND fv.deleted_at = #{deletedAt} AND fv.deletion_id = #{deletionId})) +",
    "(SELECT COUNT(*) FROM function_meta fn WHERE fn.deleted_at = #{deletedAt}",
    "AND fn.deletion_id = #{deletionId} AND NOT EXISTS (SELECT 1 FROM function_version_info fvi",
    "WHERE fvi.function_id = fn.function_id AND fvi.version = fn.function_current_version",
    "AND fvi.deleted_at = #{deletedAt} AND fvi.deletion_id = #{deletionId})) +",
    "(SELECT COUNT(*) FROM view_meta vm WHERE vm.deleted_at = #{deletedAt}",
    "AND vm.deletion_id = #{deletionId} AND NOT EXISTS (SELECT 1 FROM view_version_info vv",
    "WHERE vv.view_id = vm.view_id AND vv.version = vm.current_version",
    "AND vv.deleted_at = #{deletedAt} AND vv.deletion_id = #{deletionId})) +",
    "(SELECT COUNT(*) FROM policy_meta pm WHERE pm.deleted_at = #{deletedAt}",
    "AND pm.deletion_id = #{deletionId} AND NOT EXISTS (SELECT 1 FROM policy_version_info pv",
    "WHERE pv.policy_id = pm.policy_id AND pv.version = pm.current_version",
    "AND pv.deleted_at = #{deletedAt} AND pv.deletion_id = #{deletionId}))"
  })
  int countMissingRequiredDetails(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Counts tokenized rows whose required parent is absent from the same generation. */
  @SelectProvider(
      type = MetalakeRecoverySQLProvider.class,
      method = "countBrokenGenerationReferences")
  int countBrokenGenerationReferences(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Returns the greatest prior tombstone timestamp belonging to the target metalake tree. */
  @SelectProvider(
      type = MetalakeRecoverySQLProvider.class,
      method = "selectNewestAggregateDeletedAt")
  Long selectNewestAggregateDeletedAt(@Param("metalakeId") long metalakeId);

  /** Stamps one exact deletion generation on live rows from one aggregate table. */
  @UpdateProvider(type = MetalakeRecoverySQLProvider.class, method = "softDeleteAggregateRows")
  int softDeleteAggregateRows(
      @Param("aggregateTable") MetalakeAggregateTable aggregateTable,
      @Param("metalakeId") long metalakeId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one aggregate table belonging to an exact deletion generation. */
  @SelectProvider(type = MetalakeRecoverySQLProvider.class, method = "countGenerationRows")
  int countGenerationRows(
      @Param("aggregateTable") MetalakeAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores rows from one aggregate table belonging to an exact deletion generation. */
  @UpdateProvider(type = MetalakeRecoverySQLProvider.class, method = "restoreGenerationRows")
  int restoreGenerationRows(
      @Param("aggregateTable") MetalakeAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Permanently removes rows from one exact metalake deletion generation. */
  @DeleteProvider(type = MetalakeRecoverySQLProvider.class, method = "hardDeleteGenerationRows")
  int hardDeleteGenerationRows(
      @Param("aggregateTable") MetalakeAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
