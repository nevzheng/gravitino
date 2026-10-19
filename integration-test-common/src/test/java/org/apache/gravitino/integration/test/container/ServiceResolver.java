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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asks {@link ServiceProvider}s in order for a service and keeps the first answer per spec. */
final class ServiceResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ServiceResolver.class);

  private final List<ServiceProvider> providers;
  private final Map<ServiceSpec<?>, ServiceEndpoint> endpoints = new ConcurrentHashMap<>();

  ServiceResolver(List<ServiceProvider> providers) {
    this.providers = List.copyOf(providers);
  }

  /** Returns the endpoint of {@code spec} from the first provider that supplies it. */
  <E extends ServiceEndpoint> E resolve(ServiceSpec<E> spec) {
    return spec.endpointType().cast(endpoints.computeIfAbsent(spec, s -> provide(spec)));
  }

  /** Forgets every endpoint, so the next {@link #resolve} asks the providers again. */
  void clear() {
    endpoints.clear();
  }

  private <E extends ServiceEndpoint> E provide(ServiceSpec<E> spec) {
    for (ServiceProvider provider : providers) {
      Optional<E> endpoint = provider.provide(spec);
      if (endpoint.isPresent()) {
        LOG.info("[TEST-SERVICE-SOURCE] service={} provider={}", spec, provider.name());
        return endpoint.get();
      }
    }
    throw new IllegalStateException("No provider supplies the test service " + spec);
  }
}
