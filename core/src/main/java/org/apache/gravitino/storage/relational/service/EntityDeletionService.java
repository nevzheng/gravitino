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
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service for durable recoverable-deletion generations and restore receipts. */
public class EntityDeletionService {

  private static final EntityDeletionService INSTANCE = new EntityDeletionService();

  /**
   * Returns the singleton deletion service.
   *
   * @return deletion service
   */
  public static EntityDeletionService getInstance() {
    return INSTANCE;
  }

  private EntityDeletionService() {}

  /**
   * Creates an unpersisted deletion generation.
   *
   * @param entityType deleted entity type
   * @param entityId immutable entity identifier
   * @param metalakeId immutable metalake identifier
   * @param catalogId immutable catalog identifier, when applicable
   * @param parentId immutable immediate-parent identifier, when applicable
   * @param entityName entity name at deletion time
   * @param entityVersion entity version at deletion time, when applicable
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @param deletedBy deleting principal
   * @return a new deletion generation
   */
  public EntityDeletionPO newDeletion(
      Entity.EntityType entityType,
      long entityId,
      long metalakeId,
      @Nullable Long catalogId,
      @Nullable Long parentId,
      String entityName,
      @Nullable Long entityVersion,
      long deletedAt,
      long retentionMs,
      @Nullable String deletedBy) {
    return EntityDeletionPO.builder()
        .deletionId(UUID.randomUUID().toString())
        .entityType(entityType.name())
        .entityId(entityId)
        .metalakeId(metalakeId)
        .catalogId(catalogId)
        .parentId(parentId)
        .entityName(entityName)
        .deletedAt(deletedAt)
        .expiresAt(Math.addExact(deletedAt, retentionMs))
        .deletedBy(deletedBy)
        .entityVersion(entityVersion)
        .state(DeletionState.DELETED)
        .revision(0L)
        .build();
  }

  /**
   * Persists a deletion generation in the current transaction.
   *
   * @param deletion deletion generation
   */
  public void insert(EntityDeletionPO deletion) {
    SessionUtils.doWithCommit(
        EntityDeletionMapper.class, mapper -> mapper.insertEntityDeletion(deletion));
  }

  /**
   * Loads an exact deletion generation.
   *
   * @param deletionId deletion-generation identifier
   * @return deletion generation, or {@code null} when it was never recorded
   */
  @Nullable
  public EntityDeletionPO get(String deletionId) {
    return SessionUtils.doWithCommitAndFetchResult(
        EntityDeletionMapper.class, mapper -> mapper.selectEntityDeletion(deletionId));
  }

  /**
   * Lists deletion generations under an immutable parent.
   *
   * @param entityType entity type
   * @param parentId immutable immediate-parent identifier
   * @param entityName optional exact entity name
   * @param entityId optional exact entity identifier
   * @param state optional deletion state
   * @return matching deletion generations, newest first
   */
  public List<EntityDeletionPO> list(
      Entity.EntityType entityType,
      @Nullable Long parentId,
      @Nullable String entityName,
      @Nullable Long entityId,
      @Nullable DeletionState state) {
    return SessionUtils.doWithCommitAndFetchResult(
        EntityDeletionMapper.class,
        mapper ->
            mapper.listEntityDeletions(entityType.name(), parentId, entityName, entityId, state));
  }

  /**
   * Lists one bounded batch of active deletions older than the current retention cutoff.
   *
   * @param entityType entity type
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum records to return
   * @return expired active deletion generations, oldest first
   */
  public List<EntityDeletionPO> listExpired(
      Entity.EntityType entityType, long legacyTimeline, int limit) {
    Preconditions.checkArgument(limit > 0, "limit must be positive");
    return SessionUtils.doWithCommitAndFetchResult(
        EntityDeletionMapper.class,
        mapper -> mapper.listExpiredEntityDeletions(entityType.name(), legacyTimeline, limit));
  }

  /**
   * Deletes one bounded batch of terminal deletion receipts after their audit retention expires.
   *
   * <p>Restored and purged receipts are retained for the current global retention window after
   * completion. Active deletion generations are never selected by this method.
   *
   * @param entityType entity type
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum receipts to delete
   * @return number of deleted receipts
   */
  public int deleteTerminalReceipts(Entity.EntityType entityType, long legacyTimeline, int limit) {
    Preconditions.checkArgument(limit > 0, "limit must be positive");
    List<String> deletionIds =
        SessionUtils.doWithCommitAndFetchResult(
            EntityDeletionMapper.class,
            mapper -> mapper.listTerminalDeletionIds(entityType.name(), legacyTimeline, limit));
    if (deletionIds.isEmpty()) {
      return 0;
    }
    return SessionUtils.doWithCommitAndFetchResult(
        EntityDeletionMapper.class, mapper -> mapper.deleteEntityDeletions(deletionIds));
  }

  /**
   * Atomically transitions a deletion generation when its state and revision still match.
   *
   * @param deletionId deletion-generation identifier
   * @param expectedState state observed by the caller
   * @param expectedRevision revision observed by the caller
   * @param newState requested state
   * @param completedAt completion timestamp for terminal states
   * @param restoreEtag exact precondition accepted by a successful restore
   * @return {@code true} when the transition won the compare-and-set race
   */
  public boolean compareAndSetState(
      String deletionId,
      DeletionState expectedState,
      long expectedRevision,
      DeletionState newState,
      @Nullable Long completedAt,
      @Nullable String restoreEtag) {
    return SessionUtils.doWithCommitAndFetchResult(
            EntityDeletionMapper.class,
            mapper ->
                mapper.compareAndSetState(
                    deletionId,
                    expectedState,
                    expectedRevision,
                    newState,
                    completedAt,
                    restoreEtag))
        == 1;
  }
}
