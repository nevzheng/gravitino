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
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.RoleMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational role metadata to the shared recoverable-deletion protocol. */
final class RoleRecoveryAdapter implements RecoverableEntityAdapter<RoleEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(RoleRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  RoleRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.ROLE;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.ROLE;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return RoleMetaService.getInstance().listDeletedRolesByNamespace(namespace).stream()
        .map(RoleRecoveryAdapter::deletedSnapshot)
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
    return RoleMetaService.getInstance().listLiveRolePOsByNamespace(namespace).stream()
        .map(RoleRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return RoleMetaService.getInstance().listLiveRolesByIds(ids).stream()
        .map(RoleRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(RoleEntity entity) {
    return entity.id();
  }

  @Override
  public RoleEntity loadLive(NameIdentifier identifier) {
    return RoleMetaService.getInstance().getRoleByIdentifier(identifier);
  }

  @Override
  public RoleEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return RoleMetaService.getInstance()
        .restoreRole(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // Role restoration can revive memberships, grants, and ownership across the metalake.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Role {} was restored, but its local entity cache could not be cleared", identifier, e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(RolePO role) {
    return new RecoveryMetadata.DeletedSnapshot(
        role.getRoleId(),
        role.getRoleName(),
        new RecoveryMetadata.ParentIdentity(role.getMetalakeId(), null, role.getMetalakeId()),
        role.getDeletedAt(),
        role.getCurrentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(RolePO role) {
    return new RecoveryMetadata.LiveIdentity(
        role.getRoleId(), role.getMetalakeId(), role.getRoleName());
  }
}
