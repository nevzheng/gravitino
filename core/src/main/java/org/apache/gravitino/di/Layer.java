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

/**
 * The positions of a dispatcher chain where a {@link DispatcherDecorator} can sit, from the
 * innermost to the outermost.
 *
 * <p>Each upstream chain is built in layers: an operation dispatcher or manager at the bottom,
 * then, for a {@link Public} chain, a hook layer, a normalize layer and an event layer. A decorator
 * keyed with a layer sits directly over that layer of the chain, under the next one. For a chain
 * without that layer, such as an {@link Internal} chain, which has no hook or event layer, the
 * decorator sits at the same position: over the layers below it and under the layers above it.
 *
 * <p>A downstream component contributes decorators with {@code @IntoMap} and {@link LayerKey}:
 *
 * <pre>{@code
 * @Provides
 * @IntoMap
 * @LayerKey(Layer.EXTENSION)
 * @Public
 * static DispatcherDecorator<TableDispatcher> auditedTables(AuditTrail trail) {
 *   return tables -> new AuditedTableDispatcher(tables, trail);
 * }
 * }</pre>
 *
 * <p>Each chain takes at most one decorator per layer: two contributions with the same key to one
 * chain are a duplicate map key, which Dagger rejects when the component is compiled.
 */
public enum Layer {
  /**
   * Over the operation dispatcher or manager, under every other layer: a decorator here sees
   * normalized identifiers, and a call it rejects still fires the chain's failure events.
   */
  OPERATION,

  /** Over the hook layer, which keeps owners, grants and authorization plugins in step. */
  HOOK,

  /** Over the normalize layer, which applies the catalog's case sensitivity to identifiers. */
  NORMALIZE,

  /** Over the event layer: a decorator here sees each call before the chain fires its events. */
  EVENT,

  /**
   * Outermost: the object the chain's binding returns. A downstream component adds its own methods
   * to a chain here, so its consumers can cast the chain to its type.
   */
  EXTENSION
}
