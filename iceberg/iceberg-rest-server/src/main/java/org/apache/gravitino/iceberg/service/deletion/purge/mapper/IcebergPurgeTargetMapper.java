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

package org.apache.gravitino.iceberg.service.deletion.purge.mapper;

import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeCountsPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for the per-object Iceberg purge progress ledger. */
public interface IcebergPurgeTargetMapper {

  /** Inserts one immutable target snapshot in PENDING state. */
  @InsertProvider(type = IcebergPurgeSQLProvider.class, method = "insertTarget")
  void insertTarget(@Param("target") IcebergPurgeTargetPO target);

  /** Loads one exact physical target. */
  @Nullable
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectTarget")
  IcebergPurgeTargetPO selectTarget(
      @Param("deletionId") String deletionId, @Param("targetId") String targetId);

  /** Lists a bounded child-before-parent candidate batch from a READY target plan. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectTargetCandidates")
  List<IcebergPurgeTargetPO> selectTargetCandidates(
      @Param("deletionId") String deletionId,
      @Param("purgeJobId") String purgeJobId,
      @Param("limit") int limit);

  /** Claims one target under an exact live batch lease. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "claimTarget")
  int claimTarget(
      @Param("deletionId") String deletionId,
      @Param("targetId") String targetId,
      @Param("priorState") String priorState,
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now);

  /** Marks one exact target succeeded under matching target and batch fencing epochs. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "markTargetSucceeded")
  int markTargetSucceeded(
      @Param("deletionId") String deletionId,
      @Param("targetId") String targetId,
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now);

  /** Records target retry or permanent failure under matching fencing epochs. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "markTargetFailed")
  int markTargetFailed(
      @Param("deletionId") String deletionId,
      @Param("targetId") String targetId,
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("nextState") String nextState,
      @Param("reason") String reason,
      @Param("now") long now);

  /** Makes target attempts interrupted under a stale lease retryable. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "resetRunningTargets")
  int resetRunningTargets(
      @Param("purgeJobId") String purgeJobId,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now);

  /** Aggregates per-target progress for one deletion action. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "countTargetStatuses")
  IcebergPurgeCountsPO countTargetStatuses(@Param("deletionId") String deletionId);

  /** Deletes target details only after their PURGED action receipt has passed retention. */
  @DeleteProvider(type = IcebergPurgeSQLProvider.class, method = "deleteTargets")
  int deleteTargets(@Param("deletionId") String deletionId);
}
