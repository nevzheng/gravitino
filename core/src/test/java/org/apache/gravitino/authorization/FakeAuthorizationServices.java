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
package org.apache.gravitino.authorization;

import javax.annotation.Nullable;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * {@link AuthorizationServices} for tests: each service is the one the test set, and is read when
 * the code under test asks for it, so a test can replace a service after it built the object that
 * holds the services. Every service starts unset, which is how a server without authorization hands
 * them out, except the authorizer, which starts as {@link NoopGravitinoAuthorizer}.
 */
public final class FakeAuthorizationServices implements AuthorizationServices {

  @Nullable private AccessControlDispatcher accessControlDispatcher;
  @Nullable private OwnerDispatcher ownerDispatcher;
  @Nullable private FutureGrantManager futureGrantManager;
  private GravitinoAuthorizer authorizer = NoopGravitinoAuthorizer.INSTANCE;
  @Nullable private CatalogAccess catalogAccess;
  @Nullable private CatalogDispatcher catalogDispatcher;
  @Nullable private SchemaDispatcher schemaDispatcher;
  @Nullable private TableDispatcher tableDispatcher;
  @Nullable private FilesetDispatcher filesetDispatcher;

  /**
   * Sets the internal access control dispatcher; authorization is enabled while it is set.
   *
   * @param dispatcher the dispatcher, or null to disable authorization
   * @return these services
   */
  public FakeAuthorizationServices withAccessControlDispatcher(
      @Nullable AccessControlDispatcher dispatcher) {
    this.accessControlDispatcher = dispatcher;
    return this;
  }

  /**
   * Sets the internal owner dispatcher.
   *
   * @param dispatcher the dispatcher, or null
   * @return these services
   */
  public FakeAuthorizationServices withOwnerDispatcher(@Nullable OwnerDispatcher dispatcher) {
    this.ownerDispatcher = dispatcher;
    return this;
  }

  /**
   * Sets the future grant manager.
   *
   * @param manager the manager, or null
   * @return these services
   */
  public FakeAuthorizationServices withFutureGrantManager(@Nullable FutureGrantManager manager) {
    this.futureGrantManager = manager;
    return this;
  }

  /**
   * Sets the authorizer.
   *
   * @param gravitinoAuthorizer the authorizer
   * @return these services
   */
  public FakeAuthorizationServices withAuthorizer(GravitinoAuthorizer gravitinoAuthorizer) {
    this.authorizer = gravitinoAuthorizer;
    return this;
  }

  /**
   * Sets the catalog access.
   *
   * @param catalogs the catalog access, or null
   * @return these services
   */
  public FakeAuthorizationServices withCatalogAccess(@Nullable CatalogAccess catalogs) {
    this.catalogAccess = catalogs;
    return this;
  }

  /**
   * Sets the internal catalog dispatcher.
   *
   * @param dispatcher the dispatcher, or null
   * @return these services
   */
  public FakeAuthorizationServices withCatalogDispatcher(@Nullable CatalogDispatcher dispatcher) {
    this.catalogDispatcher = dispatcher;
    return this;
  }

  /**
   * Sets the internal schema dispatcher.
   *
   * @param dispatcher the dispatcher, or null
   * @return these services
   */
  public FakeAuthorizationServices withSchemaDispatcher(@Nullable SchemaDispatcher dispatcher) {
    this.schemaDispatcher = dispatcher;
    return this;
  }

  /**
   * Sets the internal table dispatcher.
   *
   * @param dispatcher the dispatcher, or null
   * @return these services
   */
  public FakeAuthorizationServices withTableDispatcher(@Nullable TableDispatcher dispatcher) {
    this.tableDispatcher = dispatcher;
    return this;
  }

  /**
   * Sets the internal fileset dispatcher.
   *
   * @param dispatcher the dispatcher, or null
   * @return these services
   */
  public FakeAuthorizationServices withFilesetDispatcher(@Nullable FilesetDispatcher dispatcher) {
    this.filesetDispatcher = dispatcher;
    return this;
  }

  @Override
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return accessControlDispatcher;
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    return ownerDispatcher;
  }

  @Override
  public FutureGrantManager futureGrantManager() {
    return futureGrantManager;
  }

  @Override
  public GravitinoAuthorizer gravitinoAuthorizer() {
    return authorizer;
  }

  @Override
  public CatalogAccess catalogAccess() {
    return catalogAccess;
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return catalogDispatcher;
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return schemaDispatcher;
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return tableDispatcher;
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return filesetDispatcher;
  }
}
