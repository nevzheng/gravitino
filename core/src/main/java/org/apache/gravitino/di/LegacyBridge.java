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

import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.secret.SecretManager;

/**
 * The objects that {@code GravitinoEnv} still builds and the {@link GravitinoComponent} graph may
 * depend on.
 *
 * <p>This interface is transitional. {@code GravitinoEnv} implements it and passes itself to the
 * component factory; {@link LegacyModule} exposes each method as a binding. When an area moves into
 * a module of the component, its method is removed from here, so this interface lists exactly the
 * legacy surface the graph still depends on. It is deleted when the migration is complete.
 *
 * <p>Implementations must not return {@code null}: the graph has no nullable bindings. A method
 * called before the object is built throws instead.
 */
public interface LegacyBridge {

  /**
   * Returns the entity store.
   *
   * @return the entity store
   */
  EntityStore entityStore();

  /**
   * Returns the catalog manager.
   *
   * @return the catalog manager
   */
  CatalogManager catalogManager();

  /**
   * Returns the secret manager.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the internal fileset dispatcher, which the schema operation dispatcher uses to drop the
   * filesets of a schema.
   *
   * @return the internal fileset dispatcher
   */
  FilesetDispatcher internalFilesetDispatcher();

  /**
   * Returns the event bus.
   *
   * @return the event bus
   */
  EventBus eventBus();
}
