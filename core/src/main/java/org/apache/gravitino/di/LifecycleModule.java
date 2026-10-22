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
import dagger.multibindings.Multibinds;
import java.util.Set;
import org.apache.gravitino.lifecycle.ManagedService;

/**
 * Declares the {@link ManagedService managed services} a downstream component contributes. A
 * downstream module adds one with {@code @Provides @IntoSet}; it then starts and closes with the
 * services of the component, in dependency order. There is no order to declare: the services it
 * depends on are built, and so registered, before it. Upstream contributes none.
 */
@Module
abstract class LifecycleModule {

  private LifecycleModule() {}

  @Multibinds
  abstract Set<ManagedService> contributedServices();
}
