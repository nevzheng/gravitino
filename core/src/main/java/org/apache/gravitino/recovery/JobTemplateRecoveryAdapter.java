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
package org.apache.gravitino.recovery;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.meta.JobTemplateEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.JobTemplatePO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.JobTemplateMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational job-template metadata to the shared recoverable-deletion protocol. */
final class JobTemplateRecoveryAdapter implements RecoverableEntityAdapter<JobTemplateEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(JobTemplateRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  JobTemplateRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.JOB_TEMPLATE;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.JOB_TEMPLATE;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return JobTemplateMetaService.getInstance()
        .listDeletedJobTemplatesByNamespace(namespace)
        .stream()
        .map(JobTemplateRecoveryAdapter::deletedSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveLiveParent(Namespace namespace) {
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return new RecoveryMetadata.ParentIdentity(metalakeId, null, metalakeId);
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(
      Namespace namespace, @Nullable Long parentId) {
    return JobTemplateMetaService.getInstance()
        .listLiveJobTemplatePOsByNamespace(namespace)
        .stream()
        .map(JobTemplateRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return JobTemplateMetaService.getInstance().listLiveJobTemplatesByIds(ids).stream()
        .map(JobTemplateRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(JobTemplateEntity entity) {
    return entity.id();
  }

  @Override
  public JobTemplateEntity loadLive(NameIdentifier identifier) {
    return JobTemplateMetaService.getInstance().getJobTemplateByIdentifier(identifier);
  }

  @Override
  public JobTemplateEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return JobTemplateMetaService.getInstance()
        .restoreJobTemplate(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // A template generation can restore child job runs whose cached list entries are not
      // addressable through the template identifier.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Job template {} was restored, but its local entity cache could not be cleared",
          identifier,
          e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(JobTemplatePO template) {
    return new RecoveryMetadata.DeletedSnapshot(
        template.jobTemplateId(),
        template.jobTemplateName(),
        new RecoveryMetadata.ParentIdentity(template.metalakeId(), null, template.metalakeId()),
        template.deletedAt(),
        template.currentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(JobTemplatePO template) {
    return new RecoveryMetadata.LiveIdentity(
        template.jobTemplateId(), template.metalakeId(), template.jobTemplateName());
  }
}
