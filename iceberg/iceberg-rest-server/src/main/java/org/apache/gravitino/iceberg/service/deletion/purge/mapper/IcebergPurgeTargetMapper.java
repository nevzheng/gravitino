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
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

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
}
