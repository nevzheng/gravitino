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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Applies the {@link DispatcherDecorator decorators} of a chain. */
final class Decorators {

  private Decorators() {}

  /**
   * Wraps a chain in its decorators, in ascending order.
   *
   * @param chain the chain, for error messages, for example {@code "@Public TableDispatcher"}
   * @param dispatcher the outermost layer of the chain as its module builds it
   * @param decorators the decorators of the chain
   * @param <T> the dispatcher type of the chain
   * @return the decorated chain, or {@code dispatcher} itself when there are no decorators
   * @throws IllegalStateException if two decorators have the same order or one returns null
   */
  static <T> T apply(String chain, T dispatcher, Set<DispatcherDecorator<T>> decorators) {
    if (decorators.isEmpty()) {
      return dispatcher;
    }
    List<DispatcherDecorator<T>> ordered = new ArrayList<>(decorators);
    ordered.sort(Comparator.comparingInt(DispatcherDecorator::order));
    for (int i = 1; i < ordered.size(); i++) {
      Preconditions.checkState(
          ordered.get(i - 1).order() != ordered.get(i).order(),
          "Two decorators of %s have order %s",
          chain,
          ordered.get(i).order());
    }
    T decorated = dispatcher;
    for (DispatcherDecorator<T> decorator : ordered) {
      decorated = decorator.decorate(decorated);
      Preconditions.checkState(
          decorated != null,
          "A decorator of %s with order %s returned null",
          chain,
          decorator.order());
    }
    return decorated;
  }
}
