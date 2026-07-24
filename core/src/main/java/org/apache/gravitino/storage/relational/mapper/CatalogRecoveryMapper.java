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
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped catalog-tree deletion and recovery. */
public interface CatalogRecoveryMapper {

  /** Result map for catalog base rows used by recovery. */
  @Results(
      id = "catalogRecoveryResultMap",
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
  CatalogPO catalogRecoveryResultMap();

  /** Result map for schema rows below a catalog recovery root. */
  @Results(
      id = "catalogRecoverySchemaResultMap",
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
  SchemaPO catalogRecoverySchemaResultMap();

  /** Locks the live metalake that serializes catalog membership changes. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks the live catalog recovery root. */
  @Select({
    "SELECT catalog_id FROM catalog_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveCatalog(@Param("catalogId") long catalogId);

  /** Locks every live schema below one catalog in deterministic identifier order. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = 0",
    "ORDER BY schema_id FOR UPDATE"
  })
  List<Long> lockLiveSchemas(@Param("catalogId") long catalogId);

  /** Locks tokenized catalog rows whose IDs must not be overwritten outside recovery. */
  @ResultMap("catalogRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT catalog_id, catalog_name, metalake_id, type, provider, catalog_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM catalog_meta",
    "WHERE deleted_at > 0 AND deletion_id IS NOT NULL AND catalog_id IN",
    "<foreach collection='catalogIds' item='catalogId' open='(' separator=',' close=')'>",
    "#{catalogId}",
    "</foreach>",
    "ORDER BY catalog_id FOR UPDATE",
    "</script>"
  })
  List<CatalogPO> selectRecordedDeletedCatalogsForUpdate(
      @Param("catalogIds") List<Long> catalogIds);

  /** Lists globally live catalog rows matching candidate immutable IDs. */
  @ResultMap("catalogRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT catalog_id, catalog_name, metalake_id, type, provider, catalog_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM catalog_meta",
    "WHERE deleted_at = 0 AND catalog_id IN",
    "<foreach collection='catalogIds' item='catalogId' open='(' separator=',' close=')'>",
    "#{catalogId}",
    "</foreach>",
    "</script>"
  })
  List<CatalogPO> listLiveCatalogsByIds(@Param("catalogIds") List<Long> catalogIds);

  /** Lists independently recorded catalog-root tombstones below one live metalake. */
  @ResultMap("catalogRecoveryResultMap")
  @Select({
    "SELECT cm.catalog_id, cm.catalog_name, cm.metalake_id, cm.type, cm.provider,",
    "cm.catalog_comment, cm.properties, cm.audit_info, cm.current_version, cm.last_version,",
    "cm.deleted_at, cm.deletion_id FROM catalog_meta cm LEFT JOIN entity_deletion ed",
    "ON ed.entity_type = 'CATALOG' AND ed.entity_id = cm.catalog_id",
    "AND ed.deleted_at = cm.deleted_at AND ed.deletion_id = cm.deletion_id",
    "WHERE cm.metalake_id = #{metalakeId} AND cm.deleted_at > 0 AND (",
    "cm.deletion_id IS NULL OR (cm.deletion_id IS NOT NULL AND ed.parent_id = #{metalakeId}))",
    "ORDER BY cm.deleted_at DESC, cm.catalog_id DESC"
  })
  List<CatalogPO> listDeletedRootCatalogs(@Param("metalakeId") long metalakeId);

  /** Selects and locks one exact recorded catalog root generation. */
  @ResultMap("catalogRecoveryResultMap")
  @Select({
    "SELECT catalog_id, catalog_name, metalake_id, type, provider, catalog_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM catalog_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId} FOR UPDATE"
  })
  CatalogPO selectCatalogGenerationForUpdate(
      @Param("catalogId") long catalogId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Lists live schemas below one catalog. */
  @ResultMap("catalogRecoverySchemaResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE catalog_id = #{catalogId} AND deleted_at = 0 ORDER BY schema_id"
  })
  List<SchemaPO> listLiveSchemas(@Param("catalogId") long catalogId);

  /** Locks every schema row captured by one exact catalog deletion generation. */
  @ResultMap("catalogRecoverySchemaResultMap")
  @Select({
    "SELECT schema_id, schema_name, metalake_id, catalog_id, schema_comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM schema_meta",
    "WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}",
    "ORDER BY schema_id FOR UPDATE"
  })
  List<SchemaPO> listCatalogSchemaGenerationForUpdate(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Counts every live schema and leaf entity currently below one catalog. */
  @Select({
    "SELECT",
    "(SELECT COUNT(*) FROM schema_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM table_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM fileset_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM topic_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM function_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM model_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0) +",
    "(SELECT COUNT(*) FROM view_meta WHERE catalog_id = #{catalogId} AND deleted_at = 0)"
  })
  int countLiveDescendants(@Param("catalogId") long catalogId);

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

  /** Counts tokenized rows whose parent is absent from the same catalog generation. */
  @SelectProvider(
      type = CatalogRecoverySQLProvider.class,
      method = "countBrokenGenerationReferences")
  int countBrokenGenerationReferences(
      @Param("deletedAt") long deletedAt, @Param("deletionId") String deletionId);

  /** Returns the greatest prior tombstone timestamp belonging to the target catalog tree. */
  @SelectProvider(
      type = CatalogRecoverySQLProvider.class,
      method = "selectNewestAggregateDeletedAt")
  Long selectNewestAggregateDeletedAt(@Param("catalogId") long catalogId);

  /** Stamps one exact deletion generation on live rows from one aggregate table. */
  @UpdateProvider(type = CatalogRecoverySQLProvider.class, method = "softDeleteAggregateRows")
  int softDeleteAggregateRows(
      @Param("aggregateTable") CatalogAggregateTable aggregateTable,
      @Param("catalogId") long catalogId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one aggregate table belonging to an exact deletion generation. */
  @SelectProvider(type = CatalogRecoverySQLProvider.class, method = "countGenerationRows")
  int countGenerationRows(
      @Param("aggregateTable") CatalogAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores rows from one aggregate table belonging to an exact deletion generation. */
  @UpdateProvider(type = CatalogRecoverySQLProvider.class, method = "restoreGenerationRows")
  int restoreGenerationRows(
      @Param("aggregateTable") CatalogAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Permanently removes rows from one exact catalog deletion generation. */
  @DeleteProvider(type = CatalogRecoverySQLProvider.class, method = "hardDeleteGenerationRows")
  int hardDeleteGenerationRows(
      @Param("aggregateTable") CatalogAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
