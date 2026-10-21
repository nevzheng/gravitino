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
package org.apache.gravitino.hook;

import java.util.function.Supplier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.EnvironmentAuthorizationServices;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;

/**
 * Authorization services for hook tests: the owner dispatcher comes from the test, and every other
 * service from the process environment, as the hooks found them before they took the services.
 */
public final class HookAuthorizationServices implements AuthorizationServices {

  private final Supplier<OwnerDispatcher> ownerDispatcher;

  private HookAuthorizationServices(Supplier<OwnerDispatcher> ownerDispatcher) {
    this.ownerDispatcher = ownerDispatcher;
  }

  /**
   * Returns services whose owner dispatcher is the supplied one.
   *
   * @param ownerDispatcher supplies the owner dispatcher, or null when authorization is disabled
   * @return the services
   */
  public static AuthorizationServices withOwnerDispatcher(
      Supplier<OwnerDispatcher> ownerDispatcher) {
    return new HookAuthorizationServices(ownerDispatcher);
  }

  @Override
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return env().internalAccessControlDispatcher();
  }

  @Override
  public OwnerDispatcher internalOwnerDispatcher() {
    return ownerDispatcher.get();
  }

  @Override
  public FutureGrantManager futureGrantManager() {
    return env().futureGrantManager();
  }

  @Override
  public GravitinoAuthorizer gravitinoAuthorizer() {
    return env().gravitinoAuthorizer();
  }

  @Override
  public CatalogAccess catalogAccess() {
    return env().catalogAccess();
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return env().internalCatalogDispatcher();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return env().internalSchemaDispatcher();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return env().internalTableDispatcher();
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return env().internalFilesetDispatcher();
  }

  private static AuthorizationServices env() {
    return EnvironmentAuthorizationServices.INSTANCE;
  }
}
