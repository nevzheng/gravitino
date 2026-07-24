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
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped schema-tree deletion and recovery. */
public interface SchemaRecoveryMapper {

  /** Result map for schema base rows used by recovery. */
  @Results(
      id = "schemaRecoveryResultMap",
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
  SchemaPO schemaRecoveryResultMap();

  /** Locks the live catalog that serializes every schema-tree membership change. */
  @Select({
    "SELECT catalog_id FROM catalog_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveCatalog(@Param("catalogId") long catalogId);

  /** Locks live schemas in deterministic identifier order. */
  @Select({
    "<script>",
    "SELECT schema_id FROM schema_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}",
    "</foreach>",
    "ORDER BY schema_id FOR UPDATE",
    "</script>"
  })
  List<Long> lockLiveSchemas(@Param("schemaIds") List<Long> schemaIds);

  /** Locks tokenized schema rows whose IDs must not be overwritten outside recovery. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE deleted_at > 0 AND deletion_id IS NOT NULL AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}",
    "</foreach>",
    "ORDER BY schema_id FOR UPDATE",
    "</script>"
  })
  List<SchemaPO> selectRecordedDeletedSchemasForUpdate(@Param("schemaIds") List<Long> schemaIds);

  /** Lists live schema rows in one catalog. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = 0"
  })
  List<SchemaPO> listLiveSchemas(@Param("catalogId") long catalogId);

  /** Lists globally live schema rows matching candidate immutable IDs. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}",
    "</foreach>",
    "</script>"
  })
  List<SchemaPO> listLiveSchemasByIds(@Param("schemaIds") List<Long> schemaIds);

  /** Lists independently recorded root tombstones below one immutable immediate parent. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "SELECT sm.schema_id, sm.schema_name, sm.metalake_id, sm.catalog_id, sm.schema_comment,",
    "sm.properties, sm.audit_info, sm.current_version, sm.last_version, sm.deleted_at,",
    "sm.deletion_id FROM schema_meta sm LEFT JOIN entity_deletion ed",
    "ON ed.entity_type = 'SCHEMA' AND ed.entity_id = sm.schema_id",
    "AND ed.deleted_at = sm.deleted_at AND ed.deletion_id = sm.deletion_id",
    "WHERE sm.catalog_id = #{catalogId} AND sm.deleted_at > 0 AND (",
    "sm.deletion_id IS NULL OR (sm.deletion_id IS NOT NULL AND ed.parent_id = #{parentId}))",
    "ORDER BY sm.deleted_at DESC, sm.schema_id DESC"
  })
  List<SchemaPO> listDeletedRootSchemas(
      @Param("catalogId") long catalogId, @Param("parentId") long parentId);

  /** Selects the exact recorded schema root generation. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId} FOR UPDATE"
  })
  SchemaPO selectSchemaGenerationForUpdate(
      @Param("schemaId") long schemaId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks and lists every schema row captured by one exact root deletion generation. */
  @ResultMap("schemaRecoveryResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}",
    "ORDER BY schema_id FOR UPDATE"
  })
  List<SchemaPO> listSchemaGenerationForUpdate(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Counts live leaf entities under the already-locked schema IDs. */
  @Select({
    "<script>",
    "SELECT",
    "(SELECT COUNT(*) FROM table_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>) +",
    "(SELECT COUNT(*) FROM fileset_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>) +",
    "(SELECT COUNT(*) FROM topic_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>) +",
    "(SELECT COUNT(*) FROM function_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>) +",
    "(SELECT COUNT(*) FROM model_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>) +",
    "(SELECT COUNT(*) FROM view_meta WHERE deleted_at = 0 AND schema_id IN",
    "<foreach collection='schemaIds' item='schemaId' open='(' separator=',' close=')'>",
    "#{schemaId}</foreach>)",
    "</script>"
  })
  int countLiveLeafEntities(@Param("schemaIds") List<Long> schemaIds);

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
    "AND vv.deleted_at = #{deletedAt} AND vv.deletion_id = #{deletionId}))"
  })
  int countMissingRequiredDetails(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Counts generation relations whose owner, role, tag, or policy source is no longer live. */
  @SelectProvider(type = SchemaRecoverySQLProvider.class, method = "countBrokenExternalReferences")
  int countBrokenExternalReferences(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Returns the greatest prior tombstone timestamp belonging to the target schema tree. */
  @SelectProvider(type = SchemaRecoverySQLProvider.class, method = "selectNewestAggregateDeletedAt")
  Long selectNewestAggregateDeletedAt(
      @Param("catalogId") long catalogId, @Param("schemaIds") List<Long> schemaIds);

  /** Stamps one exact deletion generation on live rows from one aggregate table. */
  @UpdateProvider(type = SchemaRecoverySQLProvider.class, method = "softDeleteAggregateRows")
  int softDeleteAggregateRows(
      @Param("aggregateTable") SchemaAggregateTable aggregateTable,
      @Param("schemaIds") List<Long> schemaIds,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one aggregate table belonging to an exact deletion generation. */
  @SelectProvider(type = SchemaRecoverySQLProvider.class, method = "countGenerationRows")
  int countGenerationRows(
      @Param("aggregateTable") SchemaAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores rows from one aggregate table belonging to an exact deletion generation. */
  @UpdateProvider(type = SchemaRecoverySQLProvider.class, method = "restoreGenerationRows")
  int restoreGenerationRows(
      @Param("aggregateTable") SchemaAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Permanently removes rows from one exact schema deletion generation. */
  @DeleteProvider(type = SchemaRecoverySQLProvider.class, method = "hardDeleteGenerationRows")
  int hardDeleteGenerationRows(
      @Param("aggregateTable") SchemaAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
