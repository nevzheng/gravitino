/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.server.web.rest.v1.support;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.ws.rs.core.HttpHeaders;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;

/**
 * Executes a V1 mutation only when its strong representation validator still identifies the current
 * resource state.
 *
 * <p>The Gravitino tree-lock root is deliberately the conditional-mutation boundary. Existing
 * metadata dispatchers acquire compatible, reentrant tree locks for legacy and V1 writes; holding
 * the root write lock therefore closes the gap between loading a representation, validating its
 * ETag, and invoking the dispatcher. The boundary is local to one Gravitino server process. A
 * distributed CAS token remains a storage/provider capability for a future HA contract.
 */
public final class V1ConditionalMutation {

  private V1ConditionalMutation() {}

  /**
   * Loads, validates, and mutates one V1 resource while holding the metadata mutation boundary.
   *
   * @param headers HTTP request headers containing the required {@code If-Match} value.
   * @param currentResourceLoader loads the current internal resource representation.
   * @param publicResourceMapper maps that resource to its canonical V1 public representation.
   * @param mutation applies the requested mutation after the validator is satisfied.
   * @param <T> internal current resource type.
   * @param <R> mutation result type.
   * @return the mutation result.
   */
  public static <T, R> R execute(
      HttpHeaders headers,
      Supplier<T> currentResourceLoader,
      Function<T, Object> publicResourceMapper,
      Supplier<R> mutation) {
    Objects.requireNonNull(mutation, "mutation cannot be null");
    return execute(headers, currentResourceLoader, publicResourceMapper, current -> mutation.get());
  }

  /**
   * Loads, validates, and mutates one V1 resource while holding the metadata mutation boundary.
   *
   * <p>The mutation receives the exact internal representation whose public form satisfied {@code
   * If-Match}. Use it to calculate a full desired-state delta without loading a second, potentially
   * stale resource.
   *
   * @param headers HTTP request headers containing the required {@code If-Match} value.
   * @param currentResourceLoader loads the current internal resource representation.
   * @param publicResourceMapper maps that resource to its canonical V1 public representation.
   * @param mutation applies the requested mutation using the validated current resource.
   * @param <T> internal current resource type.
   * @param <R> mutation result type.
   * @return the mutation result.
   */
  public static <T, R> R execute(
      HttpHeaders headers,
      Supplier<T> currentResourceLoader,
      Function<T, Object> publicResourceMapper,
      Function<T, R> mutation) {
    Objects.requireNonNull(headers, "headers cannot be null");
    Objects.requireNonNull(currentResourceLoader, "currentResourceLoader cannot be null");
    Objects.requireNonNull(publicResourceMapper, "publicResourceMapper cannot be null");
    Objects.requireNonNull(mutation, "mutation cannot be null");

    return TreeLockUtils.doWithRootTreeLock(
        LockType.WRITE,
        () -> {
          T current = currentResourceLoader.get();
          Object publicResource =
              Objects.requireNonNull(
                  publicResourceMapper.apply(current), "publicResourceMapper cannot return null");
          V1ResourceSupport.requireIfMatch(headers, V1ResourceSupport.entityTag(publicResource));
          return mutation.apply(current);
        });
  }
}
