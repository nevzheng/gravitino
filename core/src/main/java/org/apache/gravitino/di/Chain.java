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
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * Assembles one dispatcher chain from its upstream layers and its {@link DispatcherDecorator
 * decorators}. Each module builds every chain it binds with one:
 *
 * <pre>{@code
 * return Chain.of("@Public SchemaDispatcher", operations, decorators)
 *     .hook(d -> new SchemaHookDispatcher(d, lockManager))
 *     .normalize(d -> new SchemaNormalizeDispatcher(d, catalogManager))
 *     .event(d -> new SchemaEventDispatcher(eventBus, d))
 *     .build();
 * }</pre>
 *
 * <p>Layers are added from the innermost to the outermost, in {@link Layer} order. After each
 * position, whether or not the chain has an upstream layer there, the decorator keyed with that
 * position wraps what was built so far.
 *
 * @param <T> the dispatcher type of the chain
 */
final class Chain<T> {

  private final String name;
  private final Map<Layer, DispatcherDecorator<T>> decorators;
  private T top;
  // The positions up to and including this one are built.
  private Layer last;

  private Chain(String name, T bottom, Map<Layer, DispatcherDecorator<T>> decorators) {
    this.name = name;
    this.decorators = decorators;
    this.top = Preconditions.checkNotNull(bottom, "The bottom of %s is null", name);
    this.last = Layer.OPERATION;
    decorate(Layer.OPERATION);
  }

  /**
   * Starts a chain at its operation dispatcher or manager.
   *
   * @param name the chain, for error messages, for example {@code "@Public TableDispatcher"}
   * @param bottom the operation dispatcher or manager at the bottom of the chain
   * @param decorators the decorators of the chain, by layer
   * @param <T> the dispatcher type of the chain
   * @return the chain, with the {@link Layer#OPERATION} decorator applied
   */
  static <T> Chain<T> of(String name, T bottom, Map<Layer, DispatcherDecorator<T>> decorators) {
    return new Chain<>(name, bottom, decorators);
  }

  /** Adds the hook layer. */
  Chain<T> hook(UnaryOperator<T> layer) {
    return layer(Layer.HOOK, layer);
  }

  /** Adds the normalize layer. */
  Chain<T> normalize(UnaryOperator<T> layer) {
    return layer(Layer.NORMALIZE, layer);
  }

  /** Adds the event layer. */
  Chain<T> event(UnaryOperator<T> layer) {
    return layer(Layer.EVENT, layer);
  }

  /**
   * Applies the decorators of the remaining positions, up to {@link Layer#EXTENSION}.
   *
   * @return the outermost object of the chain
   */
  T build() {
    decorateAfter(last, null);
    last = Layer.EXTENSION;
    return top;
  }

  private Chain<T> layer(Layer layer, UnaryOperator<T> wrap) {
    Preconditions.checkState(
        layer.compareTo(last) > 0, "%s: layer %s is added after %s", name, layer, last);
    // Positions this chain has no layer at still take their decorators.
    decorateAfter(last, layer);
    top = Preconditions.checkNotNull(wrap.apply(top), "%s: layer %s is null", name, layer);
    last = layer;
    decorate(layer);
    return this;
  }

  /** Applies the decorators of the positions after {@code from} and before {@code to}, if any. */
  private void decorateAfter(Layer from, @Nullable Layer to) {
    for (Layer layer : Layer.values()) {
      if (layer.compareTo(from) > 0 && (to == null || layer.compareTo(to) < 0)) {
        decorate(layer);
      }
    }
  }

  private void decorate(Layer layer) {
    DispatcherDecorator<T> decorator = decorators.get(layer);
    if (decorator != null) {
      top =
          Preconditions.checkNotNull(
              decorator.decorate(top), "%s: the %s decorator returned null", name, layer);
    }
  }
}
