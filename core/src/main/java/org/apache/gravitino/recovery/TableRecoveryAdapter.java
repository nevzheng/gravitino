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
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.TableMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational table metadata to the shared recoverable-deletion protocol. */
final class TableRecoveryAdapter implements RecoverableEntityAdapter<TableEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(TableRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  TableRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.TABLE;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.TABLE;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    return TableMetaService.getInstance().listDeletedTablesByNamespace(namespace).stream()
        .map(TableRecoveryAdapter::deletedSnapshot)
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
    return TableMetaService.getInstance().listTablesByNamespace(namespace).stream()
        .map(table -> new RecoveryMetadata.LiveIdentity(table.id(), parentId, table.name()))
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return TableMetaService.getInstance().listLiveTablesByIds(ids).stream()
        .map(TableRecoveryAdapter::liveIdentity)
        .collect(Collectors.toList());
  }

  @Override
  public long id(TableEntity entity) {
    return entity.id();
  }

  @Override
  public TableEntity loadLive(NameIdentifier identifier) {
    return TableMetaService.getInstance().getTableByIdentifier(identifier);
  }

  @Override
  public TableEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return TableMetaService.getInstance()
        .restoreTable(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
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
          "Table {} was restored, but its local entity cache could not be invalidated",
          identifier,
          e);
    }
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      try {
        entityCache.invalidateRelationEntry(identifier, entityType(), relationType);
      } catch (RuntimeException e) {
        LOG.warn(
            "Table {} was restored, but its local {} relation cache could not be invalidated",
            identifier,
            relationType,
            e);
      }
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(TablePO table) {
    return new RecoveryMetadata.DeletedSnapshot(
        table.getTableId(),
        table.getTableName(),
        new RecoveryMetadata.ParentIdentity(
            table.getMetalakeId(), table.getCatalogId(), table.getSchemaId()),
        table.getDeletedAt(),
        table.getCurrentVersion());
  }

  private static RecoveryMetadata.LiveIdentity liveIdentity(TablePO table) {
    return new RecoveryMetadata.LiveIdentity(
        table.getTableId(), table.getSchemaId(), table.getTableName());
  }
}
