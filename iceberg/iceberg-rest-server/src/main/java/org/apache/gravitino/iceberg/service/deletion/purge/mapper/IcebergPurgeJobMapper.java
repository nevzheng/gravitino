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
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeJobPO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for durable Iceberg purge batch headers. */
public interface IcebergPurgeJobMapper {

  /** Inserts one nonempty batch header. */
  @InsertProvider(type = IcebergPurgeSQLProvider.class, method = "insertJob")
  void insertJob(@Param("job") IcebergPurgeJobPO job);

  /** Loads one exact batch header. */
  @Nullable
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectJob")
  IcebergPurgeJobPO selectJob(@Param("purgeJobId") String purgeJobId);

  /** Counts every durable batch header. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "countJobs")
  long countJobs();

  /** Lists a bounded candidate window for worker claim or reclaim. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectClaimableJobs")
  List<IcebergPurgeJobPO> selectClaimableJobs(@Param("now") long now, @Param("limit") int limit);

  /** Claims one candidate while incrementing its monotonic fencing epoch. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "claimJob")
  int claimJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("expectedState") String expectedState,
      @Param("expectedLeaseEpoch") long expectedLeaseEpoch,
      @Param("owner") String owner,
      @Param("now") long now,
      @Param("leaseExpiresAt") long leaseExpiresAt);

  /** Renews the lease only for the exact, unexpired owner and fencing epoch. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "heartbeatJob")
  int heartbeatJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now,
      @Param("leaseExpiresAt") long leaseExpiresAt);

  /** Returns one only when the exact owner holds the exact unexpired fencing epoch. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "ownsJobLease")
  long ownsJobLease(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now);

  /** Acquires the job row lock while verifying the exact live fencing lease. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "fenceJobLease")
  int fenceJobLease(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("now") long now);

  /** Releases a batch with unfinished automatic work for another fair claim. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "releaseJob")
  int releaseJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("pendingCount") long pendingCount,
      @Param("runningCount") long runningCount,
      @Param("succeededCount") long succeededCount,
      @Param("failedCount") long failedCount,
      @Param("retryingCount") long retryingCount,
      @Param("nextClaimAt") long nextClaimAt,
      @Param("now") long now);

  /** Commits a terminal aggregate state after all table items finish. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "finishJob")
  int finishJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("owner") String owner,
      @Param("leaseEpoch") long leaseEpoch,
      @Param("state") String state,
      @Param("succeededCount") long succeededCount,
      @Param("failedCount") long failedCount,
      @Param("now") long now);

  /** Reopens a terminal batch after at least one failed item was explicitly redriven. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "redriveTerminalJob")
  int redriveTerminalJob(
      @Param("purgeJobId") String purgeJobId,
      @Param("pendingCount") long pendingCount,
      @Param("runningCount") long runningCount,
      @Param("succeededCount") long succeededCount,
      @Param("failedCount") long failedCount,
      @Param("retryingCount") long retryingCount,
      @Param("now") long now);
}
