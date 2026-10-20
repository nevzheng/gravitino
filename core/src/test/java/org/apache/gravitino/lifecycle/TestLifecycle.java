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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestLifecycle {

  private final List<String> events = new ArrayList<>();

  @Test
  public void testStartsInOrderAndStopsInReverse() throws Exception {
    Lifecycle lifecycle =
        Lifecycle.builder().add("a", start("a"), stop("a")).add("b", start("b"), stop("b")).build();

    lifecycle.start();
    assertEquals(Lifecycle.State.STARTED, lifecycle.state());
    lifecycle.close();

    assertEquals(List.of("start a", "start b", "stop b", "stop a"), events);
    assertEquals(Lifecycle.State.CLOSED, lifecycle.state());
  }

  @Test
  public void testStartFailureRollsBackStartedServices() {
    IllegalStateException failure = new IllegalStateException("b failed");
    Lifecycle lifecycle =
        Lifecycle.builder()
            .add("a", start("a"), stop("a"))
            .add(
                "b",
                () -> {
                  throw failure;
                },
                stop("b"))
            .add("c", start("c"), stop("c"))
            .build();

    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
    assertEquals(List.of("start a", "stop a"), events);
    assertEquals(Lifecycle.State.CLOSED, lifecycle.state());
  }

  @Test
  public void testRollbackFailureIsSuppressedOnStartFailure() {
    IllegalStateException startFailure = new IllegalStateException("b failed");
    Lifecycle lifecycle =
        Lifecycle.builder()
            .add(
                "a",
                start("a"),
                () -> {
                  throw new IllegalArgumentException("a stop failed");
                })
            .add(
                "b",
                () -> {
                  throw startFailure;
                },
                stop("b"))
            .build();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    assertSame(startFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("Failed to stop a", thrown.getSuppressed()[0].getMessage());
  }

  @Test
  public void testCloseKeepsGoingAndAggregatesFailures() throws Exception {
    IllegalStateException bFailure = new IllegalStateException("b stop failed");
    IllegalStateException cFailure = new IllegalStateException("c stop failed");
    Lifecycle lifecycle =
        Lifecycle.builder()
            .add("a", start("a"), stop("a"))
            .add(
                "b",
                start("b"),
                () -> {
                  throw bFailure;
                })
            .add(
                "c",
                start("c"),
                () -> {
                  throw cFailure;
                })
            .build();
    lifecycle.start();
    events.clear();

    Exception thrown = assertThrows(Exception.class, lifecycle::close);

    assertEquals("Failed to stop c", thrown.getMessage());
    assertSame(cFailure, thrown.getCause());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(bFailure, thrown.getSuppressed()[0].getCause());
    assertEquals(List.of("stop a"), events);
  }

  @Test
  public void testCloseIsIdempotent() throws Exception {
    Lifecycle lifecycle = Lifecycle.builder().add("a", start("a"), stop("a")).build();
    lifecycle.start();

    lifecycle.close();
    lifecycle.close();

    assertEquals(List.of("start a", "stop a"), events);
  }

  @Test
  public void testCloseBeforeStartStopsNothing() throws Exception {
    Lifecycle lifecycle = Lifecycle.builder().add("a", start("a"), stop("a")).build();

    lifecycle.close();

    assertEquals(List.of(), events);
    assertThrows(IllegalStateException.class, lifecycle::start);
  }

  @Test
  public void testStopOnlyService() throws Exception {
    Lifecycle lifecycle = Lifecycle.builder().addStopOnly("a", stop("a")).build();

    lifecycle.start();
    lifecycle.close();

    assertEquals(List.of("stop a"), events);
  }

  private Lifecycle.Action start(String name) {
    return () -> events.add("start " + name);
  }

  private Lifecycle.Action stop(String name) {
    return () -> events.add("stop " + name);
  }
}
