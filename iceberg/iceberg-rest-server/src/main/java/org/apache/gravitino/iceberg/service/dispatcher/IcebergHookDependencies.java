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
package org.apache.gravitino.iceberg.service.dispatcher;

import java.util.function.Supplier;
import javax.inject.Inject;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;

/** Dependencies used by the Iceberg REST hook decorators. */
@IcebergHookGraph.AuxiliaryScope
final class IcebergHookDependencies {

  private final Supplier<EntityStore> entityStore;
  private final Supplier<LockManager> lockManager;
  private final Supplier<SchemaDispatcher> schemaDispatcher;
  private final Supplier<TableDispatcher> tableDispatcher;
  private final Supplier<ViewDispatcher> viewDispatcher;
  private final Supplier<OwnerDispatcher> ownerDispatcher;
  private final Supplier<String> schemaSeparator;

  @Inject
  IcebergHookDependencies(
      EntityStore entityStore,
      LockManager lockManager,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      ViewDispatcher viewDispatcher,
      OwnerDispatcher ownerDispatcher,
      @IcebergHookGraph.SchemaSeparator String schemaSeparator) {
    this(
        () -> entityStore,
        () -> lockManager,
        () -> schemaDispatcher,
        () -> tableDispatcher,
        () -> viewDispatcher,
        () -> ownerDispatcher,
        () -> schemaSeparator);
  }

  private IcebergHookDependencies(
      Supplier<EntityStore> entityStore,
      Supplier<LockManager> lockManager,
      Supplier<SchemaDispatcher> schemaDispatcher,
      Supplier<TableDispatcher> tableDispatcher,
      Supplier<ViewDispatcher> viewDispatcher,
      Supplier<OwnerDispatcher> ownerDispatcher,
      Supplier<String> schemaSeparator) {
    this.entityStore = entityStore;
    this.lockManager = lockManager;
    this.schemaDispatcher = schemaDispatcher;
    this.tableDispatcher = tableDispatcher;
    this.viewDispatcher = viewDispatcher;
    this.ownerDispatcher = ownerDispatcher;
    this.schemaSeparator = schemaSeparator;
  }

  /**
   * Returns the dynamic service-locator bridge used only by public constructors retained for
   * compatibility.
   *
   * @deprecated new wiring must use Dagger's exact bindings
   */
  @Deprecated(forRemoval = true)
  static IcebergHookDependencies legacy() {
    return new IcebergHookDependencies(
        () -> GravitinoEnv.getInstance().entityStore(),
        () -> GravitinoEnv.getInstance().lockManager(),
        () -> GravitinoEnv.getInstance().internalSchemaDispatcher(),
        () -> GravitinoEnv.getInstance().internalTableDispatcher(),
        () -> GravitinoEnv.getInstance().internalViewDispatcher(),
        () -> GravitinoEnv.getInstance().internalOwnerDispatcher(),
        HierarchicalSchemaUtil::schemaSeparator);
  }

  EntityStore entityStore() {
    return entityStore.get();
  }

  LockManager lockManager() {
    return lockManager.get();
  }

  SchemaDispatcher schemaDispatcher() {
    return schemaDispatcher.get();
  }

  TableDispatcher tableDispatcher() {
    return tableDispatcher.get();
  }

  ViewDispatcher viewDispatcher() {
    return viewDispatcher.get();
  }

  OwnerDispatcher ownerDispatcher() {
    return ownerDispatcher.get();
  }

  String schemaSeparator() {
    return schemaSeparator.get();
  }
}
