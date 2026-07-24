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
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;

/** Internal entity-specific operations required by the shared recovery protocol. */
interface RecoverableEntityAdapter<E> {

  /** Returns the persisted entity type used by deletion receipts. */
  Entity.EntityType entityType();

  /** Returns the stable recovery API type. */
  RecoveryEntityType recoveryType();

  /** Lists storage tombstones under the specified live namespace, newest first. */
  List<RecoveryMetadata.DeletedSnapshot> listDeleted(Namespace namespace);

  /** Resolves the immutable IDs of the current live parent path. */
  RecoveryMetadata.ParentIdentity resolveLiveParent(Namespace namespace);

  /** Lists live identities under the immediate parent. */
  List<RecoveryMetadata.LiveIdentity> listLiveInParent(Namespace namespace, long parentId);

  /** Lists globally live identities for the specified immutable IDs. */
  List<RecoveryMetadata.LiveIdentity> listLiveByIds(List<Long> ids);

  /** Returns the immutable identifier of a loaded live entity. */
  long id(E entity);

  /** Loads the live entity at an exact name identifier. */
  E loadLive(NameIdentifier identifier);

  /** Restores one exact deletion generation in the entity-specific relational transaction. */
  E restoreAtomically(
      NameIdentifier identifier,
      EntityDeletionPO deletion,
      long restoredAt,
      String acceptedEtag,
      long effectiveExpiresAt);

  /** Invalidates local entity and relation cache entries after commit. */
  void invalidate(NameIdentifier identifier);
}
