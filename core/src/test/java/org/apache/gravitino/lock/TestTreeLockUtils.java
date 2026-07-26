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

package org.apache.gravitino.lock;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class TestTreeLockUtils {

  @Test
  void testHolderMultipleLock() throws Exception {
    Config config = mock(Config.class);
    doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    LockManager lockManager = new LockManager(config);

    TreeLockUtils.doWithTreeLock(
        lockManager,
        NameIdentifier.of("test"),
        LockType.READ,
        () ->
            TreeLockUtils.doWithTreeLock(
                lockManager, NameIdentifier.of("test", "test1"), LockType.WRITE, () -> null));
  }

  @Test
  void testExplicitLockManagerUnlocksWhenExecutableFails() {
    LockManager lockManager = mock(LockManager.class);
    TreeLock treeLock = mock(TreeLock.class);
    NameIdentifier identifier = NameIdentifier.of("test");
    RuntimeException failure = new RuntimeException("test failure");
    when(lockManager.createTreeLock(identifier)).thenReturn(treeLock);

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                TreeLockUtils.doWithTreeLock(
                    lockManager,
                    identifier,
                    LockType.WRITE,
                    () -> {
                      throw failure;
                    }));

    assertSame(failure, thrown);
    InOrder operations = inOrder(lockManager, treeLock);
    operations.verify(lockManager).createTreeLock(identifier);
    operations.verify(treeLock).lock(LockType.WRITE);
    operations.verify(treeLock).unlock();
  }
}
