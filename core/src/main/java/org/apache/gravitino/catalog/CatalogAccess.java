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
package org.apache.gravitino.catalog;

import java.util.function.Consumer;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.utils.ThrowableFunction;

/**
 * Lookups of the server's catalogs and leased access to a live catalog.
 *
 * <p>This is the face of the {@link CatalogManager} that code outside the dependency-injection
 * modules may use. It has no method that creates, alters, enables, disables or drops a catalog:
 * those go through a {@link CatalogDispatcher} chain, so that the chain's hooks, events and
 * decorators run.
 *
 * <p>{@link #doWithCatalog} hands the operation the live {@link BaseCatalog}, whose operations can
 * write to the catalog's backend. The catalog must not escape the operation.
 */
public interface CatalogAccess {

  /**
   * Lists the catalogs of a metalake.
   *
   * @param namespace the namespace of the metalake
   * @return the identifiers of the catalogs
   * @throws NoSuchMetalakeException if the metalake does not exist
   */
  NameIdentifier[] listCatalogs(Namespace namespace) throws NoSuchMetalakeException;

  /**
   * Lists the catalogs of a metalake with their details.
   *
   * @param namespace the namespace of the metalake
   * @return the catalogs
   * @throws NoSuchMetalakeException if the metalake does not exist
   */
  Catalog[] listCatalogsInfo(Namespace namespace) throws NoSuchMetalakeException;

  /**
   * Loads a catalog.
   *
   * @param ident the identifier of the catalog
   * @return a metadata snapshot of the catalog; connector resources are not exposed
   * @throws NoSuchCatalogException if the catalog does not exist
   */
  Catalog loadCatalog(NameIdentifier ident) throws NoSuchCatalogException;

  /**
   * Checks whether a catalog exists.
   *
   * @param ident the identifier of the catalog
   * @return true if the catalog exists, false otherwise
   */
  default boolean catalogExists(NameIdentifier ident) {
    try {
      loadCatalog(ident);
      return true;
    } catch (NoSuchCatalogException e) {
      return false;
    }
  }

  /**
   * Runs an operation against a live catalog while its instance and class loader are kept alive.
   *
   * <p>Callers that need connector-only state, such as the authorization plugin, the capability or
   * the raw catalog properties, use this method rather than casting the snapshot that {@link
   * #loadCatalog(NameIdentifier)} returns. The operation must not retain the catalog: it converts
   * connector-backed values to detached ones before it returns.
   *
   * @param ident the identifier of the catalog
   * @param operation the operation to run against the live catalog
   * @param <R> the result type of the operation
   * @return the value the operation returns
   * @throws NoSuchCatalogException if the catalog does not exist
   */
  <R> R doWithCatalog(NameIdentifier ident, ThrowableFunction<BaseCatalog, R> operation)
      throws NoSuchCatalogException;

  /**
   * Adds a listener that is called with the identifier of each catalog removed from the catalog
   * cache, for example after the catalog was altered or dropped.
   *
   * <p>Removals are delivered on a single thread, so a listener must not block.
   *
   * @param listener the listener
   */
  void addCatalogCacheRemoveListener(Consumer<NameIdentifier> listener);
}
