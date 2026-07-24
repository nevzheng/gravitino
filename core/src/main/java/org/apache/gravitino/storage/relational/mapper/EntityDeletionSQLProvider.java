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

import static org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper.TABLE_NAME;

import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.ibatis.annotations.Param;

/** SQL provider for durable recoverable-deletion records. */
public class EntityDeletionSQLProvider {

  private static final String SELECT_COLUMNS =
      "deletion_id AS deletionId, entity_type AS entityType, entity_id AS entityId,"
          + " metalake_id AS metalakeId, catalog_id AS catalogId, parent_id AS parentId,"
          + " entity_name AS entityName, deleted_at AS deletedAt, expires_at AS expiresAt,"
          + " deleted_by AS deletedBy, entity_version AS entityVersion,"
          + " affected_row_count AS affectedRowCount, state,"
          + " revision, restored_at AS restoredAt,"
          + " restore_etag AS restoreEtag, purged_at AS purgedAt";

  /**
   * Builds the insert for a deletion generation.
   *
   * @param deletion deletion record
   * @return insert SQL
   */
  public static String insertEntityDeletion(@Param("deletion") EntityDeletionPO deletion) {
    return "INSERT INTO "
        + TABLE_NAME
        + " (deletion_id, entity_type, entity_id, metalake_id, catalog_id, parent_id,"
        + " entity_name, deleted_at, expires_at, deleted_by, entity_version,"
        + " affected_row_count, state,"
        + " revision, restored_at, restore_etag, purged_at)"
        + " VALUES (#{deletion.deletionId}, #{deletion.entityType}, #{deletion.entityId},"
        + " #{deletion.metalakeId}, #{deletion.catalogId}, #{deletion.parentId},"
        + " #{deletion.entityName}, #{deletion.deletedAt}, #{deletion.expiresAt},"
        + " #{deletion.deletedBy}, #{deletion.entityVersion}, #{deletion.affectedRowCount},"
        + " #{deletion.state},"
        + " #{deletion.revision}, #{deletion.restoredAt}, #{deletion.restoreEtag},"
        + " #{deletion.purgedAt})";
  }

  /**
   * Builds an exact deletion-generation lookup.
   *
   * @param deletionId deletion-generation identifier
   * @return select SQL
   */
  public static String selectEntityDeletion(@Param("deletionId") String deletionId) {
    return "SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE deletion_id = #{deletionId}";
  }

  /**
   * Builds a newest-generation lookup under one immutable parent and name.
   *
   * @param entityType entity type
   * @param parentId immutable immediate-parent identifier, or {@code null} for a root entity
   * @param entityName exact entity name
   * @return select SQL
   */
  public static String selectLatestEntityDeletion(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") String entityName) {
    return "SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE entity_type = #{entityType} AND "
        + parentPredicate(parentId)
        + " AND entity_name = #{entityName}"
        + " ORDER BY deleted_at DESC, deletion_id DESC LIMIT 1";
  }

  /**
   * Builds a locking newest-generation lookup that observes the current committed row.
   *
   * <p>This current read is required after activating a root entity: under MySQL repeatable-read, a
   * second plain select could otherwise reuse the transaction's earlier snapshot and miss a newer
   * same-name deletion that committed while restoration waited on the live-name key.
   *
   * @param entityType entity type
   * @param parentId immutable immediate-parent identifier, or {@code null} for a root entity
   * @param entityName exact entity name
   * @return locking select SQL
   */
  public static String selectLatestEntityDeletionForUpdate(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") String entityName) {
    return selectLatestEntityDeletion(entityType, parentId, entityName) + " FOR UPDATE";
  }

  /**
   * Builds a filtered deletion-generation list query.
   *
   * @param entityType entity type
   * @param parentId immutable immediate-parent identifier, or {@code null} for root entities
   * @param entityName optional exact entity name
   * @param entityId optional exact entity identifier
   * @param state optional deletion state
   * @return select SQL
   */
  public static String listEntityDeletions(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") @Nullable String entityName,
      @Param("entityId") @Nullable Long entityId,
      @Param("state") @Nullable DeletionState state) {
    return "<script>SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE entity_type = #{entityType} AND "
        + parentPredicate(parentId)
        + "<if test='entityName != null'> AND entity_name = #{entityName}</if>"
        + "<if test='entityId != null'> AND entity_id = #{entityId}</if>"
        + "<if test='state != null'> AND state = #{state}</if>"
        + " ORDER BY deleted_at DESC, deletion_id DESC</script>";
  }

  /**
   * Builds a bounded query for active deletion generations older than the current retention cutoff.
   *
   * <p>The effective POC retention remains the current global configuration, so this deliberately
   * compares {@code deleted_at} with the caller's cutoff instead of the creation-time {@code
   * expires_at} snapshot.
   *
   * @param entityType entity type
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum records to select
   * @return select SQL
   */
  public static String listExpiredEntityDeletions(
      @Param("entityType") String entityType,
      @Param("legacyTimeline") long legacyTimeline,
      @Param("limit") int limit) {
    return "SELECT "
        + SELECT_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE entity_type = #{entityType} AND state = 'DELETED'"
        + " AND deleted_at > 0 AND deleted_at < #{legacyTimeline}"
        + " ORDER BY deleted_at, deletion_id LIMIT #{limit}";
  }

  /**
   * Builds a bounded query for terminal deletion receipts whose post-completion retention expired.
   *
   * @param entityType entity type
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum records to select
   * @return select SQL
   */
  public static String listTerminalDeletionIds(
      @Param("entityType") String entityType,
      @Param("legacyTimeline") long legacyTimeline,
      @Param("limit") int limit) {
    return "SELECT deletion_id FROM "
        + TABLE_NAME
        + " WHERE entity_type = #{entityType} AND ("
        + "(state = 'RESTORED' AND restored_at IS NOT NULL"
        + " AND restored_at < #{legacyTimeline}) OR "
        + "(state = 'PURGED' AND purged_at IS NOT NULL"
        + " AND purged_at < #{legacyTimeline}))"
        + " ORDER BY deletion_id LIMIT #{limit}";
  }

  /**
   * Builds a compare-and-set transition for a deletion generation.
   *
   * @param deletionId deletion-generation identifier
   * @param expectedState state observed by the caller
   * @param expectedRevision revision observed by the caller
   * @param newState requested state
   * @param completedAt completion timestamp for restored or purged states
   * @param restoreEtag exact precondition accepted by a successful restore
   * @return update SQL
   */
  public static String compareAndSetState(
      @Param("deletionId") String deletionId,
      @Param("expectedState") DeletionState expectedState,
      @Param("expectedRevision") long expectedRevision,
      @Param("newState") DeletionState newState,
      @Param("completedAt") @Nullable Long completedAt,
      @Param("restoreEtag") @Nullable String restoreEtag) {
    return "<script>UPDATE "
        + TABLE_NAME
        + " SET state = #{newState}, revision = revision + 1"
        + "<if test=\"newState.name() == 'RESTORED'\">, restored_at = #{completedAt},"
        + " restore_etag = #{restoreEtag}</if>"
        + "<if test=\"newState.name() == 'PURGED'\">, purged_at = #{completedAt}</if>"
        + " WHERE deletion_id = #{deletionId} AND state = #{expectedState}"
        + " AND revision = #{expectedRevision}</script>";
  }

  /**
   * Builds an exact batch deletion for terminal deletion receipts.
   *
   * @param deletionIds exact deletion-generation identifiers
   * @return delete SQL
   */
  public static String deleteEntityDeletions(@Param("deletionIds") List<String> deletionIds) {
    return "<script>DELETE FROM "
        + TABLE_NAME
        + " WHERE deletion_id IN ("
        + "<foreach collection='deletionIds' item='deletionId' separator=','>"
        + "#{deletionId}"
        + "</foreach>)</script>";
  }

  private static String parentPredicate(@Nullable Long parentId) {
    return parentId == null ? "parent_id IS NULL" : "parent_id = #{parentId}";
  }
}
