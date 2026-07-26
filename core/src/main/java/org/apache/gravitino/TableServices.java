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
package org.apache.gravitino;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.storage.IdGenerator;

/** Package-private composition boundary for the authorization-independent table graph. */
final class TableServices {

  private final TableComponent component;

  private TableServices(TableComponent component) {
    this.component = component;
  }

  static TableServices create(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      EventBus eventBus,
      SchemaDispatcher publicSchemaDispatcher,
      SchemaDispatcher internalSchemaDispatcher) {
    return new TableServices(
        DaggerTableServices_TableComponent.factory()
            .create(
                catalogManager,
                entityStore,
                idGenerator,
                eventBus,
                publicSchemaDispatcher,
                internalSchemaDispatcher));
  }

  TableDispatcher tableDispatcher() {
    return component.tableEventDispatcher();
  }

  TableDispatcher internalTableDispatcher() {
    return component.internalTableDispatcher();
  }

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @Target({FIELD, METHOD, PARAMETER})
  @interface PublicTable {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @Target({FIELD, METHOD, PARAMETER})
  @interface InternalTable {}

  @Singleton
  @Component(modules = TableModule.class)
  interface TableComponent {

    TableEventDispatcher tableEventDispatcher();

    @InternalTable
    TableDispatcher internalTableDispatcher();

    @Component.Factory
    interface Factory {

      TableComponent create(
          @BindsInstance CatalogManager catalogManager,
          @BindsInstance EntityStore entityStore,
          @BindsInstance IdGenerator idGenerator,
          @BindsInstance EventBus eventBus,
          @BindsInstance @PublicTable SchemaDispatcher publicSchemaDispatcher,
          @BindsInstance @InternalTable SchemaDispatcher internalSchemaDispatcher);
    }
  }

  @Module
  static final class TableModule {

    private TableModule() {}

    @Provides
    @Singleton
    @PublicTable
    static TableOperationDispatcher providePublicTableOperationDispatcher(
        CatalogManager catalogManager,
        EntityStore entityStore,
        IdGenerator idGenerator,
        @PublicTable SchemaDispatcher schemaDispatcher) {
      return new TableOperationDispatcher(
          catalogManager, entityStore, idGenerator, () -> schemaDispatcher);
    }

    @Provides
    @Singleton
    @InternalTable
    static TableOperationDispatcher provideInternalTableOperationDispatcher(
        CatalogManager catalogManager,
        EntityStore entityStore,
        IdGenerator idGenerator,
        @InternalTable SchemaDispatcher schemaDispatcher) {
      return new TableOperationDispatcher(
          catalogManager, entityStore, idGenerator, () -> schemaDispatcher);
    }

    @Provides
    @Singleton
    @PublicTable
    static TableDispatcher providePublicTableNormalizeDispatcher(
        @PublicTable TableOperationDispatcher dispatcher, CatalogManager catalogManager) {
      return new TableNormalizeDispatcher(dispatcher, catalogManager);
    }

    @Provides
    @Singleton
    @InternalTable
    static TableDispatcher provideInternalTableNormalizeDispatcher(
        @InternalTable TableOperationDispatcher dispatcher, CatalogManager catalogManager) {
      return new TableNormalizeDispatcher(dispatcher, catalogManager);
    }

    @Provides
    @Singleton
    static TableEventDispatcher provideTableEventDispatcher(
        EventBus eventBus, @PublicTable TableDispatcher dispatcher) {
      return new TableEventDispatcher(eventBus, dispatcher);
    }
  }
}
