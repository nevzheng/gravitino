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
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** Purge-specific compare-and-set operations on durable deletion actions. */
public interface IcebergPurgeActionMapper {

  /**
   * Returns a bounded candidate window; callers must CAS each row before treating it as claimed.
   */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectEligibleActions")
  List<EntityDeletionPO> selectEligibleActions(
      @Param("jobType") String jobType, @Param("now") long now, @Param("limit") int limit);

  /** Atomically moves one still-eligible action directly from DELETED to PURGING. */
  @UpdateProvider(type = IcebergPurgeSQLProvider.class, method = "claimAction")
  int claimAction(
      @Param("deletionId") String deletionId,
      @Param("expectedRevision") long expectedRevision,
      @Param("jobType") String jobType,
      @Param("purgeJobId") String purgeJobId,
      @Param("now") long now);

  /** Returns the table-level actions in one batch. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "selectActionsByJob")
  List<EntityDeletionPO> selectActionsByJob(@Param("purgeJobId") String purgeJobId);

  /** Counts append-only lifecycle audit events associated with a batch. */
  @SelectProvider(type = IcebergPurgeSQLProvider.class, method = "countAuditsByJob")
  long countAuditsByJob(@Param("purgeJobId") String purgeJobId);
}
