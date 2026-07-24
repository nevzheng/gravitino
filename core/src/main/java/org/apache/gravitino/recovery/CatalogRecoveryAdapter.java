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
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.CatalogMetaService;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts relational catalog-tree metadata to the shared recoverable-deletion protocol. */
final class CatalogRecoveryAdapter implements RecoverableEntityAdapter<CatalogEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogRecoveryAdapter.class);

  @Nullable private final EntityCache entityCache;

  CatalogRecoveryAdapter(@Nullable EntityCache entityCache) {
    this.entityCache = entityCache;
  }

  @Override
  public Entity.EntityType entityType() {
    return Entity.EntityType.CATALOG;
  }

  @Override
  public RecoveryEntityType recoveryType() {
    return RecoveryEntityType.CATALOG;
  }

  @Override
  public List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace) {
    RecoveryMetadata.ParentIdentity parent = resolveLiveParent(namespace);
    return CatalogMetaService.getInstance().listDeletedCatalogsByNamespace(namespace).stream()
        .map(catalog -> deletedSnapshot(catalog, parent))
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
  public List<RecoveryMetadata.LiveIdentity> listLiveInParent(Namespace namespace, long parentId) {
    return CatalogMetaService.getInstance().listCatalogsByNamespace(namespace).stream()
        .map(catalog -> new RecoveryMetadata.LiveIdentity(catalog.id(), parentId, catalog.name()))
        .collect(Collectors.toList());
  }

  @Override
  public List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids) {
    return CatalogMetaService.getInstance().listLiveCatalogsByIds(ids).stream()
        .map(
            catalog ->
                new RecoveryMetadata.LiveIdentity(
                    catalog.getCatalogId(), catalog.getMetalakeId(), catalog.getCatalogName()))
        .collect(Collectors.toList());
  }

  @Override
  public long id(CatalogEntity entity) {
    return entity.id();
  }

  @Override
  public CatalogEntity loadLive(NameIdentifier identifier) {
    return CatalogMetaService.getInstance().getCatalogByIdentifier(identifier);
  }

  @Override
  public CatalogEntity restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt) {
    return CatalogMetaService.getInstance()
        .restoreCatalog(identifier, deletion, restoredAt, acceptedEtag, effectiveExpiresAt);
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
          "Catalog tree {} was restored, but the local entity cache could not be cleared",
          identifier,
          e);
    }
  }

  private static RecoveryMetadata.DeletedSnapshot deletedSnapshot(
      CatalogPO catalog, RecoveryMetadata.ParentIdentity parent) {
    return new RecoveryMetadata.DeletedSnapshot(
        catalog.getCatalogId(),
        catalog.getCatalogName(),
        parent,
        catalog.getDeletedAt(),
        catalog.getCurrentVersion());
  }
}
