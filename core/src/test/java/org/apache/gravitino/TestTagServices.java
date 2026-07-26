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

import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metadata.MetadataObjectExistenceChecker;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Test;

public class TestTagServices {

  @Test
  void testComponentGeneratesOneTagGraphPerApplicationGraph() {
    EntityStore entityStore = mock(EntityStore.class);
    IdGenerator idGenerator = mock(IdGenerator.class);
    EventBus eventBus = mock(EventBus.class);
    LockManager lockManager = mock(LockManager.class);
    OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);
    MetadataObjectExistenceChecker metadataObjectExistenceChecker =
        mock(MetadataObjectExistenceChecker.class);

    TagServices services =
        TagServices.create(
            entityStore,
            idGenerator,
            eventBus,
            lockManager,
            metadataObjectExistenceChecker,
            ownerDispatcher);
    TagDispatcher tagDispatcher = services.tagDispatcher();

    assertInstanceOf(TagHookDispatcher.class, tagDispatcher);
    assertSame(tagDispatcher, services.tagDispatcher());
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
  void testSeparateApplicationGraphsDoNotShareTagServices() {
    EntityStore entityStore = mock(EntityStore.class);
    IdGenerator idGenerator = mock(IdGenerator.class);
    EventBus eventBus = mock(EventBus.class);
    LockManager lockManager = mock(LockManager.class);
    MetadataObjectExistenceChecker metadataObjectExistenceChecker =
        mock(MetadataObjectExistenceChecker.class);

    TagServices first =
        TagServices.create(
            entityStore, idGenerator, eventBus, lockManager, metadataObjectExistenceChecker, null);
    TagServices second =
        TagServices.create(
            entityStore, idGenerator, eventBus, lockManager, metadataObjectExistenceChecker, null);

    assertNotSame(first.tagDispatcher(), second.tagDispatcher());
    assertSame(first.metadataObjectExistenceChecker(), second.metadataObjectExistenceChecker());
  }
}
