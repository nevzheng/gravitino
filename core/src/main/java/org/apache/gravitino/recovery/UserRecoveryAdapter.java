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
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.UserMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational user metadata to the shared recoverable-deletion protocol. */
final class UserRecoveryAdapter implements RecoverableEntityAdapter<UserEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(UserRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  UserRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.USER;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.USER;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return UserMetaService.getInstance().listDeletedUsersByNamespace(namespace).stream()
        .map(UserRecoveryAdapter::deletedSnapshot)
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
    return UserMetaService.getInstance().listLiveUserPOsByNamespace(namespace).stream()
        .map(UserRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return UserMetaService.getInstance().listLiveUsersByIds(ids).stream()
        .map(UserRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(UserEntity entity) {
    return entity.id();
  }

  @Override
  public UserEntity loadLive(NameIdentifier identifier) {
    return UserMetaService.getInstance().getUserByIdentifier(identifier);
  }

  @Override
  public UserEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return UserMetaService.getInstance()
        .restoreUser(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // User restoration can revive ownership and role-assignment rows across the metalake.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "User {} was restored, but its local entity cache could not be cleared", identifier, e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(UserPO user) {
    return new RecoveryMetadata.DeletedSnapshot(
        user.getUserId(),
        user.getUserName(),
        new RecoveryMetadata.ParentIdentity(user.getMetalakeId(), null, user.getMetalakeId()),
        user.getDeletedAt(),
        user.getCurrentVersion(),
        user.getExternalId());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(UserPO user) {
    return new RecoveryMetadata.LiveIdentity(
        user.getUserId(), user.getMetalakeId(), user.getUserName(), user.getExternalId());
  }
}
