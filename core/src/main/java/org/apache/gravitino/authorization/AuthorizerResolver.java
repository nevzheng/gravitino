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

import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.gravitino.LegacyRuntimeDependencies;

/** Resolves the active authorizer after its dynamic provider has completed initialization. */
@FunctionalInterface
public interface AuthorizerResolver {

  /**
   * Resolves the active authorizer.
   *
   * @return the active authorizer, or {@code null} when authorization is disabled or not
   *     initialized
   */
  @Nullable
  GravitinoAuthorizer resolve();

  /**
   * Returns the compatibility resolver backed by the legacy runtime environment.
   *
   * @return the legacy authorizer resolver
   */
  @SuppressWarnings({"deprecation", "removal"})
  static AuthorizerResolver legacyEnvironment() {
    return LegacyRuntimeDependencies::gravitinoAuthorizer;
  }

  /**
   * Returns the compatibility owner-dispatcher supplier backed by the legacy runtime environment.
   *
   * @return the legacy owner-dispatcher supplier
   */
  @SuppressWarnings({"deprecation", "removal"})
  static Supplier<OwnerDispatcher> legacyOwnerDispatcher() {
    return LegacyRuntimeDependencies::ownerDispatcher;
  }
}
