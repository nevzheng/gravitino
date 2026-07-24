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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestJobTemplateRecoverySQLProvider {

  private final JobTemplateRecoverySQLProvider provider = new JobTemplateRecoverySQLProvider();

  @Test
  void testAggregateMutationsKeepTemplateRootLastAndUseExactToken() {
    List<List<String>> operations =
        List.of(
            List.of(provider.softDeleteJobRuns(), provider.softDeleteJobTemplate()),
            List.of(provider.restoreJobRuns(), provider.restoreJobTemplate()),
            List.of(provider.hardDeleteJobRuns(), provider.hardDeleteJobTemplate()));
    for (List<String> statements : operations) {
      assertTrue(statements.get(0).contains("job_run_meta"), statements.get(0));
      assertTrue(statements.get(1).contains("job_template_meta"), statements.get(1));
      for (String sql : statements) {
        assertTrue(sql.contains("#{deletedAt}"), sql);
        assertTrue(sql.contains("#{deletionId}"), sql);
      }
    }
  }

  @Test
  void testDeleteCapturesOnlyLiveJobsAndRestoreChecksExecutionIdConflicts() {
    String jobs = provider.softDeleteJobRuns();
    assertTrue(jobs.contains("job_template_id = #{jobTemplateId}"), jobs);
    assertTrue(jobs.contains("deleted_at = 0 AND deletion_id IS NULL"), jobs);

    String conflicts = provider.listLiveExecutionIdConflictsForUpdate();
    assertTrue(
        conflicts.contains("candidate.job_execution_id = deleted.job_execution_id"), conflicts);
    assertTrue(conflicts.contains("candidate.deleted_at = 0"), conflicts);
    assertTrue(conflicts.contains("FOR UPDATE"), conflicts);
  }

  @Test
  void testRootListingAndUpsertFencesDistinguishRecordedFromLegacyTombstones() {
    String roots = provider.listDeletedRootJobTemplates();
    assertTrue(roots.contains("LEFT JOIN entity_deletion"), roots);
    assertTrue(roots.contains("ed.entity_type = 'JOB_TEMPLATE'"), roots);
    assertTrue(roots.contains("jtm.deletion_id IS NULL OR"), roots);

    assertTrue(
        provider.selectRecordedDeletedJobTemplatesForUpdate().contains("deletion_id IS NOT NULL"));
    assertTrue(
        provider.selectRecordedDeletedJobRunsForUpdate().contains("deletion_id IS NOT NULL"));
  }
}
