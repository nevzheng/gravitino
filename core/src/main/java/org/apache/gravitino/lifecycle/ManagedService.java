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

/**
 * A service whose lifetime the {@link ManagedServices} registry of its component owns.
 *
 * <p>A class opts in by implementing this interface; the registry ignores every other object it is
 * handed. The registry starts managed services in the order the component built them, so a service
 * starts after the services it depends on, and closes them in the reverse order. There is no order
 * metadata to keep: the dependency graph is the order.
 *
 * <p>The constructor builds a service and {@link #start()} puts it to work. A constructor should
 * not start threads or open ports, so a graph that cannot be built fails before anything goes live,
 * and a test can build a service without starting it. {@link #close()} releases what the service
 * holds, whether or not it was started.
 */
public interface ManagedService extends AutoCloseable {

  /**
   * Puts the service to work, for example by starting its threads. The default does nothing, for a
   * service that only holds resources to release.
   *
   * @throws Exception if the service cannot start; the registry then closes the services it has
   *     already started
   */
  default void start() throws Exception {}

  /**
   * Returns the name the registry logs this service under.
   *
   * @return the name of the service; the simple name of its class by default
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * Returns a managed service that only closes {@code resource}, for an object whose class does not
   * implement this interface.
   *
   * @param name the name to log the service under
   * @param resource the resource to close
   * @return a managed service that closes {@code resource}
   */
  static ManagedService closing(String name, AutoCloseable resource) {
    return new ManagedService() {
      @Override
      public void close() throws Exception {
        resource.close();
      }

      @Override
      public String name() {
        return name;
      }
    };
  }
}
