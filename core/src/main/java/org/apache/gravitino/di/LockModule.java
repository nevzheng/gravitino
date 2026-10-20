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
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.lock.LockManager;

/** Binds the tree lock manager. */
@Module
abstract class LockModule {

  private LockModule() {}

  /**
   * Builds the lock manager. Its constructor starts the lock cleaner and deadlock checker threads,
   * so it is built only when first requested; it is recorded in {@link BuiltServices}, and the
   * {@link LifecycleModule lifecycle} closes it, which stops those threads, after every other
   * service has stopped.
   */
  // The lifecycle closes the lock manager.
  @SuppressWarnings("CloseableProvides")
  @Provides
  @Singleton
  static LockManager lockManager(Config config, BuiltServices built) {
    return built.add(BuiltServices.LOCK_MANAGER, new LockManager(config));
  }
}
