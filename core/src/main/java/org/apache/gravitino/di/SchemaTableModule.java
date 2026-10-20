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
 * partitions, whose public chain is event → the internal chain because partitions have no hook. The
 * schema chains share one {@link SchemaOperationDispatcher}; the table and view chains each build
 * their own operation dispatcher, as {@code GravitinoEnv} did.
 *
 * <p>Table and view operation dispatchers import missing schemas through the internal schema chain.
 * They take it as a {@link Provider} and resolve it on first use (§5.3 of the design).
 */
@Module
abstract class SchemaTableModule {

  private SchemaTableModule() {}

  @Provides
  @Singleton
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
      SchemaOperationDispatcher operations, CatalogManager catalogManager) {
    return new SchemaNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static SchemaDispatcher schemaDispatcher(
      EventBus eventBus,
      SchemaOperationDispatcher operations,
      CatalogManager catalogManager,
      LockManager lockManager) {
    SchemaHookDispatcher hook = new SchemaHookDispatcher(operations, lockManager);
    return new SchemaEventDispatcher(eventBus, new SchemaNormalizeDispatcher(hook, catalogManager));
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
      LockManager lockManager) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return new TableNormalizeDispatcher(operations, catalogManager);
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
      @Internal Provider<OwnerDispatcher> ownerDispatcher) {
    TableOperationDispatcher operations =
        new TableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    TableHookDispatcher hook =
        new TableHookDispatcher(
            operations, () -> authorizationEnabled ? ownerDispatcher.get() : null);
    return new TableEventDispatcher(eventBus, new TableNormalizeDispatcher(hook, catalogManager));
  }

  @Provides
  @Singleton
  @Internal
  static PartitionDispatcher internalPartitionDispatcher(
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
  @Public
  static PartitionDispatcher partitionDispatcher(
      EventBus eventBus, @Internal PartitionDispatcher internal) {
    return new PartitionEventDispatcher(eventBus, internal);
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
      LockManager lockManager) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    return new ViewNormalizeDispatcher(operations, catalogManager);
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
      @Internal Provider<OwnerDispatcher> ownerDispatcher) {
    ViewOperationDispatcher operations =
        new ViewOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            schemaDispatcher::get,
            secretManager,
            lockManager);
    ViewHookDispatcher hook =
        new ViewHookDispatcher(
            operations, () -> authorizationEnabled ? ownerDispatcher.get() : null);
    return new ViewEventDispatcher(eventBus, new ViewNormalizeDispatcher(hook, catalogManager));
  }
}
