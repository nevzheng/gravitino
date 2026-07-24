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
package org.apache.gravitino.storage.relational.mapper;

import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for durable recoverable-deletion records. */
public interface EntityDeletionMapper {

  String TABLE_NAME = "entity_deletion";

  @InsertProvider(type = EntityDeletionSQLProvider.class, method = "insertEntityDeletion")
  void insertEntityDeletion(@Param("deletion") EntityDeletionPO deletion);

  @SelectProvider(type = EntityDeletionSQLProvider.class, method = "selectEntityDeletion")
  EntityDeletionPO selectEntityDeletion(@Param("deletionId") String deletionId);

  /** Selects the newest deletion generation for one immutable parent and entity name. */
  @SelectProvider(type = EntityDeletionSQLProvider.class, method = "selectLatestEntityDeletion")
  EntityDeletionPO selectLatestEntityDeletion(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") String entityName);

  @SelectProvider(
      type = EntityDeletionSQLProvider.class,
      method = "selectLatestEntityDeletionForUpdate")
  EntityDeletionPO selectLatestEntityDeletionForUpdate(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") String entityName);

  @SelectProvider(type = EntityDeletionSQLProvider.class, method = "listEntityDeletions")
  List<EntityDeletionPO> listEntityDeletions(
      @Param("entityType") String entityType,
      @Param("parentId") @Nullable Long parentId,
      @Param("entityName") @Nullable String entityName,
      @Param("entityId") @Nullable Long entityId,
      @Param("state") @Nullable DeletionState state);

  /** Selects one bounded batch of active deletions older than the current retention cutoff. */
  @SelectProvider(type = EntityDeletionSQLProvider.class, method = "listExpiredEntityDeletions")
  List<EntityDeletionPO> listExpiredEntityDeletions(
      @Param("entityType") String entityType,
      @Param("legacyTimeline") long legacyTimeline,
      @Param("limit") int limit);

  /** Selects one bounded batch of terminal deletion receipts older than the retention cutoff. */
  @SelectProvider(type = EntityDeletionSQLProvider.class, method = "listTerminalDeletionIds")
  List<String> listTerminalDeletionIds(
      @Param("entityType") String entityType,
      @Param("legacyTimeline") long legacyTimeline,
      @Param("limit") int limit);

  @UpdateProvider(type = EntityDeletionSQLProvider.class, method = "compareAndSetState")
  int compareAndSetState(
      @Param("deletionId") String deletionId,
      @Param("expectedState") DeletionState expectedState,
      @Param("expectedRevision") long expectedRevision,
      @Param("newState") DeletionState newState,
      @Param("completedAt") @Nullable Long completedAt,
      @Param("restoreEtag") @Nullable String restoreEtag);

  /** Deletes exact terminal deletion receipts selected by a bounded query. */
  @DeleteProvider(type = EntityDeletionSQLProvider.class, method = "deleteEntityDeletions")
  int deleteEntityDeletions(@Param("deletionIds") List<String> deletionIds);
}
