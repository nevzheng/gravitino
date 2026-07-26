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

package org.apache.gravitino.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestLifecycleCoordinator {

  @Test
  public void testStartsInOrderAndStopsInReverseOrder() throws Exception {
    List<String> operations = new ArrayList<>();
    LifecycleCoordinator coordinator =
        LifecycleCoordinator.builder()
            .add(
                "storage",
                () -> operations.add("start-storage"),
                () -> operations.add("stop-storage"))
            .add(
                "events", () -> operations.add("start-events"), () -> operations.add("stop-events"))
            .build();

    coordinator.start();

    assertEquals(LifecycleCoordinator.State.STARTED, coordinator.state());
    assertEquals(Arrays.asList("start-storage", "start-events"), operations);

    coordinator.close();

    assertEquals(LifecycleCoordinator.State.STOPPED, coordinator.state());
    assertEquals(
        Arrays.asList("start-storage", "start-events", "stop-events", "stop-storage"), operations);
  }

  @Test
  public void testRollsBackPartiallyStartedServices() {
    List<String> operations = new ArrayList<>();
    LifecycleCoordinator coordinator =
        LifecycleCoordinator.builder()
            .add(
                "storage",
                () -> operations.add("start-storage"),
                () -> operations.add("stop-storage"))
            .add(
                "events",
                () -> {
                  operations.add("start-events");
                  throw new IllegalStateException("start failed");
                },
                () -> operations.add("stop-events"))
            .add(
                "server", () -> operations.add("start-server"), () -> operations.add("stop-server"))
            .build();

    IllegalStateException failure = assertThrows(IllegalStateException.class, coordinator::start);

    assertEquals("start failed", failure.getMessage());
    assertEquals(LifecycleCoordinator.State.STOPPED, coordinator.state());
    assertEquals(
        Arrays.asList("start-storage", "start-events", "stop-events", "stop-storage"), operations);
  }

  @Test
  public void testShutdownIsIdempotent() throws Exception {
    List<String> operations = new ArrayList<>();
    LifecycleCoordinator coordinator =
        LifecycleCoordinator.builder()
            .add("storage", () -> operations.add("start"), () -> operations.add("stop"))
            .build();

    coordinator.start();
    coordinator.close();
    coordinator.close();

    assertEquals(Arrays.asList("start", "stop"), operations);
  }

  @Test
  public void testContinuesShutdownAndSuppressesLaterFailures() throws Exception {
    LifecycleCoordinator coordinator =
        LifecycleCoordinator.builder()
            .add(
                "storage",
                () -> {},
                () -> {
                  throw new IllegalStateException("storage stop failed");
                })
            .add(
                "events",
                () -> {},
                () -> {
                  throw new IllegalStateException("events stop failed");
                })
            .build();
    coordinator.start();

    Exception failure = assertThrows(Exception.class, coordinator::close);

    assertEquals("Failed to stop lifecycle service: events", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "Failed to stop lifecycle service: storage", failure.getSuppressed()[0].getMessage());
    assertEquals(LifecycleCoordinator.State.STOPPED, coordinator.state());
  }

  @Test
  public void testCannotStartTwice() throws Exception {
    LifecycleCoordinator coordinator = LifecycleCoordinator.builder().build();
    coordinator.start();

    assertThrows(IllegalStateException.class, coordinator::start);
  }
}
