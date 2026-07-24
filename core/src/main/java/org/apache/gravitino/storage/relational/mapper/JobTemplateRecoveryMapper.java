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
import org.apache.gravitino.storage.relational.po.JobRunRecoveryPO;
import org.apache.gravitino.storage.relational.po.JobTemplatePO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped job-template deletion and recovery. */
public interface JobTemplateRecoveryMapper {

  /** Result map for job-template root rows used by recovery. */
  @Results(
      id = "jobTemplateRecoveryResultMap",
      value = {
        @Result(property = "jobTemplateId", column = "job_template_id", id = true),
        @Result(property = "jobTemplateName", column = "job_template_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "jobTemplateComment", column = "job_template_comment"),
        @Result(property = "jobTemplateContent", column = "job_template_content"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  JobTemplatePO jobTemplateRecoveryResultMap();

  /** Result map for job-run rows captured by template deletion generations. */
  @Results(
      id = "jobRunRecoveryResultMap",
      value = {
        @Result(property = "jobRunId", column = "job_run_id", id = true),
        @Result(property = "jobTemplateId", column = "job_template_id"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "jobExecutionId", column = "job_execution_id"),
        @Result(property = "jobRunStatus", column = "job_run_status"),
        @Result(property = "jobFinishedAt", column = "job_finished_at"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  JobRunRecoveryPO jobRunRecoveryResultMap();

  /** Locks the live metalake that serializes job-template and job-run changes. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks one live job template under the already-locked metalake. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(type = JobTemplateRecoverySQLProvider.class, method = "lockLiveJobTemplate")
  JobTemplatePO lockLiveJobTemplate(
      @Param("metalakeId") long metalakeId, @Param("jobTemplateName") String jobTemplateName);

  /** Locks all live job runs currently owned by a template. */
  @ResultMap("jobRunRecoveryResultMap")
  @SelectProvider(type = JobTemplateRecoverySQLProvider.class, method = "listLiveJobRunsForUpdate")
  List<JobRunRecoveryPO> listLiveJobRunsForUpdate(@Param("jobTemplateId") long jobTemplateId);

  /** Locks recorded template tombstones whose immutable IDs must not be overwritten. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "selectRecordedDeletedJobTemplatesForUpdate")
  List<JobTemplatePO> selectRecordedDeletedJobTemplatesForUpdate(
      @Param("jobTemplateIds") List<Long> jobTemplateIds);

  /** Locks recorded job-run tombstones whose immutable IDs must not be overwritten. */
  @ResultMap("jobRunRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "selectRecordedDeletedJobRunsForUpdate")
  List<JobRunRecoveryPO> selectRecordedDeletedJobRunsForUpdate(
      @Param("jobRunIds") List<Long> jobRunIds);

  /** Lists live job-template identities below one metalake. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(type = JobTemplateRecoverySQLProvider.class, method = "listLiveJobTemplates")
  List<JobTemplatePO> listLiveJobTemplates(@Param("metalakeId") long metalakeId);

  /** Lists globally live job-template rows matching candidate immutable IDs. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(type = JobTemplateRecoverySQLProvider.class, method = "listLiveJobTemplatesByIds")
  List<JobTemplatePO> listLiveJobTemplatesByIds(@Param("jobTemplateIds") List<Long> jobTemplateIds);

  /** Lists independently recorded job-template root tombstones below one live metalake. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "listDeletedRootJobTemplates")
  List<JobTemplatePO> listDeletedRootJobTemplates(@Param("metalakeId") long metalakeId);

  /** Locks one exact job-template root generation. */
  @ResultMap("jobTemplateRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "selectJobTemplateGenerationForUpdate")
  JobTemplatePO selectJobTemplateGenerationForUpdate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks every job run captured by one exact template deletion generation. */
  @ResultMap("jobRunRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "listJobRunGenerationForUpdate")
  List<JobRunRecoveryPO> listJobRunGenerationForUpdate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks live job runs whose execution IDs conflict with one exact deleted generation. */
  @ResultMap("jobRunRecoveryResultMap")
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "listLiveExecutionIdConflictsForUpdate")
  List<JobRunRecoveryPO> listLiveExecutionIdConflictsForUpdate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Returns the newest tombstone timestamp for the immutable template or parent/name. */
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "selectNewestJobTemplateDeletedAt")
  Long selectNewestJobTemplateDeletedAt(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("metalakeId") long metalakeId,
      @Param("jobTemplateName") String jobTemplateName);

  /** Stamps every live terminal job run before stamping the template root. */
  @UpdateProvider(type = JobTemplateRecoverySQLProvider.class, method = "softDeleteJobRuns")
  int softDeleteJobRuns(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Stamps the exact live template root after all job runs. */
  @UpdateProvider(type = JobTemplateRecoverySQLProvider.class, method = "softDeleteJobTemplate")
  int softDeleteJobTemplate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("metalakeId") long metalakeId,
      @Param("jobTemplateName") String jobTemplateName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the template root in one exact deletion generation. */
  @SelectProvider(
      type = JobTemplateRecoverySQLProvider.class,
      method = "countJobTemplateGeneration")
  int countJobTemplateGeneration(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts all job runs in one exact template deletion generation. */
  @SelectProvider(type = JobTemplateRecoverySQLProvider.class, method = "countJobRunGeneration")
  int countJobRunGeneration(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores job runs from one exact generation before restoring the template root. */
  @UpdateProvider(type = JobTemplateRecoverySQLProvider.class, method = "restoreJobRuns")
  int restoreJobRuns(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact template root after all job runs. */
  @UpdateProvider(type = JobTemplateRecoverySQLProvider.class, method = "restoreJobTemplate")
  int restoreJobTemplate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("metalakeId") long metalakeId,
      @Param("jobTemplateName") String jobTemplateName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes job runs from one exact generation before its template root. */
  @DeleteProvider(type = JobTemplateRecoverySQLProvider.class, method = "hardDeleteJobRuns")
  int hardDeleteJobRuns(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the exact template root after all job runs. */
  @DeleteProvider(type = JobTemplateRecoverySQLProvider.class, method = "hardDeleteJobTemplate")
  int hardDeleteJobTemplate(
      @Param("jobTemplateId") long jobTemplateId,
      @Param("metalakeId") long metalakeId,
      @Param("jobTemplateName") String jobTemplateName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
