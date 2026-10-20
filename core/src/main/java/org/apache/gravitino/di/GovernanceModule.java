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
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.OwnerEventManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.listener.StatisticEventDispatcher;
import org.apache.gravitino.listener.TagEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.tag.TagManager;

/**
 * Binds the governance managers and their dispatcher chains: tags, policies, owners and statistics.
 *
 * <p>Each area has an {@link Internal} binding, the manager itself, and a {@link Public} binding
 * that wraps that same manager instance:
 *
 * <ul>
 *   <li>tag and policy: event → hook → manager;
 *   <li>owner and statistic: event → manager.
 * </ul>
 *
 * <p>The public bindings need the event bus, so they are only requested by the full server profile.
 * The owner manager is only built when authorization is {@link AuthorizationEnabled enabled}; while
 * it is disabled, both owner bindings are {@link NoopOwnerDispatcher}, wrapped in their decorators,
 * so they never yield {@code null} and never need the relational store the owner manager requires.
 */
// The statistic manager is closed by GravitinoEnv.shutdown(), not by the graph.
@SuppressWarnings("CloseableProvides")
@Module
abstract class GovernanceModule {

  private GovernanceModule() {}

  @Provides
  @Singleton
  @Internal
  static TagDispatcher internalTagDispatcher(IdGenerator idGenerator, EntityStore entityStore) {
    return new TagManager(idGenerator, entityStore);
  }

  @Provides
  @Singleton
  @Public
  static TagDispatcher tagDispatcher(EventBus eventBus, @Internal TagDispatcher internal) {
    return new TagEventDispatcher(eventBus, new TagHookDispatcher(internal));
  }

  @Provides
  @Singleton
  @Internal
  static PolicyDispatcher internalPolicyDispatcher(
      IdGenerator idGenerator, EntityStore entityStore) {
    return new PolicyManager(idGenerator, entityStore);
  }

  @Provides
  @Singleton
  @Public
  static PolicyDispatcher policyDispatcher(EventBus eventBus, @Internal PolicyDispatcher internal) {
    return new PolicyEventDispatcher(eventBus, new PolicyHookDispatcher(internal));
  }

  @Provides
  @Singleton
  @Internal
  static OwnerDispatcher internalOwnerDispatcher(
      @AuthorizationEnabled boolean authorizationEnabled, EntityStore entityStore) {
    return authorizationEnabled ? new OwnerManager(entityStore) : NoopOwnerDispatcher.INSTANCE;
  }

  @Provides
  @Singleton
  @Public
  static OwnerDispatcher ownerDispatcher(
      @AuthorizationEnabled boolean authorizationEnabled,
      Provider<EventBus> eventBus,
      @Internal OwnerDispatcher internal) {
    return authorizationEnabled
        ? new OwnerEventManager(eventBus.get(), internal)
        : NoopOwnerDispatcher.INSTANCE;
  }

  /**
   * Builds the statistic manager. Its constructor creates the partition statistics storage named by
   * the configuration.
   */
  @Provides
  @Singleton
  @Internal
  static StatisticDispatcher internalStatisticDispatcher(
      EntityStore entityStore, IdGenerator idGenerator, Config config, LockManager lockManager) {
    return new StatisticManager(entityStore, idGenerator, config, lockManager);
  }

  @Provides
  @Singleton
  @Public
  static StatisticDispatcher statisticDispatcher(
      EventBus eventBus, @Internal StatisticDispatcher internal) {
    return new StatisticEventDispatcher(eventBus, internal);
  }
}
