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

package org.apache.gravitino.iceberg.service.deletion;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.apache.gravitino.metrics.source.MetricsSource;

/** Operator metrics for the Iceberg REST table-deletion lifecycle. */
public class IcebergDeletionMetricsSource extends MetricsSource {

  /** Number of accepted durable table tombstones since this process started. */
  public static final String TOMBSTONES_TOTAL = "tombstones-total";

  /** Number of retained actions currently eligible for a purge-job claim. */
  public static final String TOMBSTONES_EXPIRED_PENDING_CLEANUP =
      "tombstones-expired-pending-cleanup";

  /** Distribution of successful per-action cleanup durations in seconds. */
  public static final String CLEANUP_DURATION_SECONDS = "cleanup-duration-seconds";

  /** Number of deletion actions successfully finalized as PURGED. */
  public static final String CLEANUP_SUCCESS_TOTAL = "cleanup-success-total";

  /** Number of durable cleanup attempts that ended in a retryable or permanent failure. */
  public static final String CLEANUP_FAILURE_TOTAL = "cleanup-failure-total";

  /** Number of deletion actions successfully restored. */
  public static final String UNDROP_TOTAL = "undrop-total";

  private final Counter tombstones;
  private final Histogram cleanupDurationSeconds;
  private final Counter cleanupSuccesses;
  private final Counter cleanupFailures;
  private final Counter undrops;

  /**
   * Creates deletion metrics backed by a durable expired-action gauge.
   *
   * <p>The supplier is evaluated only when a reporter scrapes the gauge. It should return zero and
   * log internally if the metadata store is temporarily unavailable; metric collection must never
   * change lifecycle state.
   *
   * @param expiredPendingCleanup number of actions eligible for a purge-job claim
   */
  public IcebergDeletionMetricsSource(LongSupplier expiredPendingCleanup) {
    super(MetricsSource.GRAVITINO_ICEBERG_METRIC_NAME);
    Objects.requireNonNull(expiredPendingCleanup, "expiredPendingCleanup must not be null");
    this.tombstones = getCounter(TOMBSTONES_TOTAL);
    registerGauge(TOMBSTONES_EXPIRED_PENDING_CLEANUP, expiredPendingCleanup::getAsLong);
    this.cleanupDurationSeconds = getHistogram(CLEANUP_DURATION_SECONDS);
    this.cleanupSuccesses = getCounter(CLEANUP_SUCCESS_TOTAL);
    this.cleanupFailures = getCounter(CLEANUP_FAILURE_TOTAL);
    this.undrops = getCounter(UNDROP_TOTAL);
  }

  /** Records one newly committed deletion action. */
  public void recordTombstone() {
    tombstones.inc();
  }

  /** Records one successfully committed exact-generation restore. */
  public void recordUndrop() {
    undrops.inc();
  }

  /**
   * Records one successfully finalized purge action.
   *
   * @param duration external cleanup duration for that action
   */
  public void recordCleanupSuccess(Duration duration) {
    Objects.requireNonNull(duration, "duration must not be null");
    cleanupSuccesses.inc();
    cleanupDurationSeconds.update(Math.max(0L, duration.toSeconds()));
  }

  /** Records one durable cleanup-attempt failure. */
  public void recordCleanupFailure() {
    cleanupFailures.inc();
  }
}
