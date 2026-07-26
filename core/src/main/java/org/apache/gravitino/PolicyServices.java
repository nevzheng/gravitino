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
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.storage.IdGenerator;

/** Package-private composition boundary for the policy service graph. */
final class PolicyServices {

  private final PolicyComponent component;

  private PolicyServices(PolicyComponent component) {
    this.component = component;
  }

  static PolicyServices create(
      EntityStore entityStore,
      IdGenerator idGenerator,
      EventBus eventBus,
      LockManager lockManager,
      @Nullable OwnerDispatcher ownerDispatcher) {
    return new PolicyServices(
        DaggerPolicyServices_PolicyComponent.factory()
            .create(entityStore, idGenerator, eventBus, lockManager, ownerDispatcher));
  }

  PolicyDispatcher policyDispatcher() {
    return component.policyDispatcher();
  }

  PolicyManager policyManager() {
    return component.policyManager();
  }

  PolicyEventDispatcher policyEventDispatcher() {
    return component.policyEventDispatcher();
  }

  @Singleton
  @Component(modules = PolicyModule.class)
  interface PolicyComponent {

    PolicyDispatcher policyDispatcher();

    PolicyManager policyManager();

    PolicyEventDispatcher policyEventDispatcher();

    @Component.Factory
    interface Factory {

      PolicyComponent create(
          @BindsInstance EntityStore entityStore,
          @BindsInstance IdGenerator idGenerator,
          @BindsInstance EventBus eventBus,
          @BindsInstance LockManager lockManager,
          @BindsInstance @Nullable OwnerDispatcher ownerDispatcher);
    }
  }

  @Module
  static final class PolicyModule {

    private PolicyModule() {}

    @Provides
    @Singleton
    static PolicyManager providePolicyManager(
        IdGenerator idGenerator, EntityStore entityStore, LockManager lockManager) {
      return new PolicyManager(idGenerator, entityStore, lockManager);
    }

    @Provides
    @Singleton
    static PolicyEventDispatcher providePolicyEventDispatcher(
        EventBus eventBus, PolicyManager policyManager) {
      return new PolicyEventDispatcher(eventBus, policyManager);
    }

    @Provides
    @Singleton
    static PolicyDispatcher providePolicyHookDispatcher(
        PolicyEventDispatcher eventDispatcher, @Nullable OwnerDispatcher ownerDispatcher) {
      return new PolicyHookDispatcher(eventDispatcher, ownerDispatcher);
    }
  }
}
