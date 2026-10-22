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
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.secret.SecretBinding;
import org.apache.gravitino.secret.SecretReference;

/**
 * A {@link SchemaDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link SchemaDispatcher}, default methods included, so a subclass
 * never falls back to an interface default where the dispatcher it wraps does something else.
 */
public abstract class ForwardingSchemaDispatcher implements SchemaDispatcher {

  private final SchemaDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingSchemaDispatcher(SchemaDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected SchemaDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) {
    return delegate.listSchemas(namespace);
  }

  @Override
  public boolean schemaExists(NameIdentifier ident) {
    return delegate.schemaExists(ident);
  }

  @Override
  public Schema createSchema(NameIdentifier ident, String comment, Map<String, String> properties) {
    return delegate.createSchema(ident, comment, properties);
  }

  @Override
  public Schema loadSchema(NameIdentifier ident) {
    return delegate.loadSchema(ident);
  }

  @Override
  public Schema alterSchema(NameIdentifier ident, SchemaChange... changes) {
    return delegate.alterSchema(ident, changes);
  }

  @Override
  public boolean dropSchema(NameIdentifier ident, boolean cascade) {
    return delegate.dropSchema(ident, cascade);
  }

  @Override
  public Schema createSchema(
      NameIdentifier ident,
      String comment,
      Map<String, String> properties,
      Map<String, SecretBinding> secretBindings,
      Map<String, SecretReference> secretReferences) {
    return delegate.createSchema(ident, comment, properties, secretBindings, secretReferences);
  }
}
