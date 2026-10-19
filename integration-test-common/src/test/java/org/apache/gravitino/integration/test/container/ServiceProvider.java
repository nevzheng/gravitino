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
package org.apache.gravitino.integration.test.container;

import java.util.Optional;

/**
 * Supplies services to tests. {@link ContainerSuite} asks its providers in order, and the first one
 * that supplies a service wins: providers found through {@link java.util.ServiceLoader}, then
 * {@link ExternalServiceProvider}, then {@link ContainerServiceProvider}.
 *
 * <p>To supply services from another environment, such as pods a Kubernetes operator creates,
 * implement this interface and list the class in {@code
 * META-INF/services/org.apache.gravitino.integration.test.container.ServiceProvider} on the test
 * classpath.
 */
public interface ServiceProvider {

  /** A short name for logs, such as {@code container}. */
  String name();

  /**
   * Returns the endpoint of {@code spec}, ready to use, or empty when this provider does not supply
   * it. Called at most once per spec until {@link ContainerSuite} closes.
   */
  <E extends ServiceEndpoint> Optional<E> provide(ServiceSpec<E> spec);
}
