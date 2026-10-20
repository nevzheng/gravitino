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
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a fixed list of services in order and stops them in reverse order.
 *
 * <p>Dependency injection builds objects but does not decide when they start or stop. This class
 * makes that order explicit and keeps it in one place:
 *
 * <ul>
 *   <li>{@link #start()} starts each service in the order it was added. If one fails, the services
 *       already started are stopped in reverse order and the start failure is rethrown.
 *   <li>{@link #close()} stops every started service in reverse order. It keeps going when a stop
 *       fails, and throws the first failure with the others attached as suppressed exceptions.
 *   <li>{@link #close()} is idempotent, and closing a lifecycle that never started does nothing,
 *       unless it was built with {@link Builder#alwaysStop()}.
 * </ul>
 *
 * <p>A lifecycle built with {@link Builder#alwaysStop()} is for services that hold resources from
 * the moment they are built rather than from the moment they start. It stops every service, started
 * or not, when it closes or when a start fails.
 */
public final class Lifecycle implements AutoCloseable {

  /** An action that starts or stops a service. */
  @FunctionalInterface
  public interface Action {
    /**
     * Runs the action.
     *
     * @throws Exception if the action fails.
     */
    void run() throws Exception;
  }

  /** The state of a {@link Lifecycle}. */
  public enum State {
    /** Created but not started. */
    NEW,
    /** {@link #start()} completed. */
    STARTED,
    /** {@link #close()} was called, or {@link #start()} failed and rolled back. */
    CLOSED
  }

  private final List<Service> services;
  private final boolean alwaysStop;
  private final List<Service> started = new ArrayList<>();
  private State state = State.NEW;

  private Lifecycle(List<Service> services, boolean alwaysStop) {
    this.services = services;
    this.alwaysStop = alwaysStop;
  }

  /**
   * Creates a builder.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the current state.
   *
   * @return the current state.
   */
  public synchronized State state() {
    return state;
  }

  /**
   * Starts every service in the order it was added.
   *
   * <p>If a service fails to start, the services already started are stopped in reverse order; with
   * {@link Builder#alwaysStop()}, every service is stopped in reverse order.
   *
   * @throws Exception the failure of the service that could not start, with any rollback failures
   *     attached as suppressed exceptions.
   */
  public synchronized void start() throws Exception {
    Preconditions.checkState(state == State.NEW, "Lifecycle cannot start from state %s", state);
    for (Service service : services) {
      try {
        service.start.run();
      } catch (Exception e) {
        Exception rollbackFailure = stopStarted();
        if (rollbackFailure != null) {
          e.addSuppressed(rollbackFailure);
        }
        state = State.CLOSED;
        throw e;
      }
      started.add(service);
    }
    state = State.STARTED;
  }

  /**
   * Stops every started service in reverse order; with {@link Builder#alwaysStop()}, every service.
   *
   * @throws Exception the first stop failure, with later failures attached as suppressed.
   */
  @Override
  public synchronized void close() throws Exception {
    if (state == State.CLOSED) {
      return;
    }
    state = State.CLOSED;
    Exception failure = stopStarted();
    if (failure != null) {
      throw failure;
    }
  }

  private Exception stopStarted() {
    List<Service> toStop = alwaysStop ? services : started;
    Exception first = null;
    for (int i = toStop.size() - 1; i >= 0; i--) {
      Service service = toStop.get(i);
      try {
        service.stop.run();
      } catch (Exception e) {
        Exception wrapped = new LifecycleException("Failed to stop " + service.name, e);
        if (first == null) {
          first = wrapped;
        } else {
          first.addSuppressed(wrapped);
        }
      }
    }
    started.clear();
    return first;
  }

  /** Builds a {@link Lifecycle}. */
  public static final class Builder {
    private final ImmutableList.Builder<Service> services = ImmutableList.builder();
    private boolean alwaysStop;

    private Builder() {}

    /**
     * Adds a service. Services start in the order they are added and stop in reverse.
     *
     * @param name a name used in error messages.
     * @param start the action that starts the service.
     * @param stop the action that stops the service.
     * @return this builder.
     */
    public Builder add(String name, Action start, Action stop) {
      Preconditions.checkArgument(name != null && !name.isEmpty(), "name must not be empty");
      Preconditions.checkArgument(start != null, "start must not be null");
      Preconditions.checkArgument(stop != null, "stop must not be null");
      services.add(new Service(name, start, stop));
      return this;
    }

    /**
     * Adds a service that only needs to be stopped.
     *
     * @param name a name used in error messages.
     * @param stop the action that stops the service.
     * @return this builder.
     */
    public Builder addStopOnly(String name, Action stop) {
      return add(name, () -> {}, stop);
    }

    /**
     * Makes the lifecycle stop every service, whether or not it was started, when it closes or when
     * a start fails. Use it when the services hold resources from the moment they are built, so the
     * resources are released even if {@link Lifecycle#start()} never ran or failed. Every stop
     * action must then be safe to run on a service that was not started.
     *
     * @return this builder.
     */
    public Builder alwaysStop() {
      this.alwaysStop = true;
      return this;
    }

    /**
     * Builds the lifecycle.
     *
     * @return a new lifecycle in state {@link State#NEW}.
     */
    public Lifecycle build() {
      return new Lifecycle(services.build(), alwaysStop);
    }
  }

  /** Thrown when a service fails to stop. */
  public static final class LifecycleException extends Exception {
    private static final long serialVersionUID = 1L;

    LifecycleException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class Service {
    private final String name;
    private final Action start;
    private final Action stop;

    private Service(String name, Action start, Action stop) {
      this.name = name;
      this.start = start;
      this.stop = stop;
    }
  }
}
