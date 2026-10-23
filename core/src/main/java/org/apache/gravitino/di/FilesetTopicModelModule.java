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
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.FunctionNormalizeDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.SemanticModelNormalizeDispatcher;
import org.apache.gravitino.catalog.SemanticModelOperationDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.TopicNormalizeDispatcher;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.hook.FilesetHookDispatcher;
import org.apache.gravitino.hook.FunctionHookDispatcher;
import org.apache.gravitino.hook.ModelHookDispatcher;
import org.apache.gravitino.hook.SemanticModelHookDispatcher;
import org.apache.gravitino.hook.TopicHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.FilesetEventDispatcher;
import org.apache.gravitino.listener.FunctionEventDispatcher;
import org.apache.gravitino.listener.ModelEventDispatcher;
import org.apache.gravitino.listener.SemanticModelEventDispatcher;
import org.apache.gravitino.listener.TopicEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the fileset, topic, model, function and semantic model dispatcher chains.
 *
 * <p>Each type has an {@link Internal} chain, normalize → operation, and a {@link Public} chain,
 * event → normalize → hook → operation. The fileset, topic, model and function chains share one
 * operation dispatcher per type; the public semantic model chain builds its own, as {@code
 * GravitinoEnv} did. Function and semantic model operations work on schemas through the shared
 * {@link SchemaOperationDispatcher} from {@link SchemaTableModule}.
 */
@Module
abstract class FilesetTopicModelModule {

  private FilesetTopicModelModule() {}

  @Provides
  @Singleton
  static FilesetOperationDispatcher filesetOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      LockManager lockManager) {
    return new FilesetOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static FilesetDispatcher internalFilesetDispatcher(
      FilesetOperationDispatcher operations, CatalogManager catalogManager) {
    return new FilesetNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static FilesetDispatcher filesetDispatcher(
      EventBus eventBus, FilesetOperationDispatcher operations, CatalogManager catalogManager) {
    FilesetHookDispatcher hook = new FilesetHookDispatcher(operations);
    return new FilesetEventDispatcher(
        eventBus, new FilesetNormalizeDispatcher(hook, catalogManager));
  }

  @Provides
  @Singleton
  static TopicOperationDispatcher topicOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      LockManager lockManager) {
    return new TopicOperationDispatcher(
        catalogManager, entityStore, idGenerator, secretManager, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static TopicDispatcher internalTopicDispatcher(
      TopicOperationDispatcher operations, CatalogManager catalogManager) {
    return new TopicNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static TopicDispatcher topicDispatcher(
      EventBus eventBus, TopicOperationDispatcher operations, CatalogManager catalogManager) {
    TopicHookDispatcher hook = new TopicHookDispatcher(operations);
    return new TopicEventDispatcher(eventBus, new TopicNormalizeDispatcher(hook, catalogManager));
  }

  @Provides
  @Singleton
  static ModelOperationDispatcher modelOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    return new ModelOperationDispatcher(catalogManager, entityStore, idGenerator, secretManager);
  }

  @Provides
  @Singleton
  @Internal
  static ModelDispatcher internalModelDispatcher(
      ModelOperationDispatcher operations, CatalogManager catalogManager) {
    return new ModelNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static ModelDispatcher modelDispatcher(
      EventBus eventBus, ModelOperationDispatcher operations, CatalogManager catalogManager) {
    ModelHookDispatcher hook = new ModelHookDispatcher(operations);
    return new ModelEventDispatcher(eventBus, new ModelNormalizeDispatcher(hook, catalogManager));
  }

  @Provides
  @Singleton
  static FunctionOperationDispatcher functionOperationDispatcher(
      CatalogManager catalogManager,
      SchemaOperationDispatcher schemaOperations,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      LockManager lockManager) {
    return new FunctionOperationDispatcher(
        catalogManager, schemaOperations, entityStore, idGenerator, secretManager, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static FunctionDispatcher internalFunctionDispatcher(
      FunctionOperationDispatcher operations, CatalogManager catalogManager) {
    return new FunctionNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static FunctionDispatcher functionDispatcher(
      EventBus eventBus,
      FunctionOperationDispatcher operations,
      CatalogManager catalogManager,
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<OwnerDispatcher> ownerDispatcher) {
    FunctionHookDispatcher hook =
        new FunctionHookDispatcher(
            operations, () -> authorizationEnabled ? ownerDispatcher.get() : null);
    return new FunctionEventDispatcher(
        eventBus, new FunctionNormalizeDispatcher(hook, catalogManager));
  }

  @Provides
  @Singleton
  @Internal
  static SemanticModelDispatcher internalSemanticModelDispatcher(
      CatalogManager catalogManager,
      SchemaOperationDispatcher schemaOperations,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    return new SemanticModelNormalizeDispatcher(operations, catalogManager);
  }

  @Provides
  @Singleton
  @Public
  static SemanticModelDispatcher semanticModelDispatcher(
      EventBus eventBus,
      CatalogManager catalogManager,
      SchemaOperationDispatcher schemaOperations,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<OwnerDispatcher> ownerDispatcher) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    SemanticModelHookDispatcher hook =
        new SemanticModelHookDispatcher(
            operations, () -> authorizationEnabled ? ownerDispatcher.get() : null);
    return new SemanticModelEventDispatcher(
        eventBus, new SemanticModelNormalizeDispatcher(hook, catalogManager));
  }
}
