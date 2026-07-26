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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metadata.MetadataObjectExistenceChecker;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Test;

public class TestPolicyServices {

  @Test
  void testComponentGeneratesOnePolicyGraphPerApplicationGraph() {
    EntityStore entityStore = relationalEntityStore();
    IdGenerator idGenerator = mock(IdGenerator.class);
    EventBus eventBus = mock(EventBus.class);
    LockManager lockManager = mock(LockManager.class);
    OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);
    MetadataObjectExistenceChecker metadataObjectExistenceChecker =
        mock(MetadataObjectExistenceChecker.class);

    PolicyServices services =
        PolicyServices.create(
            entityStore,
            idGenerator,
            eventBus,
            lockManager,
            metadataObjectExistenceChecker,
            ownerDispatcher);
    PolicyDispatcher policyDispatcher = services.policyDispatcher();

    assertInstanceOf(PolicyHookDispatcher.class, policyDispatcher);
    assertInstanceOf(PolicyManager.class, services.policyManager());
    assertInstanceOf(PolicyEventDispatcher.class, services.policyEventDispatcher());
    assertSame(policyDispatcher, services.policyDispatcher());
    assertSame(services.policyManager(), services.policyManager());
    assertSame(services.policyEventDispatcher(), services.policyEventDispatcher());
    assertSame(metadataObjectExistenceChecker, services.metadataObjectExistenceChecker());
    verifyNoInteractions(
        entityStore,
        idGenerator,
        eventBus,
        lockManager,
        metadataObjectExistenceChecker,
        ownerDispatcher);
  }

  @Test
  void testSeparateApplicationGraphsDoNotSharePolicyServices() {
    EntityStore entityStore = relationalEntityStore();
    IdGenerator idGenerator = mock(IdGenerator.class);
    EventBus eventBus = mock(EventBus.class);
    LockManager lockManager = mock(LockManager.class);
    MetadataObjectExistenceChecker metadataObjectExistenceChecker =
        mock(MetadataObjectExistenceChecker.class);

    PolicyServices first =
        PolicyServices.create(
            entityStore, idGenerator, eventBus, lockManager, metadataObjectExistenceChecker, null);
    PolicyServices second =
        PolicyServices.create(
            entityStore, idGenerator, eventBus, lockManager, metadataObjectExistenceChecker, null);

    assertNotSame(first.policyDispatcher(), second.policyDispatcher());
    assertNotSame(first.policyManager(), second.policyManager());
    assertNotSame(first.policyEventDispatcher(), second.policyEventDispatcher());
    assertSame(first.metadataObjectExistenceChecker(), second.metadataObjectExistenceChecker());
  }

  private static EntityStore relationalEntityStore() {
    return mock(
        EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
  }
}
