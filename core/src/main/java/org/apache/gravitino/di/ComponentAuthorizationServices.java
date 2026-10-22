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

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * The {@link AuthorizationServices} of the component.
 *
 * <p>Every service is resolved when it is asked for, so the hooks and managers that take these
 * services can be built before the chains they reach. The access control, owner and future grant
 * services are null while authorization is disabled.
 *
 * <p>Only the owner and access control dispatchers, and the catalog services the plugin context
 * hands out, are on a real cycle; the other providers resolve lazily as well. Taking the narrow
 * dependencies each hook and manager uses is a follow-up. See section 5.3 of the design document.
 */
@Singleton
final class ComponentAuthorizationServices implements AuthorizationServices {

  private final boolean authorizationEnabled;
  private final Provider<AccessControlDispatcher> internalAccessControlDispatcher;
  private final Provider<OwnerDispatcher> internalOwnerDispatcher;
  private final Provider<FutureGrantManager> futureGrantManager;
  private final Provider<GravitinoAuthorizer> gravitinoAuthorizer;
  private final Provider<CatalogAccess> catalogAccess;
  private final Provider<CatalogDispatcher> internalCatalogDispatcher;
  private final Provider<SchemaDispatcher> internalSchemaDispatcher;
  private final Provider<TableDispatcher> internalTableDispatcher;
  private final Provider<FilesetDispatcher> internalFilesetDispatcher;

  @Inject
  ComponentAuthorizationServices(
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<AccessControlDispatcher> internalAccessControlDispatcher,
      @Internal Provider<OwnerDispatcher> internalOwnerDispatcher,
      Provider<FutureGrantManager> futureGrantManager,
      Provider<GravitinoAuthorizer> gravitinoAuthorizer,
      Provider<CatalogAccess> catalogAccess,
      @Internal Provider<CatalogDispatcher> internalCatalogDispatcher,
      @Internal Provider<SchemaDispatcher> internalSchemaDispatcher,
      @Internal Provider<TableDispatcher> internalTableDispatcher,
      @Internal Provider<FilesetDispatcher> internalFilesetDispatcher) {
    this.authorizationEnabled = authorizationEnabled;
    this.internalAccessControlDispatcher = internalAccessControlDispatcher;
    this.internalOwnerDispatcher = internalOwnerDispatcher;
    this.futureGrantManager = futureGrantManager;
    this.gravitinoAuthorizer = gravitinoAuthorizer;
    this.catalogAccess = catalogAccess;
    this.internalCatalogDispatcher = internalCatalogDispatcher;
    this.internalSchemaDispatcher = internalSchemaDispatcher;
    this.internalTableDispatcher = internalTableDispatcher;
    this.internalFilesetDispatcher = internalFilesetDispatcher;
  }

  @Override
  @Nullable
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return authorizationEnabled ? internalAccessControlDispatcher.get() : null;
  }

  @Override
  @Nullable
  public OwnerDispatcher internalOwnerDispatcher() {
    return authorizationEnabled ? internalOwnerDispatcher.get() : null;
  }

  @Override
  @Nullable
  public FutureGrantManager futureGrantManager() {
    return authorizationEnabled ? futureGrantManager.get() : null;
  }

  @Override
  public GravitinoAuthorizer gravitinoAuthorizer() {
    return gravitinoAuthorizer.get();
  }

  @Override
  public CatalogAccess catalogAccess() {
    return catalogAccess.get();
  }

  @Override
  public boolean authorizationEnabled() {
    return authorizationEnabled;
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
}
