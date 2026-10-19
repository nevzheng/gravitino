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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.gravitino.integration.test.container.ExternalDatabase.Engine;
import org.apache.gravitino.integration.test.util.CloseableGroup;

/**
 * Supplies services the environment already runs, such as Kubernetes services or CI service
 * containers, named by {@code gravitino.test.<service>.*} system properties. A service whose
 * properties are not set is left to the next provider.
 */
final class ExternalServiceProvider implements ServiceProvider {

  /** Builds each service's external endpoint from properties; empty when they are not set. */
  private static final Map<ServiceSpec<?>, Function<Function<String, String>, Optional<?>>>
      FACTORIES =
          ImmutableMap.of(
              ServiceSpec.MYSQL, properties -> ExternalDatabase.from(Engine.MYSQL, properties),
              ServiceSpec.POSTGRESQL,
                  properties -> ExternalDatabase.from(Engine.POSTGRESQL, properties));

  private final Function<String, String> properties;
  private final CloseableGroup closer;

  ExternalServiceProvider(Function<String, String> properties, CloseableGroup closer) {
    this.properties = properties;
    this.closer = closer;
  }

  @Override
  public String name() {
    return "external";
  }

  @Override
  public <E extends ServiceEndpoint> Optional<E> provide(ServiceSpec<E> spec) {
    Function<Function<String, String>, Optional<?>> factory = FACTORIES.get(spec);
    if (factory == null) {
      return Optional.empty();
    }
    Optional<E> endpoint = factory.apply(properties).map(spec.endpointType()::cast);
    endpoint
        .filter(AutoCloseable.class::isInstance)
        .map(AutoCloseable.class::cast)
        .ifPresent(closer::register);
    return endpoint;
  }
}
