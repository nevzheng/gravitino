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

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;

/**
 * Binds the auxiliary service manager and the {@link AuxiliaryContext} it hands to every auxiliary
 * service.
 *
 * <p>The context is separate from the component: a service sees only the host services the context
 * offers, never the component itself. Dagger does not start or stop the services; the {@link
 * LifecycleModule lifecycle} does, after every other service of the component has started.
 */
@Module
abstract class AuxiliaryModule {

  private AuxiliaryModule() {}

  @Binds
  @Singleton
  abstract AuxiliaryContext auxiliaryContext(ComponentAuxiliaryContext context);

  /**
   * Builds the manager, then loads and initializes the auxiliary services the configuration names.
   * The manager is recorded before the services are initialized, so the lifecycle stops the ones
   * that were loaded even if another fails to initialize.
   */
  @Provides
  @Singleton
  static AuxiliaryServiceManager auxiliaryServiceManager(
      Config config, AuxiliaryContext context, BuiltServices built) {
    AuxiliaryServiceManager manager =
        built.add(BuiltServices.AUXILIARY_SERVICE_MANAGER, new AuxiliaryServiceManager(context));
    manager.serviceInit(config);
    return manager;
  }
}
