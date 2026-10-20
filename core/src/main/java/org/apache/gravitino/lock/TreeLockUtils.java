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

import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.Executable;

/**
 * Utility class for tree locks.
 *
 * <p>Callers pass the {@link LockManager} they hold, usually one they received through their
 * constructor. The overloads without a {@code LockManager} read it from {@code GravitinoEnv} on
 * every call and remain only for callers outside this repository.
 */
public class TreeLockUtils {

  private TreeLockUtils() {
    // Prevent instantiation.
  }

  /**
   * Execute the given executable with the given tree lock.
   *
   * @param lockManager The lock manager that creates the tree lock.
   * @param identifier The identifier of resource path that the lock attempts to lock.
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithTreeLock(
      LockManager lockManager,
      NameIdentifier identifier,
      LockType lockType,
      Executable<R, E> executable)
      throws E {
    Preconditions.checkNotNull(lockManager, "lockManager must not be null");
    TreeLock lock = lockManager.createTreeLock(identifier);
    try {
      lock.lock(lockType);
      return executable.execute();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Execute the given executable with the root tree lock.
   *
   * @param lockManager The lock manager that creates the tree lock.
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithRootTreeLock(
      LockManager lockManager, LockType lockType, Executable<R, E> executable) throws E {
    return doWithTreeLock(lockManager, LockManager.ROOT, lockType, executable);
  }

  /**
   * Execute the given executable with the given tree lock, using the lock manager of {@code
   * GravitinoEnv}.
   *
   * @param identifier The identifier of resource path that the lock attempts to lock.
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithTreeLock(
      NameIdentifier identifier, LockType lockType, Executable<R, E> executable) throws E {
    return doWithTreeLock(legacyLockManager().get(), identifier, lockType, executable);
  }

  /**
   * Execute the given executable with the root tree lock, using the lock manager of {@code
   * GravitinoEnv}.
   *
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithRootTreeLock(
      LockType lockType, Executable<R, E> executable) throws E {
    return doWithTreeLock(LockManager.ROOT, lockType, executable);
  }

  /**
   * Returns a supplier that reads the lock manager from {@code GravitinoEnv} each time it is
   * called.
   *
   * <p>This is the single legacy lookup of the lock manager. It exists for the deprecated
   * constructors that do not take a {@link LockManager}, so they resolve it at call time exactly as
   * before. New code takes the lock manager as a constructor parameter instead.
   *
   * @return A supplier of the lock manager held by {@code GravitinoEnv}.
   * @deprecated Take the {@link LockManager} as a constructor parameter.
   */
  // Inlining would copy the GravitinoEnv lookup into every caller.
  @SuppressWarnings("InlineMeSuggester")
  @Deprecated
  public static Supplier<LockManager> legacyLockManager() {
    return () -> GravitinoEnv.getInstance().lockManager();
  }
}
