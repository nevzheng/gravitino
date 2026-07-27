/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.storage.relational.mapper;

import static org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper.TABLE_NAME;

import javax.annotation.Nullable;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.ibatis.annotations.Param;

/** Portable SQL provider for durable metadata deletion generations. */
public class EntityDeletionSQLProvider {

  private static final String SELECT_COLUMNS = selectColumns("");

  /**
   * Builds the insert for one deletion generation.
   *
   * @param deletion deletion generation to insert
   * @return parameterized insert SQL
   */
  public static String insertEntityDeletion(@Param("deletion") EntityDeletionPO deletion) {
    return "INSERT INTO "
        + TABLE_NAME
        + " (deletion_id, entity_type, entity_id, entity_version, metalake_id, catalog_id,"
        + " parent_id, namespace_snapshot, entity_name_snapshot, active_name_key, state,"
        + " revision, deleted_at, retention_expires_at, deleted_by,"
        + " purge_requested, purge_job_type, purge_job_id, cleanup_status,"
        + " cleanup_attempt_count, cleanup_last_error, accepted_restore_etag, request_id,"
        + " correlation_id, restored_at, purged_at, updated_at)"
        + " VALUES (#{deletion.deletionId}, #{deletion.entityType}, #{deletion.entityId},"
        + " #{deletion.entityVersion}, #{deletion.metalakeId}, #{deletion.catalogId},"
        + " #{deletion.parentId}, #{deletion.namespaceSnapshot},"
        + " #{deletion.entityNameSnapshot}, #{deletion.activeNameKey}, #{deletion.state},"
        + " #{deletion.revision},"
        + " #{deletion.deletedAt}, #{deletion.retentionExpiresAt}, #{deletion.deletedBy},"
        + " #{deletion.purgeRequested}, #{deletion.purgeJobType}, #{deletion.purgeJobId},"
        + " #{deletion.cleanupStatus}, #{deletion.cleanupAttemptCount},"
        + " #{deletion.cleanupLastError}, #{deletion.acceptedRestoreEtag},"
        + " #{deletion.requestId}, #{deletion.correlationId}, #{deletion.restoredAt},"
        + " #{deletion.purgedAt}, #{deletion.updatedAt})";
  }

  /**
   * Builds an exact deletion-generation lookup.
   *
   * @param deletionId opaque deletion identifier
   * @return parameterized select SQL
   */
  public static String selectEntityDeletion(@Param("deletionId") String deletionId) {
    return "SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE deletion_id = #{deletionId}";
  }

  /**
   * Builds an exact deletion-generation locking lookup.
   *
   * @param deletionId opaque deletion identifier
   * @return parameterized select SQL
   */
  public static String selectEntityDeletionForUpdate(@Param("deletionId") String deletionId) {
    return selectEntityDeletion(deletionId) + " FOR UPDATE";
  }

  /**
   * Builds the lookup for the action reserving one canonical name.
   *
   * @param activeNameKey canonical active-name key
   * @return parameterized select SQL
   */
  public static String selectActiveEntityDeletion(@Param("activeNameKey") String activeNameKey) {
    return "SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE active_name_key = #{activeNameKey}";
  }

  /**
   * Builds a deleted-table lookup that follows the table row's current deletion pointer.
   *
   * <p>The join is the generation fence: an action is returned only while the retained table row
   * still points to that exact deletion id. A null table name lists the deleted rows under the
   * parent; a non-null table name performs the exact item lookup.
   *
   * @param parentId immutable schema id
   * @param tableName optional exact table name
   * @return parameterized joined lookup
   */
  public static String selectRetainedTableDeletions(
      @Param("parentId") long parentId, @Param("tableName") @Nullable String tableName) {
    String sql =
        "SELECT "
            + selectColumns("d.")
            + " FROM table_meta t JOIN "
            + TABLE_NAME
            + " d ON d.deletion_id = t.deletion_id AND d.entity_id = t.table_id"
            + " WHERE t.schema_id = #{parentId} AND t.deleted_at > 0"
            + " AND t.deletion_id IS NOT NULL AND d.entity_type = 'TABLE'";
    if (tableName != null) {
      sql += " AND t.table_name = #{tableName}";
    }
    return sql + " ORDER BY t.table_name, d.deletion_id";
  }

  private static String selectColumns(String prefix) {
    return prefix
        + "deletion_id AS deletionId, "
        + prefix
        + "entity_type AS entityType, "
        + prefix
        + "entity_id AS entityId, "
        + prefix
        + "entity_version AS entityVersion, "
        + prefix
        + "metalake_id AS metalakeId, "
        + prefix
        + "catalog_id AS catalogId, "
        + prefix
        + "parent_id AS parentId, "
        + prefix
        + "namespace_snapshot AS namespaceSnapshot, "
        + prefix
        + "entity_name_snapshot AS entityNameSnapshot, "
        + prefix
        + "active_name_key AS activeNameKey, "
        + prefix
        + "state, "
        + prefix
        + "revision, "
        + prefix
        + "deleted_at AS deletedAt, "
        + prefix
        + "retention_expires_at AS retentionExpiresAt, "
        + prefix
        + "deleted_by AS deletedBy, "
        + prefix
        + "purge_requested AS purgeRequested, "
        + prefix
        + "purge_job_type AS purgeJobType, "
        + prefix
        + "purge_job_id AS purgeJobId, "
        + prefix
        + "cleanup_status AS cleanupStatus, "
        + prefix
        + "cleanup_attempt_count AS cleanupAttemptCount, "
        + prefix
        + "cleanup_last_error AS cleanupLastError, "
        + prefix
        + "accepted_restore_etag AS acceptedRestoreEtag, "
        + prefix
        + "request_id AS requestId, "
        + prefix
        + "correlation_id AS correlationId, "
        + prefix
        + "restored_at AS restoredAt, "
        + prefix
        + "purged_at AS purgedAt, "
        + prefix
        + "updated_at AS updatedAt";
  }

  /**
   * Builds the compare-and-set that completes a retained restore.
   *
   * @param deletionId opaque deletion identifier
   * @param expectedRevision revision supplied by the strong ETag
   * @param serverNow authoritative transaction time
   * @param acceptedRestoreEtag accepted strong ETag
   * @return parameterized update SQL
   */
  public static String restoreEntityDeletion(
      @Param("deletionId") String deletionId,
      @Param("expectedRevision") long expectedRevision,
      @Param("serverNow") long serverNow,
      @Param("acceptedRestoreEtag") String acceptedRestoreEtag) {
    return "UPDATE "
        + TABLE_NAME
        + " SET state = 'RESTORED', revision = revision + 1, active_name_key = NULL,"
        + " cleanup_status = NULL, cleanup_last_error = NULL,"
        + " accepted_restore_etag = #{acceptedRestoreEtag}, restored_at = #{serverNow},"
        + " updated_at = #{serverNow}"
        + " WHERE deletion_id = #{deletionId} AND state = 'DELETED'"
        + " AND revision = #{expectedRevision} AND purge_job_id IS NULL"
        + " AND retention_expires_at IS NOT NULL"
        + " AND retention_expires_at > #{serverNow}";
  }
}
