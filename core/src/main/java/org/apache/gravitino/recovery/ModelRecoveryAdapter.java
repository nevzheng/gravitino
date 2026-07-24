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
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ModelPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.ModelMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational model metadata to the shared recoverable-deletion protocol. */
final class ModelRecoveryAdapter implements RecoverableEntityAdapter<ModelEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(ModelRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  ModelRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.MODEL;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.MODEL;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return ModelMetaService.getInstance().listDeletedModelsByNamespace(namespace).stream()
        .map(ModelRecoveryAdapter::deletedSnapshot)
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
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(Namespace namespace, long parentId) {
    return ModelMetaService.getInstance().listLiveModelPOsByNamespace(namespace).stream()
        .map(ModelRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return ModelMetaService.getInstance().listLiveModelsByIds(ids).stream()
        .map(ModelRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(ModelEntity entity) {
    return entity.id();
  }

  @Override
  public ModelEntity loadLive(NameIdentifier identifier) {
    return ModelMetaService.getInstance().getModelByIdentifier(identifier);
  }

  @Override
  public ModelEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return ModelMetaService.getInstance()
        .restoreModel(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
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
          "Model {} was restored, but its local entity cache could not be invalidated",
          identifier,
          e);
    }
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      try {
        entityCache.invalidateRelationEntry(identifier, entityType(), relationType);
      } catch (RuntimeException e) {
        LOG.warn(
            "Model {} was restored, but its local {} relation cache could not be invalidated",
            identifier,
            relationType,
            e);
      }
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(ModelPO model) {
    return new RecoveryMetadata.DeletedSnapshot(
        model.getModelId(),
        model.getModelName(),
        new RecoveryMetadata.ParentIdentity(
            model.getMetalakeId(), model.getCatalogId(), model.getSchemaId()),
        model.getDeletedAt(),
        model.getModelLatestVersion().longValue());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(ModelPO model) {
    return new RecoveryMetadata.LiveIdentity(
        model.getModelId(), model.getSchemaId(), model.getModelName());
  }
}
