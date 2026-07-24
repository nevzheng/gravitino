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
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TagPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.TagMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational tag metadata to the shared recoverable-deletion protocol. */
final class TagRecoveryAdapter implements RecoverableEntityAdapter<TagEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(TagRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  TagRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.TAG;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.TAG;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return TagMetaService.getInstance().listDeletedTagsByNamespace(namespace).stream()
        .map(TagRecoveryAdapter::deletedSnapshot)
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
    return TagMetaService.getInstance().listLiveTagPOsByNamespace(namespace).stream()
        .map(TagRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return TagMetaService.getInstance().listLiveTagsByIds(ids).stream()
        .map(TagRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(TagEntity entity) {
    return entity.id();
  }

  @Override
  public TagEntity loadLive(NameIdentifier identifier) {
    return TagMetaService.getInstance().getTagByIdentifier(identifier);
  }

  @Override
  public TagEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return TagMetaService.getInstance()
        .restoreTag(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // Restoring a tag can revive relations to arbitrary metadata objects whose cache keys are
      // not derivable from the tag identifier alone.
      entityCache.clear();
    } catch (RuntimeException e) {
      LOG.warn(
          "Tag {} was restored, but its local entity cache could not be cleared", identifier, e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(TagPO tag) {
    return new RecoveryMetadata.DeletedSnapshot(
        tag.getTagId(),
        tag.getTagName(),
        new RecoveryMetadata.ParentIdentity(tag.getMetalakeId(), null, tag.getMetalakeId()),
        tag.getDeletedAt(),
        tag.getCurrentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(TagPO tag) {
    return new RecoveryMetadata.LiveIdentity(tag.getTagId(), tag.getMetalakeId(), tag.getTagName());
  }
}
