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

package org.apache.gravitino.iceberg.service.deletion.purge.mapper;

import org.apache.ibatis.annotations.Param;

/** Portable exact-generation hard-delete SQL for table metadata. */
public class IcebergPurgeMetadataSQLProvider {

  /** Counts the exact table parent identified by immutable table id and deletion generation. */
  public static String countExactTable(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return "SELECT COUNT(*) FROM table_meta WHERE table_id = #{tableId}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Deletes the exact generation's column rows. */
  public static String deleteColumns(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactTableDelete("table_column_version_info", "table_id");
  }

  /** Deletes the exact generation's table-version rows. */
  public static String deleteVersions(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactTableDelete("table_version_info", "table_id");
  }

  /** Deletes the exact generation's owner rows. */
  public static String deleteOwners(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactObjectDelete("owner_meta", "metadata_object_type");
  }

  /** Deletes the exact generation's privilege rows. */
  public static String deleteSecurableObjects(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactObjectDelete("role_meta_securable_object", "type");
  }

  /** Deletes the exact generation's tag relations. */
  public static String deleteTagRelations(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactObjectDelete("tag_relation_meta", "metadata_object_type");
  }

  /** Deletes the exact generation's policy relations. */
  public static String deletePolicyRelations(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactObjectDelete("policy_relation_meta", "metadata_object_type");
  }

  /** Deletes the exact generation's statistic rows. */
  public static String deleteStatistics(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactObjectDelete("statistic_meta", "metadata_object_type");
  }

  /** Deletes the exact table parent last. */
  public static String deleteTable(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId) {
    return exactTableDelete("table_meta", "table_id");
  }

  private static String exactTableDelete(String table, String idColumn) {
    return "DELETE FROM "
        + table
        + " WHERE "
        + idColumn
        + " = #{tableId} AND deletion_id = #{deletionId}";
  }

  private static String exactObjectDelete(String table, String typeColumn) {
    return "DELETE FROM "
        + table
        + " WHERE metadata_object_id = #{tableId} AND "
        + typeColumn
        + " = 'TABLE' AND deletion_id = #{deletionId}";
  }
}
