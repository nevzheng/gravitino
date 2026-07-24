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
package org.apache.gravitino.storage.relational.mapper.provider.postgresql;

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.ViewVersionInfoMapper;
import org.apache.gravitino.storage.relational.mapper.provider.base.ViewVersionInfoBaseSQLProvider;
import org.apache.gravitino.storage.relational.po.ViewVersionInfoPO;
import org.apache.ibatis.annotations.Param;

public class ViewVersionInfoPostgreSQLProvider extends ViewVersionInfoBaseSQLProvider {

  @Override
  public String insertViewVersionInfoOnDuplicateKeyUpdate(
      @Param("viewVersionInfo") ViewVersionInfoPO viewVersionInfoPO) {
    return "INSERT INTO "
        + ViewVersionInfoMapper.TABLE_NAME
        + " (metalake_id, catalog_id, schema_id, view_id, version,"
        + " view_comment, columns, properties, default_catalog, default_schema, representations,"
        + " audit_info, deleted_at, deletion_id)"
        + " VALUES (#{viewVersionInfo.metalakeId}, #{viewVersionInfo.catalogId},"
        + " #{viewVersionInfo.schemaId}, #{viewVersionInfo.viewId},"
        + " #{viewVersionInfo.version}, #{viewVersionInfo.viewComment},"
        + " #{viewVersionInfo.columns}, #{viewVersionInfo.properties},"
        + " #{viewVersionInfo.defaultCatalog}, #{viewVersionInfo.defaultSchema},"
        + " #{viewVersionInfo.representations},"
        + " #{viewVersionInfo.auditInfo}, #{viewVersionInfo.deletedAt},"
        + " #{viewVersionInfo.deletionId})"
        + " ON CONFLICT (view_id, version, deleted_at) DO UPDATE SET"
        + " view_comment = #{viewVersionInfo.viewComment},"
        + " columns = #{viewVersionInfo.columns},"
        + " properties = #{viewVersionInfo.properties},"
        + " default_catalog = #{viewVersionInfo.defaultCatalog},"
        + " default_schema = #{viewVersionInfo.defaultSchema},"
        + " representations = #{viewVersionInfo.representations},"
        + " audit_info = #{viewVersionInfo.auditInfo},"
        + " deleted_at = #{viewVersionInfo.deletedAt},"
        + " deletion_id = #{viewVersionInfo.deletionId}";
  }

  @Override
  public String softDeleteViewVersionsByViewId(@Param("viewId") Long viewId) {
    return "UPDATE "
        + ViewVersionInfoMapper.TABLE_NAME
        + " SET deleted_at = CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),"
        + " deletion_id = NULL"
        + " WHERE view_id = #{viewId} AND deleted_at = 0";
  }

  @Override
  public String softDeleteViewVersionsBySchemaId(@Param("schemaId") Long schemaId) {
    return "UPDATE "
        + ViewVersionInfoMapper.TABLE_NAME
        + " SET deleted_at = CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),"
        + " deletion_id = NULL"
        + " WHERE schema_id = #{schemaId} AND deleted_at = 0";
  }

  /** Returns PostgreSQL SQL that soft-deletes live view versions under the schema IDs. */
  @Override
  public String softDeleteViewVersionsBySchemaIds(@Param("schemaIds") List<Long> schemaIds) {
    return "<script>"
        + "UPDATE "
        + ViewVersionInfoMapper.TABLE_NAME
        + " SET deleted_at = CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),"
        + " deletion_id = NULL"
        + " WHERE schema_id IN ("
        + "<foreach collection='schemaIds' item='schemaId' separator=','>"
        + "#{schemaId}"
        + "</foreach>"
        + ") AND deleted_at = 0"
        + "</script>";
  }

  @Override
  public String softDeleteViewVersionsByCatalogId(@Param("catalogId") Long catalogId) {
    return "UPDATE "
        + ViewVersionInfoMapper.TABLE_NAME
        + " SET deleted_at = CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),"
        + " deletion_id = NULL"
        + " WHERE catalog_id = #{catalogId} AND deleted_at = 0";
  }

  @Override
  public String softDeleteViewVersionsByMetalakeId(@Param("metalakeId") Long metalakeId) {
    return "UPDATE "
        + ViewVersionInfoMapper.TABLE_NAME
        + " SET deleted_at = CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),"
        + " deletion_id = NULL"
        + " WHERE metalake_id = #{metalakeId} AND deleted_at = 0";
  }

  @Override
  public String deleteViewVersionsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ViewVersionInfoMapper.TABLE_NAME
        + " WHERE id IN (SELECT id FROM "
        + ViewVersionInfoMapper.TABLE_NAME
        + " WHERE deletion_id IS NULL AND deleted_at > 0"
        + " AND deleted_at < #{legacyTimeline} LIMIT #{limit})"
        + " AND deletion_id IS NULL";
  }
}
