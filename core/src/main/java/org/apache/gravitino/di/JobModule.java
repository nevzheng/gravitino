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
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.JobEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the job manager, its dispatcher chains and the listener that registers the built-in job
 * templates of new metalakes.
 *
 * <p>The {@link Internal} chain is validation → manager; the {@link Public} chain is event → hook →
 * that same validation dispatcher. Dagger does not close the manager; the {@link LifecycleModule
 * lifecycle} closes the public chain, which closes it.
 */
@SuppressWarnings("CloseableProvides")
@Module
abstract class JobModule {

  private JobModule() {}

  /**
   * Builds the job manager. Its constructor creates the job executor named by the configuration.
   */
  @Provides
  @Singleton
  static JobManager jobManager(
      Config config, EntityStore entityStore, IdGenerator idGenerator, LockManager lockManager) {
    return new JobManager(config, entityStore, idGenerator, lockManager);
  }

  @Provides
  @Singleton
  @Internal
  static JobOperationDispatcher internalJobOperationDispatcher(JobManager jobManager) {
    return new JobTemplateValidationDispatcher(jobManager);
  }

  @Provides
  @Singleton
  @Public
  static JobOperationDispatcher jobOperationDispatcher(
      EventBus eventBus, @Internal JobOperationDispatcher validation, BuiltServices built) {
    return built.add(
        BuiltServices.JOB_OPERATION_DISPATCHER,
        new JobEventDispatcher(eventBus, new JobHookDispatcher(validation)));
  }

  @Provides
  @Singleton
  static BuiltInJobTemplateEventListener builtInJobTemplateEventListener(
      JobManager jobManager,
      EntityStore entityStore,
      IdGenerator idGenerator,
      LockManager lockManager) {
    return new BuiltInJobTemplateEventListener(jobManager, entityStore, idGenerator, lockManager);
  }
}
