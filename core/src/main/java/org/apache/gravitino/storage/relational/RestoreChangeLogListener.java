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
package org.apache.gravitino.storage.relational;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.storage.relational.po.cache.EntityChangeRecord;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Invalidates local cache entries after another server restores recoverable metadata. */
final class RestoreChangeLogListener implements EntityChangeLogListener {

  private static final Logger LOG = LoggerFactory.getLogger(RestoreChangeLogListener.class);

  private static final Set<Entity.EntityType> RECOVERABLE_TYPES =
      Set.of(Entity.EntityType.TABLE, Entity.EntityType.POLICY);

  private final EntityCache cache;

  RestoreChangeLogListener(EntityCache cache) {
    this.cache = cache;
  }

  @Override
  public void onEntityChange(List<EntityChangeRecord> changes) {
    for (EntityChangeRecord change : changes) {
      Entity.EntityType entityType = restoredEntityType(change);
      if (entityType == null) {
        continue;
      }

      NameIdentifier identifier;
      try {
        identifier = restoredIdentifier(change, entityType);
      } catch (RuntimeException e) {
        clearCacheAfterInvalidRestore(change, e);
        continue;
      }

      if (entityType == Entity.EntityType.POLICY) {
        // Policy associations remain live during the metadata-only deletion. An exact cache
        // invalidation cannot reliably invalidate their counterpart entries, so clear locally.
        clearCache(change);
        continue;
      }

      // EntityCache implementations are not required to cascade a plain entity invalidation to
      // relation entries. Invalidate the entity first so implementations such as Caffeine can use
      // any cached relationships to evict counterpart keys, then explicitly remove every
      // entity-side relation entry, including OWNER_REL.
      try {
        cache.invalidate(identifier, entityType);
      } catch (RuntimeException e) {
        logInvalidationFailure(change, e);
      }
      for (SupportsRelationOperations.Type relationType :
          SupportsRelationOperations.Type.values()) {
        try {
          cache.invalidateRelationEntry(identifier, entityType, relationType);
        } catch (RuntimeException e) {
          logInvalidationFailure(change, e);
        }
      }
    }
  }

  private Entity.EntityType restoredEntityType(EntityChangeRecord change) {
    if (change.getOperateType() != OperateType.RESTORE || change.getEntityType() == null) {
      return null;
    }

    try {
      Entity.EntityType entityType =
          Entity.EntityType.valueOf(change.getEntityType().toUpperCase(Locale.ROOT));
      return RECOVERABLE_TYPES.contains(entityType) ? entityType : null;
    } catch (IllegalArgumentException e) {
      LOG.warn("Unknown entity type in restore change log: {}", change.getEntityType());
      return null;
    }
  }

  private NameIdentifier restoredIdentifier(
      EntityChangeRecord change, Entity.EntityType entityType) {
    if (change.getFullName() == null) {
      throw new IllegalArgumentException("Restore change is missing its full name");
    }

    NameIdentifier identifier = NameIdentifier.parse(change.getFullName());
    int expectedNamespaceLength = entityType == Entity.EntityType.POLICY ? 1 : 3;
    if (identifier.namespace().length() != expectedNamespaceLength) {
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "Invalid restored %s full name in entity change log: %s",
              entityType.name().toLowerCase(Locale.ROOT),
              change.getFullName()));
    }
    if (change.getMetalakeName() == null
        || !change.getMetalakeName().equals(identifier.namespace().level(0))) {
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "Restored entity change has inconsistent metalake: metalake=%s, fullName=%s",
              change.getMetalakeName(),
              change.getFullName()));
    }
    return identifier;
  }

  private void clearCacheAfterInvalidRestore(
      EntityChangeRecord change, RuntimeException validationFailure) {
    LOG.warn(
        "Cannot target cache invalidation for restore change; clearing the local entity cache: fullName={}, entityType={}",
        change.getFullName(),
        change.getEntityType(),
        validationFailure);
    clearCache(change);
  }

  private void clearCache(EntityChangeRecord change) {
    try {
      cache.clear();
    } catch (RuntimeException clearFailure) {
      logInvalidationFailure(change, clearFailure);
    }
  }

  private void logInvalidationFailure(EntityChangeRecord change, RuntimeException failure) {
    LOG.warn(
        "Failed to invalidate cache for restore change: fullName={}, entityType={}",
        change.getFullName(),
        change.getEntityType(),
        failure);
  }
}
