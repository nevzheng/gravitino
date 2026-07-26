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

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Subcomponent;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Qualifier;
import javax.inject.Scope;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;

/** Compile-time composition boundary for Iceberg REST operation decorators. */
public final class IcebergHookGraph {

  private IcebergHookGraph() {}

  /** Final dispatchers exposed to Jersey. */
  public interface Dispatchers {

    /** Returns the final namespace dispatcher. */
    IcebergNamespaceOperationDispatcher namespaceDispatcher();

    /** Returns the final table dispatcher. */
    IcebergTableOperationDispatcher tableDispatcher();

    /** Returns the final view dispatcher. */
    IcebergViewOperationDispatcher viewDispatcher();
  }

  /** Creates the standalone-safe graph whose final dispatchers are the event decorators. */
  public static Dispatchers createBase(
      IcebergNamespaceOperationDispatcher namespaceOperations,
      IcebergTableOperationDispatcher tableOperations,
      IcebergViewOperationDispatcher viewOperations,
      EventBus eventBus,
      String metalakeName) {
    return baseComponent(
        namespaceOperations, tableOperations, viewOperations, eventBus, metalakeName);
  }

  /** Creates the hook-enabled graph for an authorized auxiliary server. */
  public static Dispatchers createAuxiliary(
      IcebergNamespaceOperationDispatcher namespaceOperations,
      IcebergTableOperationDispatcher tableOperations,
      IcebergViewOperationDispatcher viewOperations,
      EventBus eventBus,
      String metalakeName,
      EntityStore entityStore,
      LockManager lockManager,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      ViewDispatcher viewDispatcher,
      OwnerDispatcher ownerDispatcher,
      String schemaSeparator) {
    return baseComponent(
            namespaceOperations, tableOperations, viewOperations, eventBus, metalakeName)
        .auxiliaryFactory()
        .create(
            entityStore,
            lockManager,
            schemaDispatcher,
            tableDispatcher,
            viewDispatcher,
            ownerDispatcher,
            schemaSeparator);
  }

  static GravitinoEnv legacyEnvironment() {
    return GravitinoEnv.getInstance();
  }

  private static BaseComponent baseComponent(
      IcebergNamespaceOperationDispatcher namespaceOperations,
      IcebergTableOperationDispatcher tableOperations,
      IcebergViewOperationDispatcher viewOperations,
      EventBus eventBus,
      String metalakeName) {
    return DaggerIcebergHookGraph_BaseComponent.factory()
        .create(namespaceOperations, tableOperations, viewOperations, eventBus, metalakeName);
  }

  @Singleton
  @Component
  interface BaseComponent extends Dispatchers {

    @Override
    IcebergNamespaceEventDispatcher namespaceDispatcher();

    @Override
    IcebergTableEventDispatcher tableDispatcher();

    @Override
    IcebergViewEventDispatcher viewDispatcher();

    AuxiliaryComponent.Factory auxiliaryFactory();

    @Component.Factory
    interface Factory {

      BaseComponent create(
          @BindsInstance IcebergNamespaceOperationDispatcher namespaceOperations,
          @BindsInstance IcebergTableOperationDispatcher tableOperations,
          @BindsInstance IcebergViewOperationDispatcher viewOperations,
          @BindsInstance EventBus eventBus,
          @BindsInstance @Metalake String metalakeName);
    }
  }

  @AuxiliaryScope
  @Subcomponent(modules = EventBindings.class)
  interface AuxiliaryComponent extends Dispatchers {

    @Override
    IcebergNamespaceHookDispatcher namespaceDispatcher();

    @Override
    IcebergTableHookDispatcher tableDispatcher();

    @Override
    IcebergViewHookDispatcher viewDispatcher();

    @Subcomponent.Factory
    interface Factory {

      AuxiliaryComponent create(
          @BindsInstance EntityStore entityStore,
          @BindsInstance LockManager lockManager,
          @BindsInstance SchemaDispatcher schemaDispatcher,
          @BindsInstance TableDispatcher tableDispatcher,
          @BindsInstance ViewDispatcher viewDispatcher,
          @BindsInstance OwnerDispatcher ownerDispatcher,
          @BindsInstance @SchemaSeparator String schemaSeparator);
    }
  }

  @Module
  interface EventBindings {

    @Binds
    @EventLayer
    IcebergNamespaceOperationDispatcher bindNamespace(IcebergNamespaceEventDispatcher dispatcher);

    @Binds
    @EventLayer
    IcebergTableOperationDispatcher bindTable(IcebergTableEventDispatcher dispatcher);

    @Binds
    @EventLayer
    IcebergViewOperationDispatcher bindView(IcebergViewEventDispatcher dispatcher);
  }

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface Metalake {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface SchemaSeparator {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface EventLayer {}

  @Scope
  @Retention(RetentionPolicy.RUNTIME)
  @interface AuxiliaryScope {}
}
