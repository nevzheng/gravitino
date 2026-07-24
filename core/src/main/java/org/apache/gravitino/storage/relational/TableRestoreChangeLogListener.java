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
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.storage.relational.po.cache.EntityChangeRecord;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Invalidates local table cache entries after another server restores a table. */
final class TableRestoreChangeLogListener implements EntityChangeLogListener {

  private static final Logger LOG = LoggerFactory.getLogger(TableRestoreChangeLogListener.class);

  private final EntityCache cache;

  TableRestoreChangeLogListener(EntityCache cache) {
    this.cache = cache;
  }

  @Override
  public void onEntityChange(List<EntityChangeRecord> changes) {
    for (EntityChangeRecord change : changes) {
      NameIdentifier identifier;
      try {
        identifier = restoredTableIdentifier(change);
      } catch (RuntimeException e) {
        logInvalidationFailure(change, e);
        continue;
      }
      if (identifier == null) {
        continue;
      }

      // EntityCache implementations are not required to cascade a plain entity invalidation to
      // relation entries. Invalidate the entity first so implementations such as Caffeine can use
      // any cached relationships to evict counterpart keys, then explicitly remove every
      // table-side relation entry, including OWNER_REL.
      try {
        cache.invalidate(identifier, Entity.EntityType.TABLE);
      } catch (RuntimeException e) {
        logInvalidationFailure(change, e);
      }
      for (SupportsRelationOperations.Type relationType :
          SupportsRelationOperations.Type.values()) {
        try {
          cache.invalidateRelationEntry(identifier, Entity.EntityType.TABLE, relationType);
        } catch (RuntimeException e) {
          logInvalidationFailure(change, e);
        }
      }
    }
  }

  private NameIdentifier restoredTableIdentifier(EntityChangeRecord change) {
    if (change.getOperateType() != OperateType.RESTORE
        || change.getEntityType() == null
        || !Entity.EntityType.TABLE.name().equalsIgnoreCase(change.getEntityType())
        || change.getFullName() == null) {
      return null;
    }

    NameIdentifier identifier = NameIdentifier.parse(change.getFullName());
    if (identifier.namespace().length() != 3) {
      LOG.warn("Invalid restored table full name in entity change log: {}", change.getFullName());
      return null;
    }
    if (change.getMetalakeName() == null
        || !change.getMetalakeName().equals(identifier.namespace().level(0))) {
      LOG.warn(
          "Restored table change has inconsistent metalake: metalake={}, fullName={}",
          change.getMetalakeName(),
          change.getFullName());
      return null;
    }
    return identifier;
  }

  private void logInvalidationFailure(EntityChangeRecord change, RuntimeException failure) {
    LOG.warn(
        "Failed to invalidate table cache for restore change: fullName={}, entityType={}",
        change.getFullName(),
        change.getEntityType(),
        failure);
  }
}
