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

package org.apache.gravitino.catalog;

import com.google.common.base.Preconditions;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.ViewChange;

/**
 * A {@link ViewDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link ViewDispatcher}, default methods included, so a subclass
 * never falls back to an interface default where the dispatcher it wraps does something else.
 */
public abstract class ForwardingViewDispatcher implements ViewDispatcher {

  private final ViewDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingViewDispatcher(ViewDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected ViewDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listViews(Namespace namespace) {
    return delegate.listViews(namespace);
  }

  @Override
  public View loadView(NameIdentifier ident) {
    return delegate.loadView(ident);
  }

  @Override
  public boolean viewExists(NameIdentifier ident) {
    return delegate.viewExists(ident);
  }

  @Override
  public View createView(
      NameIdentifier ident,
      @Nullable String comment,
      Column[] columns,
      Representation[] representations,
      @Nullable String defaultCatalog,
      @Nullable String defaultSchema,
      Map<String, String> properties) {
    return delegate.createView(
        ident, comment, columns, representations, defaultCatalog, defaultSchema, properties);
  }

  @Override
  public View alterView(NameIdentifier ident, ViewChange... changes) {
    return delegate.alterView(ident, changes);
  }

  @Override
  public boolean dropView(NameIdentifier ident) {
    return delegate.dropView(ident);
  }
}
