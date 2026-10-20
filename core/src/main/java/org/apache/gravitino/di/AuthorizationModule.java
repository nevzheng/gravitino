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
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.hook.AccessControlHookDispatcher;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the access-control components: the access control manager and its public chain, the bulk
 * manager and the future grant manager.
 *
 * <p>The {@link Internal} access-control binding is the manager itself; the {@link Public} one is
 * event → hook → that manager. {@code GravitinoEnv} requests these objects only when authorization
 * is enabled and keeps {@code null} in their place otherwise, so that callers which test for a
 * disabled feature behave as before. The owner manager and its chains are in {@link
 * GovernanceModule}.
 */
@Module
abstract class AuthorizationModule {

  private AuthorizationModule() {}

  @Provides
  @Singleton
  @Internal
  static AccessControlDispatcher internalAccessControlDispatcher(
      EntityStore entityStore, IdGenerator idGenerator, Config config, LockManager lockManager) {
    return new AccessControlManager(entityStore, idGenerator, config, lockManager);
  }

  @Provides
  @Singleton
  @Public
  static AccessControlDispatcher accessControlDispatcher(
      EventBus eventBus, @Internal AccessControlDispatcher internal) {
    return new AccessControlEventDispatcher(eventBus, new AccessControlHookDispatcher(internal));
  }

  @Provides
  @Singleton
  static BulkManager bulkManager(Config config) {
    return new BulkManager(config);
  }

  @Provides
  @Singleton
  static FutureGrantManager futureGrantManager(
      EntityStore entityStore, @Internal OwnerDispatcher ownerDispatcher) {
    return new FutureGrantManager(entityStore, ownerDispatcher);
  }
}
