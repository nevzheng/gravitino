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

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TagEventDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.tag.TagManager;

/** Package-private composition boundary for the tag service graph. */
final class TagServices {

  private final TagComponent component;

  private TagServices(TagComponent component) {
    this.component = component;
  }

  static TagServices create(
      EntityStore entityStore,
      IdGenerator idGenerator,
      EventBus eventBus,
      @Nullable OwnerDispatcher ownerDispatcher) {
    return new TagServices(
        DaggerTagServices_TagComponent.factory()
            .create(entityStore, idGenerator, eventBus, ownerDispatcher));
  }

  TagDispatcher tagDispatcher() {
    return component.tagDispatcher();
  }

  @Singleton
  @Component(modules = TagModule.class)
  interface TagComponent {

    TagDispatcher tagDispatcher();

    @Component.Factory
    interface Factory {

      TagComponent create(
          @BindsInstance EntityStore entityStore,
          @BindsInstance IdGenerator idGenerator,
          @BindsInstance EventBus eventBus,
          @BindsInstance @Nullable OwnerDispatcher ownerDispatcher);
    }
  }

  @Module
  static final class TagModule {

    private TagModule() {}

    @Provides
    @Singleton
    static TagManager provideTagManager(IdGenerator idGenerator, EntityStore entityStore) {
      return new TagManager(idGenerator, entityStore);
    }

    @Provides
    @Singleton
    static TagEventDispatcher provideTagEventDispatcher(EventBus eventBus, TagManager tagManager) {
      return new TagEventDispatcher(eventBus, tagManager);
    }

    @Provides
    @Singleton
    static TagDispatcher provideTagHookDispatcher(
        TagEventDispatcher eventDispatcher, @Nullable OwnerDispatcher ownerDispatcher) {
      return new TagHookDispatcher(eventDispatcher, ownerDispatcher);
    }
  }
}
