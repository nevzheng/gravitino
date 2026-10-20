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
package org.apache.gravitino.di;

/**
 * A service that a downstream component adds to the {@link GravitinoComponent#lifecycle()
 * lifecycle}. A module contributes one with {@code @Provides @IntoSet}.
 *
 * <p>The lifecycle starts these services after every service of the component, in ascending {@link
 * #order()}, and stops them in reverse, before any service of the component stops. Like the
 * component's own services, each is stopped when the lifecycle closes even if it never started, so
 * {@link #stop()} must be safe to call on a service that did not start. Two services with the same
 * order are an error when the lifecycle is built.
 *
 * <p>Upstream Gravitino contributes none.
 */
public interface ManagedService {

  /**
   * Returns where this service starts among the managed services. A lower order starts first and
   * stops last.
   *
   * @return the order, unique among the managed services of one component
   */
  int order();

  /**
   * Returns the name of the service, used in log and error messages.
   *
   * @return the name
   */
  String name();

  /**
   * Starts the service. Does nothing by default, for a service that is ready once it is built.
   *
   * @throws Exception if the service fails to start
   */
  default void start() throws Exception {}

  /**
   * Stops the service and releases what it holds. Called once, whether or not {@link #start()} ran.
   *
   * @throws Exception if the service fails to stop
   */
  void stop() throws Exception;
}
