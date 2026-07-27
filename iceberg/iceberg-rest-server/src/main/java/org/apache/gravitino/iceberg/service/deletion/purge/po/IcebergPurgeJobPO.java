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

package org.apache.gravitino.iceberg.service.deletion.purge.po;

import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Persistence object for one durable, bounded Iceberg purge batch. */
@Getter
@Builder(setterPrefix = "with")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IcebergPurgeJobPO {
  private String purgeJobId;
  private String purgeJobType;
  private String state;
  @Nullable private String owner;
  private Long leaseEpoch;
  @Nullable private Long leaseExpiresAt;
  @Nullable private Long heartbeatAt;
  private Integer attemptCount;
  private Integer itemCount;
  private Integer pendingCount;
  private Integer runningCount;
  private Integer succeededCount;
  private Integer failedCount;
  private Integer retryingCount;
  @Nullable private String lastError;
  private String createdBy;
  @Nullable private String requestId;
  private String correlationId;
  private Long createdAt;
  @Nullable private Long startedAt;
  @Nullable private Long completedAt;
  private Long updatedAt;
}
