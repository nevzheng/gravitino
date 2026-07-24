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
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.GroupPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.GroupMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational group metadata to the shared recoverable-deletion protocol. */
final class GroupRecoveryAdapter implements RecoverableEntityAdapter<GroupEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(GroupRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  GroupRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.GROUP;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.GROUP;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return GroupMetaService.getInstance().listDeletedGroupsByNamespace(namespace).stream()
        .map(GroupRecoveryAdapter::deletedSnapshot)
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
    return GroupMetaService.getInstance().listLiveGroupPOsByNamespace(namespace).stream()
        .map(GroupRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return GroupMetaService.getInstance().listLiveGroupsByIds(ids).stream()
        .map(GroupRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(GroupEntity entity) {
    return entity.id();
  }

  @Override
  public GroupEntity loadLive(NameIdentifier identifier) {
    return GroupMetaService.getInstance().getGroupByIdentifier(identifier);
  }

  @Override
  public GroupEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return GroupMetaService.getInstance()
        .restoreGroup(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // Group restoration can revive ownership and role-assignment rows across the metalake.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Group {} was restored, but its local entity cache could not be cleared", identifier, e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(GroupPO group) {
    return new RecoveryMetadata.DeletedSnapshot(
        group.getGroupId(),
        group.getGroupName(),
        new RecoveryMetadata.ParentIdentity(group.getMetalakeId(), null, group.getMetalakeId()),
        group.getDeletedAt(),
        group.getCurrentVersion(),
        group.getExternalId());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(GroupPO group) {
    return new RecoveryMetadata.LiveIdentity(
        group.getGroupId(), group.getMetalakeId(), group.getGroupName(), group.getExternalId());
  }
}
