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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and closes the {@link ManagedService managed services} of one component.
 *
 * <p>Each provider that builds an object hands it to {@link #register(Object)} as it returns it.
 * The registry keeps only the objects that implement {@link ManagedService}. Dagger builds the
 * dependencies of an object before the object itself, so registration order is a topological order
 * of the graph: {@link #start()} starts the services in that order and {@link #close()} closes them
 * in reverse, so a service never outlives a service it depends on.
 *
 * <p>The registry never builds anything. It starts and closes exactly the services the component
 * has built, which differ by initialization profile and stop short when an initialization fails
 * part way.
 *
 * <ul>
 *   <li>A service that fails to start rolls back the start: the services already started are closed
 *       in reverse, and the failure is rethrown.
 *   <li>A service registered after {@link #start()} is started when it is registered.
 *   <li>{@link #close()} closes every registered service, started or not, since each holds
 *       resources from the moment it is built. A failure to close is logged and does not stop the
 *       others; the first is rethrown afterwards with the others suppressed. Closing again does
 *       nothing.
 * </ul>
 *
 * <p>Each start and close is logged with the time it took.
 */
@Singleton
public final class ManagedServices implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ManagedServices.class);

  /** The state of the registry. */
  public enum State {
    /** Services can register; none has been started. */
    NEW,
    /** {@link #start()} is running. */
    STARTING,
    /** Every registered service has been started. */
    STARTED,
    /** The registry was closed or failed to start; it accepts no more services. */
    CLOSED
  }

  private final List<ManagedService> registered = new ArrayList<>();
  // The services to close, in the order they were registered; a service leaves it once closed.
  private final List<ManagedService> open = new ArrayList<>();
  private State state = State.NEW;

  /** Creates an empty registry. */
  @Inject
  public ManagedServices() {}

  /**
   * Records {@code object} if it is a {@link ManagedService}, and returns it. A provider calls this
   * on the object it builds, so it can return the result.
   *
   * <p>While the registry is starting or started, the service is started here.
   *
   * @param object the object a provider built
   * @param <T> the type of the object
   * @return {@code object}
   * @throws IllegalStateException if the registry is closed
   * @throws ManagedServiceException if a service registered after start fails to start; it is
   *     closed before this is thrown
   */
  public synchronized <T> T register(T object) {
    Preconditions.checkNotNull(object, "object");
    if (!(object instanceof ManagedService)) {
      return object;
    }
    Preconditions.checkState(
        state != State.CLOSED, "Cannot register a service with a closed registry");
    ManagedService service = (ManagedService) object;
    if (registered.contains(service)) {
      return object;
    }
    registered.add(service);
    open.add(service);
    if (state == State.STARTED) {
      try {
        startService(service);
      } catch (Exception e) {
        closeService(service, e);
        throw new ManagedServiceException("Failed to start " + service.name(), e);
      }
    }
    return object;
  }

  /**
   * Starts every registered service in registration order. A service that registers while this
   * runs, because a service built it as it started, is started in turn.
   *
   * @throws IllegalStateException if the registry was already started or closed
   * @throws Exception the failure of the first service that failed to start, after the services
   *     already started are closed in reverse
   */
  public synchronized void start() throws Exception {
    Preconditions.checkState(state == State.NEW, "Cannot start services from state %s", state);
    state = State.STARTING;
    List<ManagedService> started = new ArrayList<>();
    // Index loop: a service may register another one while it starts.
    for (int i = 0; i < registered.size(); i++) {
      ManagedService service = registered.get(i);
      try {
        startService(service);
      } catch (Exception e) {
        state = State.CLOSED;
        for (int j = started.size() - 1; j >= 0; j--) {
          closeService(started.get(j), e);
        }
        throw e;
      }
      started.add(service);
    }
    state = State.STARTED;
  }

  /**
   * Closes every registered service that is still open, in reverse registration order.
   *
   * @throws Exception the first failure to close, with the later ones suppressed
   */
  @Override
  public synchronized void close() throws Exception {
    state = State.CLOSED;
    Exception failure = null;
    for (int i = open.size() - 1; i >= 0; i--) {
      ManagedService service = open.get(i);
      try {
        closeTimed(service);
      } catch (Exception e) {
        LOG.warn("Failed to close {}", service.name(), e);
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    open.clear();
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Returns the state of the registry.
   *
   * @return the state
   */
  public synchronized State state() {
    return state;
  }

  /**
   * Returns the registered services in registration order.
   *
   * @return the registered services
   */
  @VisibleForTesting
  public synchronized List<ManagedService> services() {
    return ImmutableList.copyOf(registered);
  }

  private void startService(ManagedService service) throws Exception {
    long begin = System.nanoTime();
    service.start();
    LOG.info("Started {} in {} ms", service.name(), elapsedMillis(begin));
  }

  /** Closes a service as part of a failed start, attaching any failure to {@code cause}. */
  private void closeService(ManagedService service, Exception cause) {
    if (!open.remove(service)) {
      return;
    }
    try {
      closeTimed(service);
    } catch (Exception e) {
      LOG.warn("Failed to close {}", service.name(), e);
      cause.addSuppressed(e);
    }
  }

  private void closeTimed(ManagedService service) throws Exception {
    open.remove(service);
    long begin = System.nanoTime();
    service.close();
    LOG.info("Closed {} in {} ms", service.name(), elapsedMillis(begin));
  }

  private static long elapsedMillis(long beginNanos) {
    return (System.nanoTime() - beginNanos) / 1_000_000L;
  }

  /** Thrown when a service registered after the registry started fails to start. */
  public static final class ManagedServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ManagedServiceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
