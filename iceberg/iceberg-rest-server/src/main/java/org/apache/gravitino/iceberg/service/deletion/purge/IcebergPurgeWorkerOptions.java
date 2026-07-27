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

package org.apache.gravitino.iceberg.service.deletion.purge;

import lombok.Builder;
import lombok.Getter;

/** Bounded retry, lease, and streaming controls for one purge worker. */
@Getter
@Builder(setterPrefix = "with")
public class IcebergPurgeWorkerOptions {
  private final long leaseDurationMs;
  private final int jobCandidateWindow;
  private final int targetBatchSize;
  private final int planningWriteBatchSize;
  private final int maxTargetBatchesPerRun;
  private final int maxActionAttempts;
  private final int maxTargetAttempts;

  /** Returns conservative defaults suitable for production configuration wiring. */
  public static IcebergPurgeWorkerOptions defaults() {
    return builder()
        .withLeaseDurationMs(300_000L)
        .withJobCandidateWindow(16)
        .withTargetBatchSize(100)
        .withPlanningWriteBatchSize(500)
        .withMaxTargetBatchesPerRun(100)
        .withMaxActionAttempts(5)
        .withMaxTargetAttempts(5)
        .build();
  }

  /** Validates that every bound is positive and the planning chunk fits the store ceiling. */
  public void validate() {
    if (leaseDurationMs <= 0
        || jobCandidateWindow <= 0
        || targetBatchSize <= 0
        || planningWriteBatchSize <= 0
        || planningWriteBatchSize > IcebergPurgeJobStore.MAX_TARGET_WRITE_BATCH
        || maxTargetBatchesPerRun <= 0
        || maxActionAttempts <= 0
        || maxTargetAttempts <= 0) {
      throw new IllegalArgumentException("All purge worker bounds must be positive and valid");
    }
  }
}
