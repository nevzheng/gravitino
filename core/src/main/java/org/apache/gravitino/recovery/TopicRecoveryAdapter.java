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
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TopicPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.TopicMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational topic metadata to the shared recoverable-deletion protocol. */
final class TopicRecoveryAdapter implements RecoverableEntityAdapter<TopicEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(TopicRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  TopicRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.TOPIC;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.TOPIC;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return TopicMetaService.getInstance().listDeletedTopicsByNamespace(namespace).stream()
        .map(TopicRecoveryAdapter::deletedSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveLiveParent(Namespace namespace) {
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    long catalogId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0), namespace.level(1)), Entity.EntityType.CATALOG);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return new RecoveryMetadata.ParentIdentity(metalakeId, catalogId, schemaId);
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(
      Namespace namespace, @Nullable Long parentId) {
    return TopicMetaService.getInstance().listLiveTopicPOsByNamespace(namespace).stream()
        .map(TopicRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return TopicMetaService.getInstance().listLiveTopicsByIds(ids).stream()
        .map(TopicRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(TopicEntity entity) {
    return entity.id();
  }

  @Override
  public TopicEntity loadLive(NameIdentifier identifier) {
    return TopicMetaService.getInstance().getTopicByIdentifier(identifier);
  }

  @Override
  public TopicEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return TopicMetaService.getInstance()
        .restoreTopic(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      entityCache.invalidate(identifier, entityType());
    } catch (RuntimeException e) {
      LOG.warn(
          "Topic {} was restored, but its local entity cache could not be invalidated",
          identifier,
          e);
    }
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      try {
        entityCache.invalidateRelationEntry(identifier, entityType(), relationType);
      } catch (RuntimeException e) {
        LOG.warn(
            "Topic {} was restored, but its local {} relation cache could not be invalidated",
            identifier,
            relationType,
            e);
      }
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(TopicPO topic) {
    return new RecoveryMetadata.DeletedSnapshot(
        topic.getTopicId(),
        topic.getTopicName(),
        new RecoveryMetadata.ParentIdentity(
            topic.getMetalakeId(), topic.getCatalogId(), topic.getSchemaId()),
        topic.getDeletedAt(),
        topic.getCurrentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(TopicPO topic) {
    return new RecoveryMetadata.LiveIdentity(
        topic.getTopicId(), topic.getSchemaId(), topic.getTopicName());
  }
}
