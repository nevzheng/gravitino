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
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizerContext;
import org.apache.gravitino.catalog.CatalogAccess;

/**
 * The {@link AuthorizerContext} of the Gravitino server, backed by the component.
 *
 * <p>Every service is resolved when it is asked for, so building the context builds nothing. The
 * internal access-control dispatcher is {@code null} when authorization is disabled, as the
 * environment reports it.
 */
final class ComponentAuthorizerContext implements AuthorizerContext {

  private final Config config;
  private final Provider<EntityStore> entityStore;
  private final Provider<CatalogAccess> catalogAccess;
  private final boolean authorizationEnabled;
  private final Provider<AccessControlDispatcher> internalAccessControlDispatcher;

  @Inject
  ComponentAuthorizerContext(
      Config config,
      Provider<EntityStore> entityStore,
      Provider<CatalogAccess> catalogAccess,
      @AuthorizationEnabled boolean authorizationEnabled,
      @Internal Provider<AccessControlDispatcher> internalAccessControlDispatcher) {
    this.config = config;
    this.entityStore = entityStore;
    this.catalogAccess = catalogAccess;
    this.authorizationEnabled = authorizationEnabled;
    this.internalAccessControlDispatcher = internalAccessControlDispatcher;
  }

  @Override
  public Config config() {
    return config;
  }

  @Override
  public EntityStore entityStore() {
    return entityStore.get();
  }

  @Override
  public CatalogAccess catalogAccess() {
    return catalogAccess.get();
  }

  @Override
  @Nullable
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return authorizationEnabled ? internalAccessControlDispatcher.get() : null;
  }
}
