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
package org.apache.gravitino.storage.relational.service;

import com.google.common.base.Preconditions;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.IdentityRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Shared receipt and parent invariants for standalone metalake-scoped recovery. */
final class MetalakeScopedRecoveryServiceSupport {

  private MetalakeScopedRecoveryServiceSupport() {}

  static void validateRestoreArguments(
      EntityDeletionPO observed, long restoredAt, String restoreEtag, long effectiveExpiresAt) {
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");
  }

  static void lockLiveMetalake(IdentityRecoveryMapper mapper, long metalakeId) {
    Long locked = mapper.lockLiveMetalake(metalakeId);
    if (!Objects.equals(metalakeId, locked)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(),
          metalakeId);
    }
  }

  static void lockLiveMetalakeForRestore(
      IdentityRecoveryMapper mapper, long metalakeId, NameIdentifier identifier) {
    Long locked = mapper.lockLiveMetalake(metalakeId);
    if (!Objects.equals(metalakeId, locked)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.PARENT_CHANGED,
          "The parent metalake changed while restoring %s",
          identifier);
    }
  }

  static void validateLatestDeletion(
      Entity.EntityType entityType,
      NameIdentifier identifier,
      long metalakeId,
      EntityDeletionPO observed) {
    EntityDeletionPO latest =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.selectLatestEntityDeletionForUpdate(
                    entityType.name(), metalakeId, identifier.name()));
    if (latest == null || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
          "Deletion generation %s is no longer latest for %s %s",
          observed.getDeletionId(),
          entityType.name().toLowerCase(),
          identifier);
    }
  }

  @Nullable
  static EntityDeletionPO loadDeletion(String deletionId) {
    return SessionUtils.getWithoutCommit(
        EntityDeletionMapper.class, mapper -> mapper.selectEntityDeletion(deletionId));
  }

  static void validateDeletionSnapshot(
      Entity.EntityType entityType,
      NameIdentifier identifier,
      long metalakeId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual) {
    boolean unchanged =
        sameDeletionSnapshot(observed, actual)
            && actual.getState() == DeletionState.DELETED
            && entityType.name().equals(actual.getEntityType())
            && Objects.equals(actual.getMetalakeId(), metalakeId)
            && actual.getCatalogId() == null
            && Objects.equals(actual.getParentId(), metalakeId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  static boolean isCompletedRestoreReplay(
      Entity.EntityType entityType,
      NameIdentifier identifier,
      long metalakeId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual,
      String restoreEtag) {
    return actual != null
        && observed.getState() == DeletionState.DELETED
        && actual.getState() == DeletionState.RESTORED
        && sameImmutableDeletion(observed, actual)
        && entityType.name().equals(actual.getEntityType())
        && Objects.equals(actual.getMetalakeId(), metalakeId)
        && actual.getCatalogId() == null
        && Objects.equals(actual.getParentId(), metalakeId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  static void validateNotExpired(EntityDeletionPO deletion, long effectiveExpiresAt) {
    if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
      throw new TombstoneExpiredException(
          "Deletion generation %s expired at %s", deletion.getDeletionId(), effectiveExpiresAt);
    }
  }

  static void claimRestore(EntityDeletionPO deletion) {
    int claimed =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.compareAndSetState(
                    deletion.getDeletionId(),
                    DeletionState.DELETED,
                    deletion.getRevision(),
                    DeletionState.RESTORING,
                    null,
                    null));
    if (claimed != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  static void completeRestore(EntityDeletionPO deletion, long restoredAt, String restoreEtag) {
    int completed =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.compareAndSetState(
                    deletion.getDeletionId(),
                    DeletionState.RESTORING,
                    deletion.getRevision() + 1L,
                    DeletionState.RESTORED,
                    restoredAt,
                    restoreEtag));
    if (completed != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  static void insertRestoreChange(NameIdentifier identifier, Entity.EntityType entityType) {
    SessionUtils.doWithoutCommit(
        EntityChangeLogMapper.class,
        mapper ->
            mapper.insertEntityChange(
                identifier.namespace().level(0),
                entityType.name(),
                identifier.toString(),
                OperateType.RESTORE));
  }

  static boolean eligibleForPurge(
      EntityDeletionPO observed, @Nullable EntityDeletionPO actual, long legacyTimeline) {
    return actual != null
        && actual.getState() == DeletionState.DELETED
        && Objects.equals(actual.getRevision(), observed.getRevision())
        && sameImmutableDeletion(observed, actual)
        && actual.getDeletedAt() > 0
        && actual.getDeletedAt() < legacyTimeline;
  }

  static boolean claimPurge(EntityDeletionPO deletion) {
    return SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.compareAndSetState(
                    deletion.getDeletionId(),
                    DeletionState.DELETED,
                    deletion.getRevision(),
                    DeletionState.PURGING,
                    null,
                    null))
        == 1;
  }

  static void completePurge(EntityDeletionPO deletion, long purgedAt) {
    int completed =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.compareAndSetState(
                    deletion.getDeletionId(),
                    DeletionState.PURGING,
                    deletion.getRevision() + 1L,
                    DeletionState.PURGED,
                    purgedAt,
                    null));
    if (completed != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  static void validateGenerationCompleteness(
      EntityDeletionPO deletion, int rootCount, long currentCount, int brokenReferences) {
    Long expected = deletion.getAffectedRowCount();
    if (expected == null
        || expected <= 0
        || rootCount != 1
        || currentCount != expected
        || brokenReferences != 0) {
      throw incompleteGeneration(deletion.getDeletionId());
    }
  }

  static long sumCounts(Map<?, Integer> counts) {
    long result = 0L;
    for (Integer count : counts.values()) {
      result = Math.addExact(result, count.longValue());
    }
    return result;
  }

  static RecoveryConflictException incompleteGeneration(String deletionId) {
    return new RecoveryConflictException(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        "Deletion generation %s is incomplete and requires manual repair",
        deletionId);
  }

  static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private static boolean sameDeletionSnapshot(
      EntityDeletionPO observed, @Nullable EntityDeletionPO actual) {
    return actual != null
        && sameImmutableDeletion(observed, actual)
        && Objects.equals(actual.getState(), observed.getState())
        && Objects.equals(actual.getRevision(), observed.getRevision())
        && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
        && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
        && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt());
  }

  private static boolean sameImmutableDeletion(EntityDeletionPO observed, EntityDeletionPO actual) {
    return Objects.equals(actual.getDeletionId(), observed.getDeletionId())
        && Objects.equals(actual.getEntityType(), observed.getEntityType())
        && Objects.equals(actual.getEntityId(), observed.getEntityId())
        && Objects.equals(actual.getMetalakeId(), observed.getMetalakeId())
        && Objects.equals(actual.getCatalogId(), observed.getCatalogId())
        && Objects.equals(actual.getParentId(), observed.getParentId())
        && Objects.equals(actual.getEntityName(), observed.getEntityName())
        && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
        && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
        && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
        && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
        && Objects.equals(actual.getAffectedRowCount(), observed.getAffectedRowCount());
  }
}
