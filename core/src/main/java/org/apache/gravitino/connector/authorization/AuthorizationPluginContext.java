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
package org.apache.gravitino.connector.authorization;

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * The host services an {@link AuthorizationPlugin} may use.
 *
 * <p>The catalog that loads a plugin passes this context to {@link
 * BaseAuthorization#newPlugin(String, String, Map, AuthorizationPluginContext)}. A plugin that maps
 * metadata objects to storage locations, such as the Ranger HDFS plugin, reads catalogs, schemas,
 * tables and filesets through the internal dispatchers here, never through a static. The interface
 * is plain Java: it carries no dependency-injection types across the plugin class loader boundary.
 */
@Evolving
public interface AuthorizationPluginContext {

  /**
   * Returns whether the server enforces authorization. A plugin looks up no location while it does
   * not.
   *
   * @return true if authorization is enabled
   */
  boolean authorizationEnabled();

  /**
   * Returns the internal catalog dispatcher, which applies no authorization hooks or events.
   *
   * @return the internal catalog dispatcher
   */
  CatalogDispatcher internalCatalogDispatcher();

  /**
   * Returns the internal schema dispatcher, which applies no authorization hooks or events.
   *
   * @return the internal schema dispatcher
   */
  SchemaDispatcher internalSchemaDispatcher();

  /**
   * Returns the internal table dispatcher, which applies no authorization hooks or events.
   *
   * @return the internal table dispatcher
   */
  TableDispatcher internalTableDispatcher();

  /**
   * Returns the internal fileset dispatcher, which applies no authorization hooks or events.
   *
   * @return the internal fileset dispatcher
   */
  FilesetDispatcher internalFilesetDispatcher();

  /**
   * Creates a context that reads each service when a plugin asks for it, so it can be built before
   * the dispatchers are.
   *
   * @param authorizationEnabled says whether the server enforces authorization
   * @param internalCatalogDispatcher supplies the internal catalog dispatcher
   * @param internalSchemaDispatcher supplies the internal schema dispatcher
   * @param internalTableDispatcher supplies the internal table dispatcher
   * @param internalFilesetDispatcher supplies the internal fileset dispatcher
   * @return the context
   */
  static AuthorizationPluginContext of(
      BooleanSupplier authorizationEnabled,
      Supplier<CatalogDispatcher> internalCatalogDispatcher,
      Supplier<SchemaDispatcher> internalSchemaDispatcher,
      Supplier<TableDispatcher> internalTableDispatcher,
      Supplier<FilesetDispatcher> internalFilesetDispatcher) {
    Preconditions.checkNotNull(authorizationEnabled, "authorizationEnabled");
    Preconditions.checkNotNull(internalCatalogDispatcher, "internalCatalogDispatcher");
    Preconditions.checkNotNull(internalSchemaDispatcher, "internalSchemaDispatcher");
    Preconditions.checkNotNull(internalTableDispatcher, "internalTableDispatcher");
    Preconditions.checkNotNull(internalFilesetDispatcher, "internalFilesetDispatcher");
    return new AuthorizationPluginContext() {
      @Override
      public boolean authorizationEnabled() {
        return authorizationEnabled.getAsBoolean();
      }

      @Override
      public CatalogDispatcher internalCatalogDispatcher() {
        return internalCatalogDispatcher.get();
      }

      @Override
      public SchemaDispatcher internalSchemaDispatcher() {
        return internalSchemaDispatcher.get();
      }

      @Override
      public TableDispatcher internalTableDispatcher() {
        return internalTableDispatcher.get();
      }

      @Override
      public FilesetDispatcher internalFilesetDispatcher() {
        return internalFilesetDispatcher.get();
      }
    };
  }
}
