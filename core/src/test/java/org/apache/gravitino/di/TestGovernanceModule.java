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

import static org.apache.gravitino.di.DispatcherChains.assertChain;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.NoopOwnerDispatcher;
import org.apache.gravitino.authorization.OwnerEventManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.listener.StatisticEventDispatcher;
import org.apache.gravitino.listener.TagEventDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.tag.TagManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestGovernanceModule {

  private FakeLeaves leaves;
  private ModuleTestComponent component;

  @BeforeEach
  public void setUp() {
    leaves = new FakeLeaves();
    component = ModuleTestComponent.create(TestGravitinoComponent.testConfig(), leaves);
  }

  @AfterEach
  public void tearDown() throws Exception {
    component.internalStatisticDispatcher().close();
  }

  @Test
  public void testTagChains() throws Exception {
    assertChain(component.internalTagDispatcher(), TagManager.class);
    Object manager =
        assertChain(
            component.tagDispatcher(),
            TagEventDispatcher.class,
            TagHookDispatcher.class,
            TagManager.class);
    assertSame(component.internalTagDispatcher(), manager);
  }

  @Test
  public void testPolicyChains() throws Exception {
    assertChain(component.internalPolicyDispatcher(), PolicyManager.class);
    Object manager =
        assertChain(
            component.policyDispatcher(),
            PolicyEventDispatcher.class,
            PolicyHookDispatcher.class,
            PolicyManager.class);
    assertSame(component.internalPolicyDispatcher(), manager);
  }

  @Test
  public void testOwnerChains() throws Exception {
    ModuleTestComponent authorized = authorizedComponent(leaves);

    assertChain(authorized.internalOwnerDispatcher(), OwnerManager.class);
    Object manager =
        assertChain(
            authorized.ownerDispatcher(),
            "ownerDispatcher",
            OwnerEventManager.class,
            OwnerManager.class);
    assertSame(authorized.internalOwnerDispatcher(), manager);
    assertSame(authorized.ownerDispatcher(), authorized.ownerDispatcher());
    assertNotSame(authorized.ownerDispatcher(), authorized.internalOwnerDispatcher());
  }

  @Test
  public void testOwnerChainsAreNoopWhileAuthorizationIsDisabled() {
    // Neither chain builds the owner manager, so neither needs a relational store or the event bus.
    FakeLeaves plainStore = new FakeLeaves();
    plainStore.entityStore = mock(EntityStore.class);
    plainStore.eventBus = null;
    ModuleTestComponent disabled =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), plainStore);

    assertSame(NoopOwnerDispatcher.INSTANCE, disabled.internalOwnerDispatcher());
    assertSame(NoopOwnerDispatcher.INSTANCE, disabled.ownerDispatcher());
  }

  @Test
  public void testStatisticChains() throws Exception {
    assertChain(component.internalStatisticDispatcher(), StatisticManager.class);
    Object manager =
        assertChain(
            component.statisticDispatcher(),
            StatisticEventDispatcher.class,
            StatisticManager.class);
    assertSame(component.internalStatisticDispatcher(), manager);
  }

  @Test
  public void testPublicAndInternalBindingsAreDistinctSingletons() {
    assertSame(component.tagDispatcher(), component.tagDispatcher());
    assertSame(component.internalTagDispatcher(), component.internalTagDispatcher());
    assertNotSame(component.tagDispatcher(), component.internalTagDispatcher());
    assertSame(component.policyDispatcher(), component.policyDispatcher());
    assertNotSame(component.policyDispatcher(), component.internalPolicyDispatcher());
    assertSame(component.statisticDispatcher(), component.statisticDispatcher());
    assertNotSame(component.statisticDispatcher(), component.internalStatisticDispatcher());
  }

  @Test
  public void testInternalBindingsDoNotNeedTheEventBus() throws Exception {
    // The metadata profile has no event bus; its internal chains must still build.
    FakeLeaves noEventBus = new FakeLeaves();
    noEventBus.eventBus = null;
    ModuleTestComponent metadataOnly =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), noEventBus);
    try {
      metadataOnly.internalTagDispatcher();
      metadataOnly.internalPolicyDispatcher();
      metadataOnly.internalOwnerDispatcher();
      assertThrows(NullPointerException.class, metadataOnly::tagDispatcher);
    } finally {
      metadataOnly.internalStatisticDispatcher().close();
    }
  }

  @Test
  public void testRelationalStoreIsRequired() {
    FakeLeaves plainStore = new FakeLeaves();
    plainStore.entityStore = mock(EntityStore.class);
    ModuleTestComponent plain =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), plainStore);
    assertThrows(RuntimeException.class, plain::internalPolicyDispatcher);

    ModuleTestComponent authorized = authorizedComponent(plainStore);
    assertThrows(RuntimeException.class, authorized::internalOwnerDispatcher);
  }

  private static ModuleTestComponent authorizedComponent(FakeLeaves leaves) {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    return ModuleTestComponent.create(config, leaves);
  }
}
