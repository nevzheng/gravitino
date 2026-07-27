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

import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgePlanPO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for durable purge target-plan completeness markers. */
public interface IcebergPurgePlanMapper {

  /** Inserts a PLANNING marker before target enumeration begins. */
  @InsertProvider(type = IcebergPurgeSQLProvider.class, method = "insertPlan")
  void insertPlan(@Param("plan") IcebergPurgePlanPO plan);

  /** Loads one exact plan marker. */
  @Nullable
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectPlan")
  IcebergPurgePlanPO selectPlan(@Param("deletionId") String deletionId);

  /** Counts snapshotted physical targets. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "countTargets")
  long countTargets(@Param("deletionId") String deletionId);

  /** Finds the greatest delete order in the snapshot. */
  @Nullable
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "maxDeleteOrder")
  Integer maxDeleteOrder(@Param("deletionId") String deletionId);

  /** Reads the delete order of the designated root-metadata target. */
  @Nullable
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectTargetOrder")
  Integer selectTargetOrder(
      @Param("deletionId") String deletionId, @Param("targetId") String targetId);

  /** Atomically publishes a complete, root-last target snapshot. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "markPlanReady")
  int markPlanReady(
      @Param("deletionId") String deletionId,
      @Param("purgeJobId") String purgeJobId,
      @Param("contextDigest") String contextDigest,
      @Param("targetCount") long targetCount,
      @Param("rootTargetId") String rootTargetId,
      @Param("now") long now);
}
