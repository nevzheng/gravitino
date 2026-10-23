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
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;

/**
 * The server services that keep authorization in step with metadata changes: the dispatchers the
 * hooks set owners and grants through, the authorizer whose caches they refresh, and the catalog
 * manager whose authorization plugins they notify.
 *
 * <p>The access control, owner and future grant services are null while authorization is disabled,
 * as the hooks have always found them. The interface is plain Java and carries no
 * dependency-injection types.
 */
@Evolving
public interface AuthorizationServices extends AuthorizationPluginContext {

  /**
   * Returns the internal access control dispatcher.
   *
   * @return the internal access control dispatcher, or null while authorization is disabled
   */
  @Nullable
  AccessControlDispatcher internalAccessControlDispatcher();

  /**
   * Returns the internal owner dispatcher.
   *
   * @return the internal owner dispatcher, or null while authorization is disabled
   */
  @Nullable
  OwnerDispatcher internalOwnerDispatcher();

  /**
   * Returns the manager of the grants a new object inherits from its parents.
   *
   * @return the future grant manager, or null while authorization is disabled
   */
  @Nullable
  FutureGrantManager futureGrantManager();

  /**
   * Returns the authorizer the server installed.
   *
   * @return the authorizer, or a no-op authorizer when none is installed; never null
   */
  GravitinoAuthorizer gravitinoAuthorizer();

  /**
   * Returns the lookups of the server's catalogs and leased access to them; the live catalogs carry
   * the authorization plugins.
   *
   * @return the catalog access
   */
  CatalogAccess catalogAccess();

  /**
   * Returns whether the server enforces authorization: exactly when it holds an internal access
   * control dispatcher.
   *
   * @return true if authorization is enabled
   */
  @Override
  default boolean authorizationEnabled() {
    return internalAccessControlDispatcher() != null;
  }
}
