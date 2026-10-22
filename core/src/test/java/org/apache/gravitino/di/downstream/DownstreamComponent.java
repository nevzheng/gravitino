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
package org.apache.gravitino.di.downstream;

import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.di.ForDispatcherChains;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.di.GravitinoModules;
import org.apache.gravitino.di.LegacyBridge;

/**
 * A root component built the way a downstream distribution builds its own: from {@link
 * GravitinoModules} and its own module, in a package other than {@code org.apache.gravitino.di}.
 */
@Singleton
@Component(modules = {GravitinoModules.class, DownstreamModule.class})
interface DownstreamComponent extends GravitinoComponent {

  /**
   * Returns the service only this component has.
   *
   * @return the downstream service
   */
  DownstreamService downstreamService();

  /**
   * Returns the catalog manager a downstream module builds its own operation dispatchers over.
   *
   * @return the catalog manager
   */
  @ForDispatcherChains
  CatalogManager dispatcherChainCatalogManager();

  /** Creates {@link DownstreamComponent} instances. */
  @Component.Factory
  interface Factory {

    /**
     * Creates a component.
     *
     * @param config the configuration
     * @param legacy the legacy bridge
     * @return a new component
     */
    DownstreamComponent create(@BindsInstance Config config, @BindsInstance LegacyBridge legacy);
  }
}
