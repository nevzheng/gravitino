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

import java.util.function.UnaryOperator;

/**
 * Wraps a dispatcher chain of {@link GravitinoComponent}. This is the seam a downstream component
 * uses to change a chain without binding the chain's type a second time, which Dagger rejects as a
 * duplicate binding.
 *
 * <p>Each chain has its own set of decorators. A module contributes one with
 * {@code @Provides @IntoSet} and the chain's qualifier:
 *
 * <pre>{@code
 * @Provides
 * @IntoSet
 * @Public
 * static DispatcherDecorator<TableDispatcher> auditedTables(AuditTrail trail) {
 *   return DispatcherDecorator.of(100, tables -> new AuditedTableDispatcher(tables, trail));
 * }
 * }</pre>
 *
 * <p>The decorators of a chain wrap its outermost layer: the event layer of a {@link Public} chain,
 * the normalize layer (or the manager, for chains without one) of an {@link Internal} chain. They
 * are applied in ascending {@link #order()}: the lowest order wraps the chain first, so the highest
 * order is the outermost object, the one the chain's binding returns. Two decorators of one chain
 * with the same order are an error when the chain is built. Where a {@link Public} chain shares
 * layers with the {@link Internal} chain of the same type, it shares the undecorated layers, so the
 * decorators of one chain never appear in the other.
 *
 * <p>A decorator must delegate: the dispatcher it returns passes each call on to the dispatcher it
 * is given, unless it rejects the call. It must not replace the chain with a dispatcher of its own
 * that bypasses the one it is given. The layers under a {@link Public} decorator include the hook
 * layer, which keeps authorization in step with metadata changes: it sets the owner of a new
 * object, applies future grants, tells the catalogs' authorization plugins of changes, and
 * refreshes the authorizer's caches after a role, privilege or role binding changes. A replacing
 * decorator drops the hook and event layers, so, for example, a user whose role is revoked keeps
 * the access the authorizer has cached, and no audit event is sent. An {@link Internal} chain has
 * no hook layer, but authorization checks and authorization plugins read it. Nothing enforces this
 * contract; a decorator that breaks it still builds.
 *
 * <p>Upstream Gravitino contributes no decorators, so every chain is exactly the chain its module
 * builds.
 *
 * @param <T> the dispatcher type of the chain
 */
public interface DispatcherDecorator<T> {

  /**
   * Returns where this decorator sits in its chain. Decorators with a lower order wrap the chain
   * first; the decorator with the highest order is outermost.
   *
   * @return the order, unique among the decorators of one chain
   */
  int order();

  /**
   * Wraps a dispatcher.
   *
   * @param dispatcher the chain built so far: the chain's module layers and the decorators with a
   *     lower order
   * @return the dispatcher that takes its place and delegates to it, never null
   */
  T decorate(T dispatcher);

  /**
   * Returns a decorator with the given order that applies a function.
   *
   * @param order the order of the decorator
   * @param decorator the function that wraps a dispatcher
   * @param <T> the dispatcher type of the chain
   * @return the decorator
   */
  static <T> DispatcherDecorator<T> of(int order, UnaryOperator<T> decorator) {
    return new DispatcherDecorator<T>() {
      @Override
      public int order() {
        return order;
      }

      @Override
      public T decorate(T dispatcher) {
        return decorator.apply(dispatcher);
      }
    };
  }
}
