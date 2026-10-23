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
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.OwnerEventManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.lifecycle.ManagedServices;
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
 *
 * <p>{@link Chain} assembles each chain from its layers and the {@link DispatcherDecorator
 * decorators} contributed for it, keyed by {@link Layer}; upstream contributes none.
 */
// The statistic manager registers with the ManagedServices registry, which closes it.
@SuppressWarnings("CloseableProvides")
@Module
abstract class GovernanceModule {

  private GovernanceModule() {}

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<TagDispatcher>> internalTagDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<TagDispatcher>> tagDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<PolicyDispatcher>> internalPolicyDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<PolicyDispatcher>> policyDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<OwnerDispatcher>> internalOwnerDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<OwnerDispatcher>> ownerDecorators();

  @Multibinds
  @Internal
  abstract Map<Layer, DispatcherDecorator<StatisticDispatcher>> internalStatisticDecorators();

  @Multibinds
  @Public
  abstract Map<Layer, DispatcherDecorator<StatisticDispatcher>> statisticDecorators();

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
      @Internal Map<Layer, DispatcherDecorator<TagDispatcher>> decorators) {
    return Chain.<TagDispatcher>of("@Internal TagDispatcher", tagManager, decorators).build();
  }

  @Provides
  @Singleton
  @Public
  static TagDispatcher tagDispatcher(
      EventBus eventBus,
      @Undecorated TagManager tagManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<TagDispatcher>> decorators) {
    return Chain.<TagDispatcher>of("@Public TagDispatcher", tagManager, decorators)
        .hook(d -> new TagHookDispatcher(d, authorizationServices))
        .event(d -> new TagEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<PolicyDispatcher>> decorators) {
    return Chain.<PolicyDispatcher>of("@Internal PolicyDispatcher", policyManager, decorators)
        .build();
  }

  @Provides
  @Singleton
  @Public
  static PolicyDispatcher policyDispatcher(
      EventBus eventBus,
      @Undecorated PolicyManager policyManager,
      AuthorizationServices authorizationServices,
      @Public Map<Layer, DispatcherDecorator<PolicyDispatcher>> decorators) {
    return Chain.<PolicyDispatcher>of("@Public PolicyDispatcher", policyManager, decorators)
        .hook(d -> new PolicyHookDispatcher(d, authorizationServices))
        .event(d -> new PolicyEventDispatcher(eventBus, d))
        .build();
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
      @Internal Map<Layer, DispatcherDecorator<OwnerDispatcher>> decorators) {
    OwnerDispatcher owners =
        authorizationEnabled ? ownerManager.get() : NoopOwnerDispatcher.INSTANCE;
    return Chain.of("@Internal OwnerDispatcher", owners, decorators).build();
  }

  @Provides
  @Singleton
  @Public
  static OwnerDispatcher ownerDispatcher(
      @AuthorizationEnabled boolean authorizationEnabled,
      Provider<EventBus> eventBus,
      @Undecorated Provider<OwnerManager> ownerManager,
      @Public Map<Layer, DispatcherDecorator<OwnerDispatcher>> decorators) {
    if (!authorizationEnabled) {
      return Chain.of("@Public OwnerDispatcher", NoopOwnerDispatcher.INSTANCE, decorators).build();
    }
    return Chain.<OwnerDispatcher>of("@Public OwnerDispatcher", ownerManager.get(), decorators)
        .event(d -> new OwnerEventManager(eventBus.get(), d))
        .build();
  }

  /**
   * Builds the statistic manager. Its constructor creates the partition statistics storage named by
   * the configuration.
   */
  @Provides
  @Singleton
  @Undecorated
  static StatisticManager statisticManager(
      EntityStore entityStore,
      IdGenerator idGenerator,
      Config config,
      LockManager lockManager,
      ManagedServices services) {
    return services.register(new StatisticManager(entityStore, idGenerator, config, lockManager));
  }

  @Provides
  @Singleton
  @Internal
  static StatisticDispatcher internalStatisticDispatcher(
      @Undecorated StatisticManager statisticManager,
      @Internal Map<Layer, DispatcherDecorator<StatisticDispatcher>> decorators) {
    return Chain.<StatisticDispatcher>of(
            "@Internal StatisticDispatcher", statisticManager, decorators)
        .build();
  }

  @Provides
  @Singleton
  @Public
  static StatisticDispatcher statisticDispatcher(
      EventBus eventBus,
      @Undecorated StatisticManager statisticManager,
      @Public Map<Layer, DispatcherDecorator<StatisticDispatcher>> decorators) {
    return Chain.<StatisticDispatcher>of(
            "@Public StatisticDispatcher", statisticManager, decorators)
        .event(d -> new StatisticEventDispatcher(eventBus, d))
        .build();
  }
}
