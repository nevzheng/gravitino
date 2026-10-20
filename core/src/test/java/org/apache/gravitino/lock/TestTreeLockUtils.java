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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.function.Supplier;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class TestTreeLockUtils {

  private static final NameIdentifier IDENT = NameIdentifier.of("metalake", "catalog");

  private final GravitinoEnv env = GravitinoEnv.getInstance();

  private Object savedEnvLockManager;
  private LockManager lockManager;
  private TreeLock treeLock;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    savedEnvLockManager = FieldUtils.readField(env, "lockManager", true);
    lockManager = mock(LockManager.class);
    treeLock = mock(TreeLock.class);
    when(lockManager.createTreeLock(IDENT)).thenReturn(treeLock);
    when(lockManager.createTreeLock(LockManager.ROOT)).thenReturn(treeLock);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    FieldUtils.writeField(env, "lockManager", savedEnvLockManager, true);
  }

  @Test
  void testHolderMultipleLock() throws Exception {
    Config config = mock(Config.class);
    doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    LockManager realLockManager = new LockManager(config);

    TreeLockUtils.doWithTreeLock(
        realLockManager,
        NameIdentifier.of("test"),
        LockType.READ,
        () ->
            TreeLockUtils.doWithTreeLock(
                realLockManager, NameIdentifier.of("test", "test1"), LockType.WRITE, () -> null));
  }

  @Test
  void testDoWithTreeLockLocksRunsAndUnlocks() throws Exception {
    String result =
        TreeLockUtils.doWithTreeLock(
            lockManager,
            IDENT,
            LockType.WRITE,
            () -> {
              verify(treeLock).lock(LockType.WRITE);
              verify(treeLock, never()).unlock();
              return "done";
            });

    Assertions.assertEquals("done", result);
    InOrder order = inOrder(lockManager, treeLock);
    order.verify(lockManager).createTreeLock(IDENT);
    order.verify(treeLock).lock(LockType.WRITE);
    order.verify(treeLock).unlock();
  }

  @Test
  void testDoWithTreeLockUnlocksWhenExecutableThrows() {
    IOException expected = new IOException("boom");

    IOException thrown =
        Assertions.assertThrows(
            IOException.class,
            () ->
                TreeLockUtils.doWithTreeLock(
                    lockManager,
                    IDENT,
                    LockType.READ,
                    () -> {
                      throw expected;
                    }));

    Assertions.assertSame(expected, thrown);
    verify(treeLock).lock(LockType.READ);
    verify(treeLock).unlock();
  }

  @Test
  void testDoWithRootTreeLockLocksTheRoot() throws Exception {
    Integer result = TreeLockUtils.doWithRootTreeLock(lockManager, LockType.READ, () -> 42);

    Assertions.assertEquals(42, result);
    verify(lockManager).createTreeLock(LockManager.ROOT);
    verify(treeLock).lock(LockType.READ);
    verify(treeLock).unlock();
  }

  @Test
  void testNullLockManagerIsRejected() {
    Assertions.assertThrows(
        NullPointerException.class,
        () -> TreeLockUtils.doWithTreeLock(null, IDENT, LockType.READ, () -> null));
    Assertions.assertThrows(
        NullPointerException.class,
        () -> TreeLockUtils.doWithRootTreeLock(null, LockType.READ, () -> null));
  }

  // The legacy overloads stay for callers outside this repository; this pins their behavior.
  @SuppressWarnings("deprecation")
  @Test
  void testLegacyOverloadsUseTheEnvLockManager() throws Exception {
    FieldUtils.writeField(env, "lockManager", lockManager, true);

    Assertions.assertEquals(
        "tree", TreeLockUtils.doWithTreeLock(IDENT, LockType.WRITE, () -> "tree"));
    Assertions.assertEquals("root", TreeLockUtils.doWithRootTreeLock(LockType.READ, () -> "root"));

    verify(lockManager).createTreeLock(IDENT);
    verify(lockManager).createTreeLock(LockManager.ROOT);
    verify(treeLock).lock(LockType.WRITE);
    verify(treeLock).lock(LockType.READ);
  }

  @SuppressWarnings("deprecation")
  @Test
  void testLegacyLockManagerResolvesOnEachCall() throws Exception {
    Supplier<LockManager> supplier = TreeLockUtils.legacyLockManager();

    FieldUtils.writeField(env, "lockManager", lockManager, true);
    Assertions.assertSame(lockManager, supplier.get());

    LockManager replacement = mock(LockManager.class);
    FieldUtils.writeField(env, "lockManager", replacement, true);
    Assertions.assertSame(replacement, supplier.get());
  }
}
