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

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Coordinates ordered startup and reverse-order shutdown for an application object graph.
 *
 * <p>Dependency injection constructs objects but does not manage their lifecycle. This class keeps
 * lifecycle ordering explicit and independent of a dependency injection framework. When startup
 * fails, it attempts to stop the failing service and every previously started service before
 * propagating the startup failure.
 */
public final class LifecycleCoordinator implements AutoCloseable {

  /** The state of this coordinator. */
  public enum State {
    /** No lifecycle action has run. */
    NEW,
    /** Lifecycle actions are starting. */
    STARTING,
    /** All lifecycle actions started successfully. */
    STARTED,
    /** Lifecycle actions are stopping. */
    STOPPING,
    /** Lifecycle actions have stopped or startup rollback has completed. */
    STOPPED
  }

  /** An operation that may throw while starting or stopping a service. */
  @FunctionalInterface
  public interface Action {

    /** Executes the lifecycle operation. */
    void run() throws Exception;
  }

  /** Builder for an explicitly ordered lifecycle plan. */
  public static final class Builder {

    private final List<LifecycleEntry> entries = new ArrayList<>();

    private Builder() {}

    /**
     * Appends a service to the lifecycle plan.
     *
     * <p>Services start in insertion order and stop in reverse insertion order.
     *
     * @param name human-readable service name used in diagnostics
     * @param start operation that initializes or starts the service
     * @param stop operation that stops or closes the service
     * @return this builder
     */
    public Builder add(String name, Action start, Action stop) {
      entries.add(new LifecycleEntry(name, start, stop));
      return this;
    }

    /**
     * Builds an independent coordinator from the current plan.
     *
     * @return a new lifecycle coordinator
     */
    public LifecycleCoordinator build() {
      return new LifecycleCoordinator(new ArrayList<>(entries));
    }
  }

  private final List<LifecycleEntry> entries;
  private final Deque<LifecycleEntry> activeEntries = new ArrayDeque<>();

  private State state = State.NEW;

  private LifecycleCoordinator(List<LifecycleEntry> entries) {
    this.entries = entries;
  }

  /**
   * Creates a lifecycle-plan builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Starts every registered service in insertion order.
   *
   * <p>If a service fails to start, shutdown is attempted for that service and every previously
   * started service. Shutdown failures are attached to the startup failure as suppressed
   * exceptions.
   *
   * @throws Exception if startup or rollback fails
   */
  public synchronized void start() throws Exception {
    Preconditions.checkState(state == State.NEW, "Lifecycle has already started");
    state = State.STARTING;

    try {
      for (LifecycleEntry entry : entries) {
        activeEntries.push(entry);
        entry.start.run();
      }
      state = State.STARTED;
    } catch (Exception startFailure) {
      Exception stopFailure = stopActiveEntries();
      state = State.STOPPED;
      if (stopFailure != null) {
        startFailure.addSuppressed(stopFailure);
      }
      throw startFailure;
    }
  }

  /**
   * Returns the current lifecycle state.
   *
   * @return the current state
   */
  public synchronized State state() {
    return state;
  }

  /**
   * Stops every active service in reverse startup order.
   *
   * <p>Repeated calls after shutdown are no-ops. If one stop operation fails, the remaining
   * services are still stopped and subsequent failures are suppressed onto the first failure.
   *
   * @throws Exception if any stop operation fails
   */
  @Override
  public synchronized void close() throws Exception {
    if (state == State.STOPPED) {
      return;
    }

    Preconditions.checkState(
        state == State.NEW || state == State.STARTED,
        "Cannot stop lifecycle while in state %s",
        state);
    state = State.STOPPING;
    Exception stopFailure = stopActiveEntries();
    state = State.STOPPED;
    if (stopFailure != null) {
      throw stopFailure;
    }
  }

  private Exception stopActiveEntries() {
    Exception firstFailure = null;
    while (!activeEntries.isEmpty()) {
      LifecycleEntry entry = activeEntries.pop();
      try {
        entry.stop.run();
      } catch (Exception stopFailure) {
        Exception namedFailure =
            new IllegalStateException(
                "Failed to stop lifecycle service: " + entry.name, stopFailure);
        if (firstFailure == null) {
          firstFailure = namedFailure;
        } else {
          firstFailure.addSuppressed(namedFailure);
        }
      }
    }
    return firstFailure;
  }

  private static final class LifecycleEntry {

    private final String name;
    private final Action start;
    private final Action stop;

    private LifecycleEntry(String name, Action start, Action stop) {
      this.name = Preconditions.checkNotNull(name, "name");
      this.start = Preconditions.checkNotNull(start, "start");
      this.stop = Preconditions.checkNotNull(stop, "stop");
    }
  }
}
