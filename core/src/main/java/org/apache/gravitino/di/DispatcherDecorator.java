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

import org.apache.gravitino.catalog.ForwardingTableDispatcher;

/**
 * Wraps one {@link Layer layer} of a dispatcher chain of {@link GravitinoComponent}. This is the
 * seam a downstream component uses to change a chain without binding the chain's type a second
 * time, which Dagger rejects as a duplicate binding.
 *
 * <p>Each chain has its own map of decorators, keyed by layer. A module contributes one with
 * {@code @Provides @IntoMap}, a {@link LayerKey} and the chain's qualifier; see {@link Layer}.
 *
 * <p>A decorator must delegate: the dispatcher it returns passes each call on to the dispatcher it
 * is given, unless it rejects the call. It must not replace the chain with a dispatcher of its own
 * that bypasses the one it is given. For the schema, table, fileset, topic, model and view chains,
 * a decorator can extend the matching forwarding class, such as {@link ForwardingTableDispatcher},
 * which forwards every method, default methods included, and override only what it changes. Under
 * an {@link Layer#EVENT} or {@link Layer#EXTENSION} decorator of a {@link Public} chain lies the
 * hook layer, which keeps authorization in step with metadata changes: it sets the owner of a new
 * object, applies future grants, tells the catalogs' authorization plugins of changes, and
 * refreshes the authorizer's caches after a role, privilege or role binding changes. A replacing
 * decorator drops the hook and event layers, so, for example, a user whose role is revoked keeps
 * the access the authorizer has cached, and no audit event is sent. Nothing enforces this contract;
 * a decorator that breaks it still builds.
 *
 * <p>Upstream Gravitino contributes no decorators, so every chain is exactly the chain its module
 * builds.
 *
 * @param <T> the dispatcher type of the chain
 */
@FunctionalInterface
public interface DispatcherDecorator<T> {

  /**
   * Wraps a dispatcher.
   *
   * @param dispatcher the chain built so far: the layers under this decorator's position
   * @return the dispatcher that takes its place and delegates to it, never null
   */
  T decorate(T dispatcher);
}
