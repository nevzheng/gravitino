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
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.PolicyPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.PolicyMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational policy metadata to the shared recoverable-deletion protocol. */
final class PolicyRecoveryAdapter implements RecoverableEntityAdapter<PolicyEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  PolicyRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.POLICY;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.POLICY;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return PolicyMetaService.getInstance().listDeletedPoliciesByNamespace(namespace).stream()
        .map(PolicyRecoveryAdapter::deletedSnapshot)
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
    return PolicyMetaService.getInstance().listLivePolicyPOsByNamespace(namespace).stream()
        .map(PolicyRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return PolicyMetaService.getInstance().listLivePoliciesByIds(ids).stream()
        .map(PolicyRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(PolicyEntity entity) {
    return entity.id();
  }

  @Override
  public PolicyEntity loadLive(NameIdentifier identifier) {
    return PolicyMetaService.getInstance().getPolicyByIdentifier(identifier);
  }

  @Override
  public PolicyEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return PolicyMetaService.getInstance()
        .restorePolicy(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // Policy associations are intentionally left live while the policy base row is deleted.
      // Clear the cache so both policy and relationship views become visible together.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Policy {} was restored, but its local entity cache could not be cleared", identifier, e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(PolicyPO policy) {
    return new RecoveryMetadata.DeletedSnapshot(
        policy.getPolicyId(),
        policy.getPolicyName(),
        new RecoveryMetadata.ParentIdentity(policy.getMetalakeId(), null, policy.getMetalakeId()),
        policy.getDeletedAt(),
        policy.getCurrentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(PolicyPO policy) {
    return new RecoveryMetadata.LiveIdentity(
        policy.getPolicyId(), policy.getMetalakeId(), policy.getPolicyName());
  }
}
