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
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.FunctionNormalizeDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
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
 * event → normalize → hook → operation, each wrapped in its {@link DispatcherDecorator decorators}.
 * The fileset, topic, model and function chains share one operation dispatcher per type; the public
 * semantic model chain builds its own. Function and semantic model operations work on schemas
 * through the shared {@link SchemaOperationDispatcher} from {@link SchemaTableModule}.
 */
@Module
abstract class FilesetTopicModelModule {

  private FilesetTopicModelModule() {}

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<FilesetDispatcher>> internalFilesetDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<FilesetDispatcher>> filesetDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<TopicDispatcher>> internalTopicDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<TopicDispatcher>> topicDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<ModelDispatcher>> internalModelDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<ModelDispatcher>> modelDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<FunctionDispatcher>> internalFunctionDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<FunctionDispatcher>> functionDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<SemanticModelDispatcher>> internalSemanticModelDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<SemanticModelDispatcher>> semanticModelDecorators();

  @Provides
  @Singleton
  @Undecorated
  static FilesetOperationDispatcher filesetOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
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
      @Undecorated FilesetOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<FilesetDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal FilesetDispatcher",
        new FilesetNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static FilesetDispatcher filesetDispatcher(
      EventBus eventBus,
      @Undecorated FilesetOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<FilesetDispatcher>> decorators) {
    FilesetHookDispatcher hook = new FilesetHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public FilesetDispatcher",
        new FilesetEventDispatcher(eventBus, new FilesetNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  @Provides
  @Singleton
  @Undecorated
  static TopicOperationDispatcher topicOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      @Internal Provider<SchemaDispatcher> schemaDispatcher,
      SecretManager secretManager,
      LockManager lockManager) {
    return new TopicOperationDispatcher(
        catalogManager,
        entityStore,
        idGenerator,
        schemaDispatcher::get,
        secretManager,
        lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static TopicDispatcher internalTopicDispatcher(
      @Undecorated TopicOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<TopicDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal TopicDispatcher",
        new TopicNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static TopicDispatcher topicDispatcher(
      EventBus eventBus,
      @Undecorated TopicOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<TopicDispatcher>> decorators) {
    TopicHookDispatcher hook = new TopicHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public TopicDispatcher",
        new TopicEventDispatcher(eventBus, new TopicNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  @Provides
  @Singleton
  @Undecorated
  static ModelOperationDispatcher modelOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager) {
    return new ModelOperationDispatcher(catalogManager, entityStore, idGenerator, secretManager);
  }

  @Provides
  @Singleton
  @Internal
  static ModelDispatcher internalModelDispatcher(
      @Undecorated ModelOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<ModelDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal ModelDispatcher",
        new ModelNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static ModelDispatcher modelDispatcher(
      EventBus eventBus,
      @Undecorated ModelOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<ModelDispatcher>> decorators) {
    ModelHookDispatcher hook = new ModelHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public ModelDispatcher",
        new ModelEventDispatcher(eventBus, new ModelNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  @Provides
  @Singleton
  @Undecorated
  static FunctionOperationDispatcher functionOperationDispatcher(
      @Undecorated CatalogManager catalogManager,
      @Undecorated SchemaOperationDispatcher schemaOperations,
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
      @Undecorated FunctionOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      @Internal Set<DispatcherDecorator<FunctionDispatcher>> decorators) {
    return Decorators.apply(
        "@Internal FunctionDispatcher",
        new FunctionNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static FunctionDispatcher functionDispatcher(
      EventBus eventBus,
      @Undecorated FunctionOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<FunctionDispatcher>> decorators) {
    FunctionHookDispatcher hook = new FunctionHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public FunctionDispatcher",
        new FunctionEventDispatcher(
            eventBus, new FunctionNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }

  @Provides
  @Singleton
  @Internal
  static SemanticModelDispatcher internalSemanticModelDispatcher(
      @Undecorated CatalogManager catalogManager,
      @Undecorated SchemaOperationDispatcher schemaOperations,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      @Internal Set<DispatcherDecorator<SemanticModelDispatcher>> decorators) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    return Decorators.apply(
        "@Internal SemanticModelDispatcher",
        new SemanticModelNormalizeDispatcher(operations, catalogManager),
        decorators);
  }

  @Provides
  @Singleton
  @Public
  static SemanticModelDispatcher semanticModelDispatcher(
      EventBus eventBus,
      @Undecorated CatalogManager catalogManager,
      @Undecorated SchemaOperationDispatcher schemaOperations,
      EntityStore entityStore,
      IdGenerator idGenerator,
      SecretManager secretManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<SemanticModelDispatcher>> decorators) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    SemanticModelHookDispatcher hook =
        new SemanticModelHookDispatcher(operations, authorizationServices);
    return Decorators.apply(
        "@Public SemanticModelDispatcher",
        new SemanticModelEventDispatcher(
            eventBus, new SemanticModelNormalizeDispatcher(hook, catalogManager)),
        decorators);
  }
}
