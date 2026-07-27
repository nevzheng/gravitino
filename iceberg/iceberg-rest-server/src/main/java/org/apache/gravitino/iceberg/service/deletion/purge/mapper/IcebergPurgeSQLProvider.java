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

import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgePlanPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.ibatis.annotations.Param;

/** Portable SQL for the deletion-action based Iceberg purge subsystem. */
public class IcebergPurgeSQLProvider {

  private static final String ACTION_COLUMNS =
      "deletion_id AS deletionId, entity_type AS entityType, entity_id AS entityId,"
          + " entity_version AS entityVersion, metalake_id AS metalakeId,"
          + " catalog_id AS catalogId, parent_id AS parentId,"
          + " namespace_snapshot AS namespaceSnapshot,"
          + " entity_name_snapshot AS entityNameSnapshot, name_lookup_key AS nameLookupKey,"
          + " active_name_key AS activeNameKey, state, revision, deleted_at AS deletedAt,"
          + " retention_expires_at AS retentionExpiresAt, deleted_by AS deletedBy,"
          + " purge_requested AS purgeRequested, purge_job_type AS purgeJobType,"
          + " purge_job_id AS purgeJobId, cleanup_status AS cleanupStatus,"
          + " cleanup_attempt_count AS cleanupAttemptCount,"
          + " cleanup_last_error AS cleanupLastError,"
          + " accepted_restore_etag AS acceptedRestoreEtag, request_id AS requestId,"
          + " correlation_id AS correlationId, restored_at AS restoredAt,"
          + " purged_at AS purgedAt, updated_at AS updatedAt";

  private static final String JOB_COLUMNS =
      "purge_job_id AS purgeJobId, purge_job_type AS purgeJobType, state, owner,"
          + " lease_epoch AS leaseEpoch, lease_expires_at AS leaseExpiresAt,"
          + " heartbeat_at AS heartbeatAt, attempt_count AS attemptCount,"
          + " item_count AS itemCount, pending_count AS pendingCount,"
          + " running_count AS runningCount, succeeded_count AS succeededCount,"
          + " failed_count AS failedCount, retrying_count AS retryingCount,"
          + " last_error AS lastError, created_by AS createdBy, request_id AS requestId,"
          + " correlation_id AS correlationId, created_at AS createdAt,"
          + " started_at AS startedAt, completed_at AS completedAt, updated_at AS updatedAt";

  private static final String PLAN_COLUMNS =
      "deletion_id AS deletionId, purge_job_id AS purgeJobId,"
          + " context_digest AS contextDigest, state, target_count AS targetCount,"
          + " root_target_id AS rootTargetId, created_at AS createdAt,"
          + " completed_at AS completedAt, updated_at AS updatedAt";

  private static final String TARGET_COLUMNS =
      "deletion_id AS deletionId, target_id AS targetId, purge_job_id AS purgeJobId,"
          + " target_type AS targetType, target_uri AS targetUri,"
          + " object_version AS objectVersion, delete_order AS deleteOrder, state,"
          + " lease_epoch AS leaseEpoch, attempt_count AS attemptCount,"
          + " last_error AS lastError, created_at AS createdAt, updated_at AS updatedAt";

  /** Returns a bounded candidate scan; the following update is the authoritative CAS. */
  public static String selectEligibleActions(
      @Param("jobType") String jobType, @Param("now") long now, @Param("limit") int limit) {
    return "SELECT "
        + ACTION_COLUMNS
        + " FROM entity_deletion d WHERE d.entity_type = 'TABLE'"
        + " AND d.purge_job_type = #{jobType} AND d.state = 'DELETED'"
        + " AND d.purge_job_id IS NULL"
        + " AND (d.retention_expires_at IS NULL OR d.retention_expires_at <= #{now})"
        + " AND EXISTS (SELECT 1 FROM iceberg_deletion_context c"
        + " WHERE c.deletion_id = d.deletion_id)"
        + " ORDER BY CASE WHEN d.retention_expires_at IS NULL THEN d.deleted_at"
        + " ELSE d.retention_expires_at END, d.deletion_id LIMIT #{limit}";
  }

  /** Claims one eligible deletion action directly from DELETED to PURGING. */
  public static String claimAction(
      @Param("deletionId") String deletionId,
      @Param("expectedRevision") long expectedRevision,
      @Param("jobType") String jobType,
      @Param("purgeJobId") String purgeJobId,
      @Param("now") long now) {
    return "UPDATE entity_deletion SET state = 'PURGING', revision = revision + 1,"
        + " purge_job_id = #{purgeJobId}, cleanup_status = 'PENDING',"
        + " cleanup_last_error = NULL, updated_at = #{now}"
        + " WHERE deletion_id = #{deletionId} AND revision = #{expectedRevision}"
        + " AND entity_type = 'TABLE' AND purge_job_type = #{jobType}"
        + " AND state = 'DELETED' AND purge_job_id IS NULL"
        + " AND (retention_expires_at IS NULL OR retention_expires_at <= #{now})"
        + " AND EXISTS (SELECT 1 FROM iceberg_deletion_context c"
        + " WHERE c.deletion_id = entity_deletion.deletion_id)";
  }

  /** Selects all table-level actions associated with one bounded batch. */
  public static String selectActionsByJob(@Param("purgeJobId") String purgeJobId) {
    return "SELECT "
        + ACTION_COLUMNS
        + " FROM entity_deletion WHERE purge_job_id = #{purgeJobId}"
        + " ORDER BY deletion_id";
  }

  /** Counts lifecycle audit events associated with one purge batch. */
  public static String countAuditsByJob(@Param("purgeJobId") String purgeJobId) {
    return "SELECT COUNT(*) FROM entity_deletion_audit WHERE purge_job_id = #{purgeJobId}";
  }

  /** Inserts one durable batch header. */
  public static String insertJob(@Param("job") IcebergPurgeJobPO job) {
    return "INSERT INTO iceberg_purge_job (purge_job_id, purge_job_type, state, owner,"
        + " lease_epoch, lease_expires_at, heartbeat_at, attempt_count, item_count,"
        + " pending_count, running_count, succeeded_count, failed_count, retrying_count,"
        + " last_error, created_by, request_id, correlation_id, created_at, started_at,"
        + " completed_at, updated_at) VALUES (#{job.purgeJobId}, #{job.purgeJobType},"
        + " #{job.state}, #{job.owner}, #{job.leaseEpoch}, #{job.leaseExpiresAt},"
        + " #{job.heartbeatAt}, #{job.attemptCount}, #{job.itemCount}, #{job.pendingCount},"
        + " #{job.runningCount}, #{job.succeededCount}, #{job.failedCount},"
        + " #{job.retryingCount}, #{job.lastError}, #{job.createdBy}, #{job.requestId},"
        + " #{job.correlationId}, #{job.createdAt}, #{job.startedAt}, #{job.completedAt},"
        + " #{job.updatedAt})";
  }

  /** Selects one exact batch header. */
  public static String selectJob(@Param("purgeJobId") String purgeJobId) {
    return "SELECT " + JOB_COLUMNS + " FROM iceberg_purge_job WHERE purge_job_id = #{purgeJobId}";
  }

  /** Counts all durable purge batch headers. */
  public static String countJobs() {
    return "SELECT COUNT(*) FROM iceberg_purge_job";
  }

  /** Selects a bounded window of new or stale batch headers. */
  public static String selectClaimableJobs(@Param("now") long now, @Param("limit") int limit) {
    return "SELECT "
        + JOB_COLUMNS
        + " FROM iceberg_purge_job WHERE state = 'PENDING'"
        + " OR (state = 'RUNNING' AND lease_expires_at <= #{now})"
        + " ORDER BY updated_at, purge_job_id LIMIT #{limit}";
  }

  /** Claims or reclaims one batch and increments its fencing epoch. */
  public static String claimJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("now") long now,
      @Param("leaseExpiresAt") long leaseExpiresAt) {
    return "UPDATE iceberg_purge_job SET state = 'RUNNING', owner = #{owner},"
        + " lease_epoch = lease_epoch + 1, lease_expires_at = #{leaseExpiresAt},"
        + " heartbeat_at = #{now}, attempt_count = attempt_count + 1,"
        + " started_at = CASE WHEN started_at IS NULL THEN #{now} ELSE started_at END,"
        + " updated_at = #{now} WHERE purge_job_id = #{purgeJobId}"
        + " AND (state = 'PENDING' OR (state = 'RUNNING' AND lease_expires_at <= #{now}))";
  }

  /** Renews an unexpired lease held by the exact owner and epoch. */
  public static String heartbeatJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now,
      @Param("leaseExpiresAt") long leaseExpiresAt) {
    return "UPDATE iceberg_purge_job SET heartbeat_at = #{now},"
        + " lease_expires_at = #{leaseExpiresAt}, updated_at = #{now}"
        + " WHERE purge_job_id = #{purgeJobId} AND state = 'RUNNING'"
        + " AND owner = #{owner} AND lease_epoch = #{leaseEpoch}"
        + " AND lease_expires_at > #{now}";
  }

  /** Inserts the planning marker before any physical target may be deleted. */
  public static String insertPlan(@Param("plan") IcebergPurgePlanPO plan) {
    return "INSERT INTO iceberg_purge_plan (deletion_id, purge_job_id, context_digest, state,"
        + " target_count, root_target_id, created_at, completed_at, updated_at) VALUES ("
        + " #{plan.deletionId}, #{plan.purgeJobId}, #{plan.contextDigest}, #{plan.state},"
        + " #{plan.targetCount}, #{plan.rootTargetId}, #{plan.createdAt},"
        + " #{plan.completedAt}, #{plan.updatedAt})";
  }

  /** Selects one planning marker. */
  public static String selectPlan(@Param("deletionId") String deletionId) {
    return "SELECT " + PLAN_COLUMNS + " FROM iceberg_purge_plan WHERE deletion_id = #{deletionId}";
  }

  /** Inserts one immutable physical target snapshot. */
  public static String insertTarget(@Param("target") IcebergPurgeTargetPO target) {
    return "INSERT INTO iceberg_purge_target (deletion_id, target_id, purge_job_id,"
        + " target_type, target_uri, object_version, delete_order, state, lease_epoch,"
        + " attempt_count, last_error, created_at, updated_at) VALUES ("
        + " #{target.deletionId}, #{target.targetId}, #{target.purgeJobId},"
        + " #{target.targetType}, #{target.targetUri}, #{target.objectVersion},"
        + " #{target.deleteOrder}, #{target.state}, #{target.leaseEpoch},"
        + " #{target.attemptCount}, #{target.lastError}, #{target.createdAt},"
        + " #{target.updatedAt})";
  }

  /** Selects one exact physical target. */
  public static String selectTarget(
      @Param("deletionId") String deletionId, @Param("targetId") String targetId) {
    return "SELECT "
        + TARGET_COLUMNS
        + " FROM iceberg_purge_target WHERE deletion_id = #{deletionId}"
        + " AND target_id = #{targetId}";
  }

  /** Counts the snapshotted targets for one deletion action. */
  public static String countTargets(@Param("deletionId") String deletionId) {
    return "SELECT COUNT(*) FROM iceberg_purge_target WHERE deletion_id = #{deletionId}";
  }

  /** Returns the maximum delete order, used to prove the root is ordered last. */
  public static String maxDeleteOrder(@Param("deletionId") String deletionId) {
    return "SELECT MAX(delete_order) FROM iceberg_purge_target WHERE deletion_id = #{deletionId}";
  }

  /** Selects the delete order of one exact root target. */
  public static String selectTargetOrder(
      @Param("deletionId") String deletionId, @Param("targetId") String targetId) {
    return "SELECT delete_order FROM iceberg_purge_target WHERE deletion_id = #{deletionId}"
        + " AND target_id = #{targetId} AND target_type = 'ROOT_METADATA'";
  }

  /** Makes a complete immutable target snapshot visible to deletion workers. */
  public static String markPlanReady(
      @Param("deletionId") String deletionId,
      @Param("purgeJobId") String purgeJobId,
      @Param("contextDigest") String contextDigest,
      @Param("targetCount") long targetCount,
      @Param("rootTargetId") String rootTargetId,
      @Param("now") long now) {
    return "UPDATE iceberg_purge_plan SET state = 'READY', target_count = #{targetCount},"
        + " root_target_id = #{rootTargetId}, completed_at = #{now}, updated_at = #{now}"
        + " WHERE deletion_id = #{deletionId} AND purge_job_id = #{purgeJobId}"
        + " AND context_digest = #{contextDigest} AND state = 'PLANNING'";
  }
}
