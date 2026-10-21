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

import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;

/**
 * A graph with the production modules and fake leaves: {@link FakeLeavesModule} replaces the
 * storage and event modules and the legacy bridge, so module tests run against mocks.
 */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      LockModule.class,
      CatalogModule.class,
      SchemaTableModule.class,
      FilesetTopicModelModule.class,
      GovernanceModule.class,
      AuthorizationModule.class,
      JobModule.class,
      AuxiliaryModule.class,
      LifecycleModule.class,
      FakeLeavesModule.class
    })
interface ModuleTestComponent extends GravitinoComponent {

  /**
   * Returns the schema operation dispatcher the schema chains share, which the production component
   * does not expose.
   *
   * @return the schema operation dispatcher
   */
  @Undecorated
  SchemaOperationDispatcher schemaOperationDispatcher();

  /**
   * Returns the catalog manager under the catalog chains, which the production component exposes
   * only as its {@link #catalogAccess()}.
   *
   * @return the catalog manager
   */
  @Undecorated
  CatalogManager catalogManager();

  /**
   * Returns the metalake manager under the metalake chains, which the production component does not
   * expose.
   *
   * @return the metalake manager
   */
  @Undecorated
  MetalakeManager metalakeManager();

  /**
   * Builds a test graph.
   *
   * @param config the configuration
   * @param leaves the fake leaves
   * @return the graph
   */
  static ModuleTestComponent create(Config config, FakeLeaves leaves) {
    return DaggerModuleTestComponent.factory().create(config, leaves);
  }

  /** Creates {@link ModuleTestComponent} instances. */
  @Component.Factory
  interface Factory {
    ModuleTestComponent create(@BindsInstance Config config, @BindsInstance FakeLeaves leaves);
  }
}
