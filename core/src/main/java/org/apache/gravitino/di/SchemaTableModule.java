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
import java.util.Map;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.OwnerDispatcher;
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
 * partitions, whose public chain is event → normalize → operation because partitions have no hook.
 * The schema chains share one {@link SchemaOperationDispatcher} and the partition chains one
 * partition operation dispatcher; the table and view chains each build their own operation
 * dispatcher, as {@code GravitinoEnv} did.
 *
 * <p>Table and view operation dispatchers import missing schemas through the internal schema chain.
 * They take it as a {@link Provider} and resolve it on first use (§5.3 of the design).
 *
 * <p>{@link Chain} assembles each chain from its layers and the {@link DispatcherDecorator
 * decorators} contributed for it, keyed by {@link Layer}; upstream contributes none.
 */
@Module
abstract class SchemaTableModule {

  private SchemaTableModule() {}

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<SchemaDispatcher>> internalSchemaDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<SchemaDispatcher>> schemaDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<TableDispatcher>> internalTableDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<TableDispatcher>> tableDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<PartitionDispatcher>> internalPartitionDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<PartitionDispatcher>> partitionDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<ViewDispatcher>> internalViewDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<ViewDispatcher>> viewDecorators();

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
      @Internal Map<Layer, DispatcherDecorator<SchemaDispatcher>> decorators) {
    return Chain.<SchemaDispatcher>of("@Internal SchemaDispatcher", operations, decorators)
        .normalize(d -> new SchemaNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static SchemaDispatcher schemaDispatcher(
      EventBus eventBus,
      @Undecorated SchemaOperationDispatcher operations,
      CatalogManager catalogManager,
      LockManager lockManager,
      @Public Map<Layer, DispatcherDecorator<SchemaDispatcher>> decorators) {
    return Chain.<SchemaDispatcher>of("@Public SchemaDispatcher", operations, decorators)
        .hook(d -> new SchemaHookDispatcher(d, lockManager))
        .normalize(d -> new SchemaNormalizeDispatcher(d, catalogManager))
        .event(d -> new SchemaEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<TableDispatcher>> decorators) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Chain.<TableDispatcher>of("@Internal TableDispatcher", operations, decorators)
        .normalize(d -> new TableNormalizeDispatcher(d, catalogManager))
        .build();
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
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<OwnerDispatcher> ownerDispatcher,
      @Public Map<Layer, DispatcherDecorator<TableDispatcher>> decorators) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Chain.<TableDispatcher>of("@Public TableDispatcher", operations, decorators)
        .hook(
            d ->
                new TableHookDispatcher(
                    d, () -> authorizationEnabled ? ownerDispatcher.get() : null))
        .normalize(d -> new TableNormalizeDispatcher(d, catalogManager))
        .event(d -> new TableEventDispatcher(eventBus, d))
        .build();
  }

  /** Builds the partition operation dispatcher at the bottom of both partition chains. */
  @Provides
  @Singleton
  @Undecorated
  static PartitionOperationDispatcher partitionOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    return new PartitionOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager);
  }

  @Provides
  @Singleton
  @Internal
  static PartitionDispatcher internalPartitionDispatcher(
      @Undecorated PartitionOperationDispatcher operations,
      CatalogManager catalogManager,
      @Internal Map<Layer, DispatcherDecorator<PartitionDispatcher>> decorators) {
    return Chain.<PartitionDispatcher>of("@Internal PartitionDispatcher", operations, decorators)
        .normalize(d -> new PartitionNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static PartitionDispatcher partitionDispatcher(
      EventBus eventBus,
      @Undecorated PartitionOperationDispatcher operations,
      CatalogManager catalogManager,
      @Public Map<Layer, DispatcherDecorator<PartitionDispatcher>> decorators) {
    // Partitions have no ownership, so their public chain has no hook layer.
    return Chain.<PartitionDispatcher>of("@Public PartitionDispatcher", operations, decorators)
        .normalize(d -> new PartitionNormalizeDispatcher(d, catalogManager))
        .event(d -> new PartitionEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<ViewDispatcher>> decorators) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Chain.<ViewDispatcher>of("@Internal ViewDispatcher", operations, decorators)
        .normalize(d -> new ViewNormalizeDispatcher(d, catalogManager))
        .build();
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
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<OwnerDispatcher> ownerDispatcher,
      @Public Map<Layer, DispatcherDecorator<ViewDispatcher>> decorators) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return Chain.<ViewDispatcher>of("@Public ViewDispatcher", operations, decorators)
        .hook(
            d ->
                new ViewHookDispatcher(
                    d, () -> authorizationEnabled ? ownerDispatcher.get() : null))
        .normalize(d -> new ViewNormalizeDispatcher(d, catalogManager))
        .event(d -> new ViewEventDispatcher(eventBus, d))
        .build();
  }
}
