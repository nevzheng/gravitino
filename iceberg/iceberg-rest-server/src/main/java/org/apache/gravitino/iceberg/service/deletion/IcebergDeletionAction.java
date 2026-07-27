/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.deletion;

import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;

/** Safe management representation of one Iceberg table deletion action. */
@Getter
@Builder
public class IcebergDeletionAction {
  private final String deletionId;
  private final String entityId;
  private final String state;
  private final long revision;
  private final long deletedAt;
  @Nullable private final Long retentionExpiresAt;
  @Nullable private final String cleanupStatus;
  @Nullable private final String purgeJobId;
  private final int cleanupAttemptCount;
  @Nullable private final String cleanupLastError;
  private final String deletedBy;
  private final boolean recoverable;
  @Nullable private final Long restoredAt;
  @Nullable private final Long purgedAt;
}
