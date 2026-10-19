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

import com.google.common.base.Preconditions;

/**
 * Names a service tests can ask {@link ContainerSuite#getService(ServiceSpec)} for, and the {@link
 * ServiceEndpoint} type it is reached through. Compared by identity: use the constants.
 *
 * @param <E> the endpoint type of the service
 */
public final class ServiceSpec<E extends ServiceEndpoint> {

  /** The shared MySQL 8.0 server. */
  public static final ServiceSpec<DatabaseEndpoint> MYSQL =
      new ServiceSpec<>("mysql", DatabaseEndpoint.class);

  /** The shared PostgreSQL 13 server. */
  public static final ServiceSpec<DatabaseEndpoint> POSTGRESQL =
      new ServiceSpec<>("postgresql", DatabaseEndpoint.class);

  private final String name;
  private final Class<E> endpointType;

  ServiceSpec(String name, Class<E> endpointType) {
    Preconditions.checkArgument(name.matches("[a-z0-9]+"), "Invalid service name: %s", name);
    this.name = name;
    this.endpointType = endpointType;
  }

  /** The service name; also the {@code gravitino.test.<name>.*} prefix of its external settings. */
  public String name() {
    return name;
  }

  /** The endpoint type this service is reached through. */
  public Class<E> endpointType() {
    return endpointType;
  }

  @Override
  public String toString() {
    return name;
  }
}
