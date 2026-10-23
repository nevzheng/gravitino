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
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.annotation.Evolving;

/**
 * The host services a {@link GravitinoAuthorizer} may use.
 *
 * <p>The Gravitino server builds one context and passes it to the authorizer it loads, through
 * {@link GravitinoAuthorizer#initialize(AuthorizerContext)}. An authorizer that keeps the context
 * reaches the host only through these methods, never through a static. The interface is plain Java
 * and carries no dependency-injection types.
 */
@Evolving
public interface AuthorizerContext {

  /**
   * Returns the server configuration.
   *
   * @return the configuration
   */
  Config config();

  /**
   * Returns the entity store.
   *
   * @return the entity store
   */
  EntityStore entityStore();

  /**
   * Returns the internal access-control dispatcher: the access control manager, which runs no hooks
   * and emits no events.
   *
   * @return the internal access-control dispatcher, or {@code null} when authorization is disabled
   */
  @Nullable
  AccessControlDispatcher internalAccessControlDispatcher();
}
