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
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.SchemaPO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.SchemaMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational schema-tree metadata to the shared recoverable-deletion protocol. */
final class SchemaRecoveryAdapter implements RecoverableEntityAdapter<SchemaEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  SchemaRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.SCHEMA;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.SCHEMA;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return listDeleted(namespace, null);
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(
      Namespace namespace, @Nullable String parentScope) {
    RecoveryMetadata.ParentIdentity parent = resolveLiveParent(namespace, parentScope);
    return SchemaMetaService.getInstance()
        .listDeletedSchemasByParent(namespace, parentScope)
        .stream()
        .map(schema -> deletedSnapshot(schema, parent.parentId()))
        .collect(Collectors.toList());
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveLiveParent(Namespace namespace) {
    return resolveLiveParent(namespace, null);
  }

  @Override
  public RecoveryMetadata.ParentIdentity resolveLiveParent(
      Namespace namespace, @Nullable String parentScope) {
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    long catalogId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.CATALOG);
    long parentId =
        parentScope == null
            ? catalogId
            : EntityIdService.getEntityId(
                NameIdentifier.of(namespace, parentScope), Entity.EntityType.SCHEMA);
    return new RecoveryMetadata.ParentIdentity(metalakeId, catalogId, parentId);
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(Namespace namespace, long parentId) {
    return listLiveInParent(namespace, parentId, null);
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(
      Namespace namespace, long parentId, @Nullable String parentScope) {
    return SchemaMetaService.getInstance().listLiveSchemasByParent(namespace, parentScope).stream()
        .map(
            schema ->
                new RecoveryMetadata.LiveIdentity(
                    schema.getSchemaId(), parentId, schema.getSchemaName()))
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    SchemaMetaService schemaService = SchemaMetaService.getInstance();
    return schemaService.listLiveSchemasByIds(ids).stream()
        .map(
            schema ->
                new RecoveryMetadata.LiveIdentity(
                    schema.getSchemaId(),
                    schemaService.resolveLiveSchemaParentId(
                        schema.getCatalogId(), schema.getSchemaName()),
                    schema.getSchemaName()))
        .collect(Collectors.toList());
  }

  @Override
  public long id(SchemaEntity entity) {
    return entity.id();
  }

  @Override
  public SchemaEntity loadLive(NameIdentifier identifier) {
    return SchemaMetaService.getInstance().getSchemaByIdentifier(identifier);
  }

  @Override
  public SchemaEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return SchemaMetaService.getInstance()
        .restoreSchema(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
  }

  @Override
  public void invalidate(NameIdentifier identifier) {
    if (entityCache == null) {
      return;
    }
    try {
      // Schema invalidation is prefix-cascading in CaffeineEntityCache, so restoring a root also
      // evicts cached descendants and leaf metadata below that root.
      entityCache.invalidate(identifier, entityType());
    } catch (RuntimeException e) {
      LOG.warn(
          "Schema tree {} was restored, but its local entity cache could not be invalidated",
          identifier,
          e);
    }
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      try {
        entityCache.invalidateRelationEntry(identifier, entityType(), relationType);
      } catch (RuntimeException e) {
        LOG.warn(
            "Schema tree {} was restored, but its local {} relation cache could not be invalidated",
            identifier,
            relationType,
            e);
      }
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(SchemaPO schema, long parentId) {
    return new RecoveryMetadata.DeletedSnapshot(
        schema.getSchemaId(),
        schema.getSchemaName(),
        new RecoveryMetadata.ParentIdentity(
            schema.getMetalakeId(), schema.getCatalogId(), parentId),
        schema.getDeletedAt(),
        schema.getCurrentVersion());
  }
}
