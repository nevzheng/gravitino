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

/**
 * Includes every module of {@link GravitinoComponent}. A downstream root component is built from
 * this module and its own modules, and extends {@link GravitinoComponent}:
 *
 * <pre>{@code
 * @Singleton
 * @Component(modules = {GravitinoModules.class, EnterpriseModule.class})
 * public interface EnterpriseComponent extends GravitinoComponent {
 *   @Component.Factory
 *   interface Factory {
 *     EnterpriseComponent create(@BindsInstance Config config);
 *   }
 * }
 * }</pre>
 *
 * <p>The downstream modules change the upstream dispatcher chains through {@link
 * DispatcherDecorator} contributions and add services to the lifecycle through {@link
 * ManagedService} contributions; they cannot bind a type an upstream module already binds.
 */
@Module(
    includes = {
      ConfigModule.class,
      StorageModule.class,
      LockModule.class,
      EventModule.class,
      CatalogModule.class,
      SchemaTableModule.class,
      FilesetTopicModelModule.class,
      GovernanceModule.class,
      AuthorizationModule.class,
      JobModule.class,
      AuxiliaryModule.class,
      AuthorizerModule.class,
      LifecycleModule.class
    })
public abstract class GravitinoModules {

  private GravitinoModules() {}
}
