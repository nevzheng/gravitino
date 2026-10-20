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
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizationServices;
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
 * that wraps that same manager instance. The manager is bound {@link Undecorated}, so only the
 * modules of this package can inject it:
 *
 * <ul>
 *   <li>tag and policy: event → hook → manager;
 *   <li>owner and statistic: event → manager.
 * </ul>
 *
 * <p>Each binding is wrapped in its {@link DispatcherDecorator decorators}; the public chain wraps
 * the undecorated manager, so the decorators of the internal binding do not appear in it.
 *
 * <p>The public bindings need the event bus, so they are only requested by the full server profile.
 * The owner manager is only built when authorization is {@link AuthorizationEnabled enabled}; while
 * it is disabled, both owner bindings are {@link NoopOwnerDispatcher}, wrapped in their decorators,
 * so they never yield {@code null} and never need the relational store the owner manager requires.
 */
// The statistic chains are closed by the component's lifecycle (LifecycleModule), not by Dagger.
@SuppressWarnings("CloseableProvides")
@Module
abstract class GovernanceModule {

  private GovernanceModule() {}

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<TagDispatcher>> internalTagDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<TagDispatcher>> tagDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<PolicyDispatcher>> internalPolicyDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<PolicyDispatcher>> policyDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<OwnerDispatcher>> internalOwnerDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<OwnerDispatcher>> ownerDecorators();

  @Multibinds
  @Internal
  abstract Set<DispatcherDecorator<StatisticDispatcher>> internalStatisticDecorators();

  @Multibinds
  @Public
  abstract Set<DispatcherDecorator<StatisticDispatcher>> statisticDecorators();

  @Provides
  @Singleton
  @Undecorated
  static TagManager tagManager(IdGenerator idGenerator, EntityStore entityStore) {
    return new TagManager(idGenerator, entityStore);
  }

  @Provides
  @Singleton
  @Internal
  static TagDispatcher internalTagDispatcher(
      @Undecorated TagManager tagManager,
      @Internal Set<DispatcherDecorator<TagDispatcher>> decorators) {
    return Decorators.apply("@Internal TagDispatcher", tagManager, decorators);
  }

  @Provides
  @Singleton
  @Public
  static TagDispatcher tagDispatcher(
      EventBus eventBus,
      @Undecorated TagManager tagManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<TagDispatcher>> decorators) {
    return Decorators.apply(
        "@Public TagDispatcher",
        new TagEventDispatcher(eventBus, new TagHookDispatcher(tagManager, authorizationServices)),
        decorators);
  }

  @Provides
  @Singleton
  @Undecorated
  static PolicyManager policyManager(IdGenerator idGenerator, EntityStore entityStore) {
    return new PolicyManager(idGenerator, entityStore);
  }

  @Provides
  @Singleton
  @Internal
  static PolicyDispatcher internalPolicyDispatcher(
      @Undecorated PolicyManager policyManager,
      @Internal Set<DispatcherDecorator<PolicyDispatcher>> decorators) {
    return Decorators.apply("@Internal PolicyDispatcher", policyManager, decorators);
  }

  @Provides
  @Singleton
  @Public
  static PolicyDispatcher policyDispatcher(
      EventBus eventBus,
      @Undecorated PolicyManager policyManager,
      AuthorizationServices authorizationServices,
      @Public Set<DispatcherDecorator<PolicyDispatcher>> decorators) {
    return Decorators.apply(
        "@Public PolicyDispatcher",
        new PolicyEventDispatcher(
            eventBus, new PolicyHookDispatcher(policyManager, authorizationServices)),
        decorators);
  }

  @Provides
  @Singleton
  @Undecorated
  static OwnerManager ownerManager(
      EntityStore entityStore, AuthorizationServices authorizationServices) {
    return new OwnerManager(entityStore, authorizationServices);
  }

  @Provides
  @Singleton
  @Internal
  static OwnerDispatcher internalOwnerDispatcher(
      @AuthorizationEnabled boolean authorizationEnabled,
      @Undecorated Provider<OwnerManager> ownerManager,
      @Internal Set<DispatcherDecorator<OwnerDispatcher>> decorators) {
    OwnerDispatcher owners =
        authorizationEnabled ? ownerManager.get() : NoopOwnerDispatcher.INSTANCE;
    return Decorators.apply("@Internal OwnerDispatcher", owners, decorators);
  }

  @Provides
  @Singleton
  @Public
  static OwnerDispatcher ownerDispatcher(
      @AuthorizationEnabled boolean authorizationEnabled,
      Provider<EventBus> eventBus,
      @Undecorated Provider<OwnerManager> ownerManager,
      @Public Set<DispatcherDecorator<OwnerDispatcher>> decorators) {
    OwnerDispatcher owners =
        authorizationEnabled
            ? new OwnerEventManager(eventBus.get(), ownerManager.get())
            : NoopOwnerDispatcher.INSTANCE;
    return Decorators.apply("@Public OwnerDispatcher", owners, decorators);
  }

  /**
   * Builds the statistic manager. Its constructor creates the partition statistics storage named by
   * the configuration.
   */
  @Provides
  @Singleton
  @Undecorated
  static StatisticManager statisticManager(
      EntityStore entityStore, IdGenerator idGenerator, Config config, LockManager lockManager) {
    return new StatisticManager(entityStore, idGenerator, config, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static StatisticDispatcher internalStatisticDispatcher(
      @Undecorated StatisticManager statisticManager,
      @Internal Set<DispatcherDecorator<StatisticDispatcher>> decorators,
      BuiltServices built) {
    return built.add(
        BuiltServices.INTERNAL_STATISTIC_DISPATCHER,
        Decorators.apply("@Internal StatisticDispatcher", statisticManager, decorators));
  }

  @Provides
  @Singleton
  @Public
  static StatisticDispatcher statisticDispatcher(
      EventBus eventBus,
      @Undecorated StatisticManager statisticManager,
      @Public Set<DispatcherDecorator<StatisticDispatcher>> decorators,
      BuiltServices built) {
    return built.add(
        BuiltServices.STATISTIC_DISPATCHER,
        Decorators.apply(
            "@Public StatisticDispatcher",
            new StatisticEventDispatcher(eventBus, statisticManager),
            decorators));
  }
}
