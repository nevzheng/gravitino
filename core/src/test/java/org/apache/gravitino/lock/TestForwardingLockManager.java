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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.NameIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestForwardingLockManager {

  private static final NameIdentifier IDENT = NameIdentifier.of("metalake", "catalog");

  @Test
  void testForwardsEachRequestToTheCurrentLockManager() {
    AtomicReference<LockManager> current = new AtomicReference<>();
    LockManager forwarding = new ForwardingLockManager(current::get);

    LockManager first = mock(LockManager.class);
    TreeLock firstLock = mock(TreeLock.class);
    when(first.createTreeLock(IDENT)).thenReturn(firstLock);
    current.set(first);
    Assertions.assertSame(firstLock, forwarding.createTreeLock(IDENT));

    LockManager second = mock(LockManager.class);
    TreeLock secondLock = mock(TreeLock.class);
    when(second.createTreeLock(LockManager.ROOT)).thenReturn(secondLock);
    current.set(second);
    // The root is matched by reference, so the same identifier object must reach the delegate.
    Assertions.assertSame(secondLock, forwarding.createTreeLock(LockManager.ROOT));
    verify(first).createTreeLock(IDENT);
    verify(second).createTreeLock(LockManager.ROOT);
  }

  @Test
  void testFailsClearlyWithoutALockManager() {
    LockManager forwarding = new ForwardingLockManager(() -> null);

    IllegalStateException e =
        Assertions.assertThrows(
            IllegalStateException.class, () -> forwarding.createTreeLock(IDENT));
    Assertions.assertEquals("No lock manager is available", e.getMessage());
  }

  @Test
  void testDoWithTreeLockRunsThroughTheForwardedLock() throws Exception {
    LockManager delegate = mock(LockManager.class);
    TreeLock treeLock = mock(TreeLock.class);
    when(delegate.createTreeLock(IDENT)).thenReturn(treeLock);

    Assertions.assertEquals(
        "done",
        TreeLockUtils.doWithTreeLock(
            new ForwardingLockManager(() -> delegate), IDENT, LockType.WRITE, () -> "done"));
    verify(treeLock).lock(LockType.WRITE);
    verify(treeLock).unlock();
  }
}
