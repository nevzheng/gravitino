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
import java.util.Set;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;

/**
 * A {@link TableDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link TableDispatcher}, default methods included, so a subclass
 * never falls back to an interface default where the dispatcher it wraps does something else.
 */
public abstract class ForwardingTableDispatcher implements TableDispatcher {

  private final TableDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingTableDispatcher(TableDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected TableDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listTables(Namespace namespace) {
    return delegate.listTables(namespace);
  }

  @Override
  public Table loadTable(NameIdentifier ident) {
    return delegate.loadTable(ident);
  }

  @Override
  public Table loadTable(NameIdentifier ident, Set<Privilege.Name> requiredPrivilegeNames) {
    return delegate.loadTable(ident, requiredPrivilegeNames);
  }

  @Override
  public boolean tableExists(NameIdentifier ident) {
    return delegate.tableExists(ident);
  }

  @Override
  public Table createTable(
      NameIdentifier ident, Column[] columns, String comment, Map<String, String> properties) {
    return delegate.createTable(ident, columns, comment, properties);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions) {
    return delegate.createTable(ident, columns, comment, properties, partitions);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Distribution distribution,
      SortOrder[] sortOrders) {
    return delegate.createTable(ident, columns, comment, properties, distribution, sortOrders);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Distribution distribution) {
    return delegate.createTable(ident, columns, comment, properties, distribution);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      SortOrder[] sortOrders) {
    return delegate.createTable(ident, columns, comment, properties, sortOrders);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders) {
    return delegate.createTable(
        ident, columns, comment, properties, partitions, distribution, sortOrders);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes) {
    return delegate.createTable(
        ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
  }

  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes) {
    return delegate.alterTable(ident, changes);
  }

  @Override
  public boolean dropTable(NameIdentifier ident) {
    return delegate.dropTable(ident);
  }

  @Override
  public boolean purgeTable(NameIdentifier ident) {
    return delegate.purgeTable(ident);
  }
}
