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
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational metalake-tree metadata to the shared recoverable-deletion protocol. */
final class MetalakeRecoveryAdapter implements RecoverableEntityAdapter<BaseMetalake> {

  private static final Logger LOG = LoggerFactory.getLogger(MetalakeRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  MetalakeRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.METALAKE;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.METALAKE;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return MetalakeMetaService.getInstance().listDeletedMetalakes().stream()
        .map(MetalakeRecoveryAdapter::deletedSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveLiveParent(Namespace namespace) {
    throw new UnsupportedOperationException("Metalakes are root resources without a live parent");
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveCurrentParent(
      Namespace namespace, @Nullable String parentScope, EntityDeletionPO deletion) {
    return new RecoveryMetadata.ParentIdentity(deletion.getEntityId(), null, null);
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(
      Namespace namespace, @Nullable Long parentId) {
    return MetalakeMetaService.getInstance().listMetalakes().stream()
        .map(metalake -> new RecoveryMetadata.LiveIdentity(metalake.id(), null, metalake.name()))
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return MetalakeMetaService.getInstance().listLiveMetalakesByIds(ids).stream()
        .map(
            metalake ->
                new RecoveryMetadata.LiveIdentity(
                    metalake.getMetalakeId(), null, metalake.getMetalakeName()))
        .collect(Collectors.toList());
  }

  @Override
  public long id(BaseMetalake entity) {
    return entity.id();
  }

  @Override
  public BaseMetalake loadLive(NameIdentifier identifier) {
    return MetalakeMetaService.getInstance().getMetalakeByIdentifier(identifier);
  }

  @Override
  public BaseMetalake restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return MetalakeMetaService.getInstance()
        .restoreMetalake(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Metalake tree {} was restored, but the local entity cache could not be cleared",
          identifier,
          e);
    }
  }

  @Override
  public boolean rootScoped() {
    return true;
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(MetalakePO metalake) {
    return new RecoveryMetadata.DeletedSnapshot(
        metalake.getMetalakeId(),
        metalake.getMetalakeName(),
        new RecoveryMetadata.ParentIdentity(metalake.getMetalakeId(), null, null),
        metalake.getDeletedAt(),
        metalake.getCurrentVersion());
  }
}
