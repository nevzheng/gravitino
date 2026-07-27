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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Tests for {@link IcebergDeletionMetricsSource}. */
public class TestIcebergDeletionMetricsSource {

  @Test
  void exposesPrdMetricsAndRecordsLifecycleOutcomes() {
    AtomicLong expired = new AtomicLong(3L);
    IcebergDeletionMetricsSource source = new IcebergDeletionMetricsSource(expired::get);

    source.recordTombstone();
    source.recordUndrop();
    source.recordCleanupFailure();
    source.recordCleanupSuccess(Duration.ofMillis(2_500L));

    assertEquals(
        1L,
        source
            .getMetricRegistry()
            .counter(IcebergDeletionMetricsSource.TOMBSTONES_TOTAL)
            .getCount());
    assertEquals(
        3L,
        source
            .getMetricRegistry()
            .getGauges()
            .get(IcebergDeletionMetricsSource.TOMBSTONES_EXPIRED_PENDING_CLEANUP)
            .getValue());
    assertEquals(
        1L,
        source
            .getMetricRegistry()
            .counter(IcebergDeletionMetricsSource.CLEANUP_SUCCESS_TOTAL)
            .getCount());
    assertEquals(
        1L,
        source
            .getMetricRegistry()
            .counter(IcebergDeletionMetricsSource.CLEANUP_FAILURE_TOTAL)
            .getCount());
    assertEquals(
        1L,
        source.getMetricRegistry().counter(IcebergDeletionMetricsSource.UNDROP_TOTAL).getCount());
    assertEquals(
        1L,
        source
            .getMetricRegistry()
            .histogram(IcebergDeletionMetricsSource.CLEANUP_DURATION_SECONDS)
            .getCount());
  }
}
