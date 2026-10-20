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

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.Set;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.hook.AccessControlHookDispatcher;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the access-control components: the access control manager and its chains. The bulk manager
 * and the future grant manager have {@code @Inject} constructors, so they need no binding here.
 *
 * <p>The {@link Internal} access-control binding is the manager itself; the {@link Public} one is
 * event → hook → that manager. Each is wrapped in its {@link DispatcherDecorator decorators}; the
 * public chain wraps the undecorated manager. {@code GravitinoEnv} requests these objects only when
 * authorization is enabled and keeps {@code null} in their place otherwise, so that callers which
 * test for a disabled feature behave as before. The owner manager and its chains are in {@link
 * GovernanceModule}.
 */
@Module
abstract class AuthorizationModule {

  private AuthorizationModule() {}

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<AccessControlDispatcher>> internalAccessControlDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<AccessControlDispatcher>> accessControlDecorators();

  @Provides
  @Singleton
  @Undecorated
  static AccessControlManager accessControlManager(
      EntityStore entityStore, IdGenerator idGenerator, Config config, LockManager lockManager) {
    return new AccessControlManager(entityStore, idGenerator, config, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static AccessControlDispatcher internalAccessControlDispatcher(
      @Undecorated AccessControlManager accessControlManager,
      @Internal Set<DispatcherDecorator<AccessControlDispatcher>> decorators) {
    return Decorators.apply("@Internal AccessControlDispatcher", accessControlManager, decorators);
  }

  @Provides
  @Singleton
  @Public
  static AccessControlDispatcher accessControlDispatcher(
      EventBus eventBus,
      @Undecorated AccessControlManager accessControlManager,
      @Public Set<DispatcherDecorator<AccessControlDispatcher>> decorators) {
    return Decorators.apply(
        "@Public AccessControlDispatcher",
        new AccessControlEventDispatcher(
            eventBus, new AccessControlHookDispatcher(accessControlManager)),
        decorators);
  }

  @Binds
  abstract AuthorizationServices authorizationServices(ComponentAuthorizationServices services);
}
