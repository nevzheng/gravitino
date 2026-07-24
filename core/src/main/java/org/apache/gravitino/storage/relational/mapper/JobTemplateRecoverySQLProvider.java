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

/** SQL provider for exact, generation-scoped job-template deletion and recovery. */
public class JobTemplateRecoverySQLProvider {

  private static final String TEMPLATE_COLUMNS =
      "jtm.job_template_id, jtm.job_template_name, jtm.metalake_id,"
          + " jtm.job_template_comment, jtm.job_template_content, jtm.audit_info,"
          + " jtm.current_version, jtm.last_version, jtm.deleted_at, jtm.deletion_id";

  private static final String JOB_COLUMNS =
      "jrm.job_run_id, jrm.job_template_id, jrm.metalake_id, jrm.job_execution_id,"
          + " jrm.job_run_status, jrm.job_finished_at, jrm.current_version,"
          + " jrm.last_version, jrm.deleted_at, jrm.deletion_id";

  /** Builds the locking read for one live job template. */
  public String lockLiveJobTemplate() {
    return "SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm WHERE jtm.metalake_id = #{metalakeId}"
        + " AND jtm.job_template_name = #{jobTemplateName}"
        + " AND jtm.deleted_at = 0 AND jtm.deletion_id IS NULL FOR UPDATE";
  }

  /** Builds the locking read for all live job runs currently owned by a template. */
  public String listLiveJobRunsForUpdate() {
    return "SELECT "
        + JOB_COLUMNS
        + " FROM job_run_meta jrm WHERE jrm.job_template_id = #{jobTemplateId}"
        + " AND jrm.deleted_at = 0 AND jrm.deletion_id IS NULL"
        + " ORDER BY jrm.job_run_id FOR UPDATE";
  }

  /** Builds the locking fence for recorded template tombstones by immutable ID. */
  public String selectRecordedDeletedJobTemplatesForUpdate() {
    return "<script>SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm WHERE jtm.deleted_at > 0"
        + " AND jtm.deletion_id IS NOT NULL AND jtm.job_template_id IN"
        + idList("jobTemplateIds", "jobTemplateId")
        + " ORDER BY jtm.job_template_id FOR UPDATE</script>";
  }

  /** Builds the locking fence for recorded job-run tombstones by immutable ID. */
  public String selectRecordedDeletedJobRunsForUpdate() {
    return "<script>SELECT "
        + JOB_COLUMNS
        + " FROM job_run_meta jrm WHERE jrm.deleted_at > 0"
        + " AND jrm.deletion_id IS NOT NULL AND jrm.job_run_id IN"
        + idList("jobRunIds", "jobRunId")
        + " ORDER BY jrm.job_run_id FOR UPDATE</script>";
  }

  /** Builds the read for live job templates below one metalake. */
  public String listLiveJobTemplates() {
    return "SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm WHERE jtm.metalake_id = #{metalakeId}"
        + " AND jtm.deleted_at = 0 AND jtm.deletion_id IS NULL";
  }

  /** Builds the read for globally live job templates matching immutable IDs. */
  public String listLiveJobTemplatesByIds() {
    return "<script>SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm WHERE jtm.deleted_at = 0"
        + " AND jtm.deletion_id IS NULL AND jtm.job_template_id IN"
        + idList("jobTemplateIds", "jobTemplateId")
        + "</script>";
  }

  /** Builds the read for independently deleted job-template roots below one live metalake. */
  public String listDeletedRootJobTemplates() {
    return "SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm LEFT JOIN entity_deletion ed"
        + " ON ed.entity_type = 'JOB_TEMPLATE' AND ed.entity_id = jtm.job_template_id"
        + " AND ed.deleted_at = jtm.deleted_at AND ed.deletion_id = jtm.deletion_id"
        + " WHERE jtm.metalake_id = #{metalakeId} AND jtm.deleted_at > 0 AND ("
        + "jtm.deletion_id IS NULL OR (jtm.deletion_id IS NOT NULL"
        + " AND ed.metalake_id = #{metalakeId} AND ed.parent_id = #{metalakeId}))"
        + " ORDER BY jtm.deleted_at DESC, jtm.job_template_id DESC";
  }

  /** Builds the locking read for one exact template generation. */
  public String selectJobTemplateGenerationForUpdate() {
    return "SELECT "
        + TEMPLATE_COLUMNS
        + " FROM job_template_meta jtm WHERE jtm.job_template_id = #{jobTemplateId}"
        + " AND jtm.deleted_at = #{deletedAt} AND jtm.deletion_id = #{deletionId}"
        + " FOR UPDATE";
  }

  /** Builds the locking read for all job runs in one exact template generation. */
  public String listJobRunGenerationForUpdate() {
    return "SELECT "
        + JOB_COLUMNS
        + " FROM job_run_meta jrm WHERE jrm.job_template_id = #{jobTemplateId}"
        + " AND jrm.deleted_at = #{deletedAt} AND jrm.deletion_id = #{deletionId}"
        + " ORDER BY jrm.job_run_id FOR UPDATE";
  }

  /** Builds the locking read for live execution-ID conflicts with an exact generation. */
  public String listLiveExecutionIdConflictsForUpdate() {
    return "SELECT "
        + JOB_COLUMNS.replace("jrm.", "candidate.")
        + " FROM job_run_meta candidate JOIN job_run_meta deleted"
        + " ON candidate.metalake_id = deleted.metalake_id"
        + " AND candidate.job_execution_id = deleted.job_execution_id"
        + " AND candidate.job_run_id <> deleted.job_run_id"
        + " WHERE deleted.job_template_id = #{jobTemplateId}"
        + " AND deleted.deleted_at = #{deletedAt} AND deleted.deletion_id = #{deletionId}"
        + " AND candidate.deleted_at = 0 AND candidate.deletion_id IS NULL"
        + " ORDER BY candidate.job_run_id FOR UPDATE";
  }

  /** Builds the query for the newest tombstone timestamp for a template identity or name. */
  public String selectNewestJobTemplateDeletedAt() {
    return "SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM job_template_meta"
        + " WHERE job_template_id = #{jobTemplateId}"
        + " OR (metalake_id = #{metalakeId} AND job_template_name = #{jobTemplateName})"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM job_run_meta"
        + " WHERE job_template_id = #{jobTemplateId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
        + " WHERE entity_type = 'JOB_TEMPLATE' AND (entity_id = #{jobTemplateId}"
        + " OR (parent_id = #{metalakeId} AND entity_name = #{jobTemplateName}))"
        + ") job_template_deletions";
  }

  /** Builds the update that stamps live terminal job runs before the template root. */
  public String softDeleteJobRuns() {
    return "UPDATE job_run_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + " WHERE job_template_id = #{jobTemplateId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the update that stamps the exact live template root last. */
  public String softDeleteJobTemplate() {
    return "UPDATE job_template_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + exactTemplateIdentity()
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the count for one exact template root generation. */
  public String countJobTemplateGeneration() {
    return "SELECT COUNT(*) FROM job_template_meta WHERE job_template_id = #{jobTemplateId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the count for all job runs in one exact template generation. */
  public String countJobRunGeneration() {
    return "SELECT COUNT(*) FROM job_run_meta WHERE job_template_id = #{jobTemplateId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation update that restores job runs before the template root. */
  public String restoreJobRuns() {
    return "UPDATE job_run_meta SET deleted_at = 0, deletion_id = NULL"
        + " WHERE job_template_id = #{jobTemplateId} AND deleted_at = #{deletedAt}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation update that restores the template root last. */
  public String restoreJobTemplate() {
    return "UPDATE job_template_meta SET deleted_at = 0, deletion_id = NULL"
        + exactTemplateIdentity()
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges job runs before the template root. */
  public String hardDeleteJobRuns() {
    return "DELETE FROM job_run_meta WHERE job_template_id = #{jobTemplateId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges the template root last. */
  public String hardDeleteJobTemplate() {
    return "DELETE FROM job_template_meta"
        + exactTemplateIdentity()
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  private static String exactTemplateIdentity() {
    return " WHERE job_template_id = #{jobTemplateId} AND metalake_id = #{metalakeId}"
        + " AND job_template_name = #{jobTemplateName}"
        + " AND current_version = #{currentVersion}";
  }

  private static String idList(String collection, String item) {
    return " <foreach collection='"
        + collection
        + "' item='"
        + item
        + "' open='(' separator=',' close=')'>#{"
        + item
        + "}</foreach>";
  }
}
