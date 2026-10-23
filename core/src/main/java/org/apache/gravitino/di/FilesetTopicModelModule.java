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
 * event → normalize → hook → operation. The fileset, topic, model and function chains share one
 * operation dispatcher per type; the public semantic model chain builds its own, as {@code
 * GravitinoEnv} did. Function and semantic model operations work on schemas through the shared
 * {@link SchemaOperationDispatcher} from {@link SchemaTableModule}.
 *
 * <p>{@link Chain} assembles each chain from its layers and the {@link DispatcherDecorator
 * decorators} contributed for it, keyed by {@link Layer}; upstream contributes none.
 */
@Module
abstract class FilesetTopicModelModule {

  private FilesetTopicModelModule() {}

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<FilesetDispatcher>> internalFilesetDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<FilesetDispatcher>> filesetDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<TopicDispatcher>> internalTopicDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<TopicDispatcher>> topicDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<ModelDispatcher>> internalModelDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<ModelDispatcher>> modelDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<FunctionDispatcher>> internalFunctionDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<FunctionDispatcher>> functionDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<SemanticModelDispatcher>>
      internalSemanticModelDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<SemanticModelDispatcher>> semanticModelDecorators();

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
      @Internal Map<Layer, DispatcherDecorator<FilesetDispatcher>> decorators) {
    return Chain.<FilesetDispatcher>of("@Internal FilesetDispatcher", operations, decorators)
        .normalize(d -> new FilesetNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static FilesetDispatcher filesetDispatcher(
      EventBus eventBus,
      @Undecorated FilesetOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<FilesetDispatcher>> decorators) {
    return Chain.<FilesetDispatcher>of("@Public FilesetDispatcher", operations, decorators)
        .hook(d -> new FilesetHookDispatcher(d, authorizationServices))
        .normalize(d -> new FilesetNormalizeDispatcher(d, catalogManager))
        .event(d -> new FilesetEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<TopicDispatcher>> decorators) {
    return Chain.<TopicDispatcher>of("@Internal TopicDispatcher", operations, decorators)
        .normalize(d -> new TopicNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static TopicDispatcher topicDispatcher(
      EventBus eventBus,
      @Undecorated TopicOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<TopicDispatcher>> decorators) {
    return Chain.<TopicDispatcher>of("@Public TopicDispatcher", operations, decorators)
        .hook(d -> new TopicHookDispatcher(d, authorizationServices))
        .normalize(d -> new TopicNormalizeDispatcher(d, catalogManager))
        .event(d -> new TopicEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<ModelDispatcher>> decorators) {
    return Chain.<ModelDispatcher>of("@Internal ModelDispatcher", operations, decorators)
        .normalize(d -> new ModelNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static ModelDispatcher modelDispatcher(
      EventBus eventBus,
      @Undecorated ModelOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<ModelDispatcher>> decorators) {
    return Chain.<ModelDispatcher>of("@Public ModelDispatcher", operations, decorators)
        .hook(d -> new ModelHookDispatcher(d, authorizationServices))
        .normalize(d -> new ModelNormalizeDispatcher(d, catalogManager))
        .event(d -> new ModelEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<FunctionDispatcher>> decorators) {
    return Chain.<FunctionDispatcher>of("@Internal FunctionDispatcher", operations, decorators)
        .normalize(d -> new FunctionNormalizeDispatcher(d, catalogManager))
        .build();
  }

  @Provides
  @Singleton
  @Public
  static FunctionDispatcher functionDispatcher(
      EventBus eventBus,
      @Undecorated FunctionOperationDispatcher operations,
      @Undecorated CatalogManager catalogManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<FunctionDispatcher>> decorators) {
    return Chain.<FunctionDispatcher>of("@Public FunctionDispatcher", operations, decorators)
        .hook(d -> new FunctionHookDispatcher(d, authorizationServices))
        .normalize(d -> new FunctionNormalizeDispatcher(d, catalogManager))
        .event(d -> new FunctionEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<SemanticModelDispatcher>> decorators) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    return Chain.<SemanticModelDispatcher>of(
            "@Internal SemanticModelDispatcher", operations, decorators)
        .normalize(d -> new SemanticModelNormalizeDispatcher(d, catalogManager))
        .build();
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
      @Public Map<Layer, DispatcherDecorator<SemanticModelDispatcher>> decorators) {
    SemanticModelOperationDispatcher operations =
        new SemanticModelOperationDispatcher(
            catalogManager, schemaOperations, entityStore, idGenerator, secretManager);
    return Chain.<SemanticModelDispatcher>of(
            "@Public SemanticModelDispatcher", operations, decorators)
        .hook(d -> new SemanticModelHookDispatcher(d, authorizationServices))
        .normalize(d -> new SemanticModelNormalizeDispatcher(d, catalogManager))
        .event(d -> new SemanticModelEventDispatcher(eventBus, d))
        .build();
  }
}
