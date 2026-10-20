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

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.Set;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.PartitionNormalizeDispatcher;
import org.apache.gravitino.catalog.PartitionOperationDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SchemaNormalizeDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.catalog.ViewNormalizeDispatcher;
import org.apache.gravitino.catalog.ViewOperationDispatcher;
import org.apache.gravitino.hook.SchemaHookDispatcher;
import org.apache.gravitino.hook.TableHookDispatcher;
import org.apache.gravitino.hook.ViewHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.PartitionEventDispatcher;
import org.apache.gravitino.listener.SchemaEventDispatcher;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.ViewEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the schema, table, partition and view dispatcher chains.
 *
 * <p>Each type has two chains. The {@link Internal} chain is normalize → operation and is used by
 * infrastructure code. The {@link Public} chain is event → normalize → hook → operation, except for
 * partitions, whose public chain is event → the undecorated internal chain because partitions have
 * no hook. Each chain is wrapped in its {@link DispatcherDecorator decorators}. The schema chains
 * share one {@link SchemaOperationDispatcher}; the table and view chains each build their own
 * operation dispatcher, as {@code GravitinoEnv} did.
 *
 * <p>Table and view operation dispatchers import missing schemas through the internal schema chain.
 * They take it as a {@link Provider} and resolve it on first use (§5.3 of the design).
 */
@Module
abstract class SchemaTableModule {

  private SchemaTableModule() {}

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<SchemaDispatcher>> internalSchemaDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<SchemaDispatcher>> schemaDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<TableDispatcher>> internalTableDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<TableDispatcher>> tableDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<PartitionDispatcher>> internalPartitionDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<PartitionDispatcher>> partitionDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<ViewDispatcher>> internalViewDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<ViewDispatcher>> viewDecorators();

  @Provides
  @Singleton
  @Undecorated
  static SchemaOperationDispatcher schemaOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      @Internal FilesetDispatcher filesetDispatcher,
      LockManager lockManager) {
    return new SchemaOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager, filesetDispatcher, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static SchemaDispatcher internalSchemaDispatcher(
      @Undecorated SchemaOperationDispatcher operations,
      CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<SchemaDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal SchemaDispatcher",
        new SchemaNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static SchemaDispatcher schemaDispatcher(
      EventBus eventBus,
      @Undecorated SchemaOperationDispatcher operations,
      CatalogManager catalogManager,
      LockManager lockManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<SchemaDispatcher>> decorators) {
    SchemaHookDispatcher hook =
        new SchemaHookDispatcher(operations, lockManager, authorizationServices);
    return Decorators.apply(
        "@Public SchemaDispatcher",
        new SchemaEventDispatcher(eventBus, new SchemaNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  @Provides
  @Singleton
  @Internal
  static TableDispatcher internalTableDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Internal Provider<SchemaDispatcher> schemaDispatcher,
      SecretManager secretManager,
      LockManager lockManager,
      @Internal Set<DispatcherDecorator<TableDispatcher>> decorators) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Decorators.apply(
        "@Internal TableDispatcher",
        new TableNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static TableDispatcher tableDispatcher(
      EventBus eventBus,
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Internal Provider<SchemaDispatcher> schemaDispatcher,
      SecretManager secretManager,
      LockManager lockManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<TableDispatcher>> decorators) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    TableHookDispatcher hook = new TableHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public TableDispatcher",
        new TableEventDispatcher(eventBus, new TableNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  /** Builds the undecorated internal partition chain, which the public chain wraps. */
  @Provides
  @Singleton
  @Undecorated
  static PartitionNormalizeDispatcher partitionNormalizeDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    PartitionOperationDispatcher operations =
        new PartitionOperationDispatcher(catalogManager, entityStore, idGenerator, secretManager);
    return new PartitionNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Internal
  static PartitionDispatcher internalPartitionDispatcher(
      @Undecorated PartitionNormalizeDispatcher normalize,
      @Internal Set<DispatcherDecorator<PartitionDispatcher>> decorators) {
    return Decorators.apply("@Internal PartitionDispatcher", normalize, decorators);
  }

  @Provides
  @Singleton
  @Public
  static PartitionDispatcher partitionDispatcher(
      EventBus eventBus,
      @Undecorated PartitionNormalizeDispatcher normalize,
      @Public Set<DispatcherDecorator<PartitionDispatcher>> decorators) {
    return Decorators.apply(
        "@Public PartitionDispatcher",
        new PartitionEventDispatcher(eventBus, normalize),
        decorators);
  }

  @Provides
  @Singleton
  @Internal
  static ViewDispatcher internalViewDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Internal Provider<SchemaDispatcher> schemaDispatcher,
      SecretManager secretManager,
      LockManager lockManager,
      @Internal Set<DispatcherDecorator<ViewDispatcher>> decorators) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Decorators.apply(
        "@Internal ViewDispatcher",
        new ViewNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static ViewDispatcher viewDispatcher(
      EventBus eventBus,
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Internal Provider<SchemaDispatcher> schemaDispatcher,
      SecretManager secretManager,
      LockManager lockManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<ViewDispatcher>> decorators) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    ViewHookDispatcher hook = new ViewHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public ViewDispatcher",
        new ViewEventDispatcher(eventBus, new ViewNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }
}
