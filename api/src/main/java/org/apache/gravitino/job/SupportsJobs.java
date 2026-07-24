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
package org.apache.gravitino.job;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.exceptions.InUseException;
import org.apache.gravitino.exceptions.JobTemplateAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchJobException;
import org.apache.gravitino.exceptions.NoSuchJobTemplateException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;

/**
 * Interface for job management operations. This interface will be mixed with GravitinoClient to
 * provide the ability to manage job templates and jobs within the Gravitino system.
 */
public interface SupportsJobs {

  /**
   * Lists all the registered job templates in Gravitino.
   *
   * @return a list of job templates
   */
  List<JobTemplate> listJobTemplates();

  /**
   * Lists retained job-template deletion generations in this metalake.
   *
   * <p>The optional filters select an exact job-template name, immutable job-template ID, or both.
   * Returned generations describe Gravitino metadata only.
   *
   * @param name An exact job-template-name filter, or {@code null} for every name.
   * @param id An exact immutable job-template-ID filter, or {@code null} for every ID.
   * @return The retained job-template deletion generations matching the filters.
   * @throws IllegalArgumentException If a supplied filter is invalid.
   * @throws UnsupportedOperationException If recoverable job-template deletion is not supported.
   */
  default DeletedEntity[] listDeletedJobTemplates(@Nullable String name, @Nullable String id) {
    throw new UnsupportedOperationException("listDeletedJobTemplates not supported.");
  }

  /**
   * Register a job template with the specified job template to Gravitino. The registered job
   * template will be maintained in Gravitino, allowing it to be executed later.
   *
   * @param jobTemplate the template for the job
   * @throws JobTemplateAlreadyExistsException if a job template with the same name already exists
   */
  void registerJobTemplate(JobTemplate jobTemplate) throws JobTemplateAlreadyExistsException;

  /**
   * Retrieves a job template by its name.
   *
   * @param jobTemplateName the name of the job template to retrieve
   * @return the job template associated with the specified name
   * @throws NoSuchJobTemplateException if no job template with the specified name exists
   */
  JobTemplate getJobTemplate(String jobTemplateName) throws NoSuchJobTemplateException;

  /**
   * Loads one exact retained deletion generation for a job template.
   *
   * <p>The job-template name and immutable ID must identify the same deleted template. The returned
   * generation supplies the strong optimistic precondition required by {@link
   * #restoreJobTemplate(String, DeletedEntity)}.
   *
   * @param jobTemplateName The job-template name.
   * @param id The immutable job-template ID.
   * @return The exact retained job-template deletion generation.
   * @throws IllegalArgumentException If the name or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws UnsupportedOperationException If recoverable job-template deletion is not supported.
   */
  default DeletedEntity loadDeletedJobTemplate(String jobTemplateName, String id)
      throws TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedJobTemplate not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino job-template metadata.
   *
   * <p>This operation restores only the template's relational metadata and captured terminal-run
   * history. It does not contact an executor; submit, poll, or cancel a job; recreate staging
   * files; or restore job metrics. The generation must come from an exact deleted-job-template read
   * and must match the requested name and resource type. Replaying a previously accepted generation
   * is idempotent while the server can still prove that exact restore.
   *
   * <p>After {@link TombstoneChangedException}, callers must reread the same job-template path and
   * immutable ID before deciding whether to retry; clients must never substitute a different ID or
   * generation. An unknown transport outcome may replay the same generation. A recovery conflict or
   * expired generation is not retryable as-is.
   *
   * @param jobTemplateName The job-template name.
   * @param generation The exact retained deletion generation and optimistic precondition.
   * @return The restored job template, preserving its concrete template subtype.
   * @throws IllegalArgumentException If the name, generation type, generation name, ID, or ETag is
   *     invalid or inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If recoverable job-template deletion is not supported.
   */
  default JobTemplate restoreJobTemplate(String jobTemplateName, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreJobTemplate not supported.");
  }

  /**
   * Deletes a job template by its name. This will remove the job template from Gravitino, and it
   * will no longer be available for execution. Only when all the jobs associated with this job
   * template are completed, failed, or cancelled, the job template can be deleted successfully,
   * otherwise it will throw an exception.
   *
   * <p>The deletion of a job template will also delete all the jobs associated with this template.
   *
   * @param jobTemplateName the name of the job template to delete
   * @return true if the job template was successfully deleted, false if the job template does not
   *     exist
   * @throws InUseException if there are still queued or started jobs associated with the job
   *     template
   */
  boolean deleteJobTemplate(String jobTemplateName) throws InUseException;

  /**
   * Alters a job template by applying the specified changes. This allows for modifying the
   * properties of an existing job template, such as its name, description, or parameters.
   *
   * @param jobTemplateName the name of the job template to alter
   * @param changes the changes to apply to the job template
   * @return the updated job template after applying the changes
   * @throws NoSuchJobTemplateException if no job template with the specified name exists
   * @throws IllegalArgumentException if any of the changes cannot be applied to the job template
   */
  default JobTemplate alterJobTemplate(String jobTemplateName, JobTemplateChange... changes)
      throws NoSuchJobTemplateException, IllegalArgumentException {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * Lists all the jobs by the specified job template name. This will return a list of job handles
   * associated with the specified job template. Each job handle represents a specific job.
   *
   * @param jobTemplateName the name of the job template to list jobs for
   * @return a list of job handles associated with the specified job template
   * @throws NoSuchJobTemplateException if no job template with the specified name exists
   */
  List<JobHandle> listJobs(String jobTemplateName) throws NoSuchJobTemplateException;

  /**
   * Lists all the jobs in Gravitino. This will return a list of all job handles, regardless of the
   * job template they are associated with.
   *
   * @return a list of all job handles in Gravitino
   */
  List<JobHandle> listJobs();

  /**
   * Run a job with the template name and configuration. The jobConf is a map of key-value contains
   * the variables that will be used to replace the templated parameters in the job template.
   *
   * @param jobTemplateName the name of the job template to run
   * @param jobConf the configuration for the job
   * @return a handle to the run job
   * @throws NoSuchJobTemplateException if no job template with the specified name exists
   */
  JobHandle runJob(String jobTemplateName, Map<String, String> jobConf)
      throws NoSuchJobTemplateException;

  /**
   * Retrieves a job by its ID.
   *
   * @param jobId the ID of the job to retrieve
   * @return a handle to the job
   * @throws NoSuchJobException if the job with the specified ID does not exist
   */
  JobHandle getJob(String jobId) throws NoSuchJobException;

  /**
   * Cancel a job by its ID. This operation will attempt to cancel the job if it is still running.
   * This method will return immediately, user could use the job handle to check the status of the
   * job after invoking this method.
   *
   * @param jobId the ID of the job to cancel
   * @return a handle to the cancelled job
   * @throws NoSuchJobException if the job with the specified ID does not exist
   */
  JobHandle cancelJob(String jobId) throws NoSuchJobException;
}
