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

import org.apache.gravitino.authorization.GravitinoAuthorizer;

/**
 * The objects that the {@link GravitinoComponent} graph depends on but does not build.
 *
 * <p>This interface is transitional. {@code GravitinoEnv} implements it and passes itself to the
 * component factory; {@link LegacyModule} exposes each method as a binding. When an area moves into
 * a module of the component, its method is removed from here, so this interface lists exactly the
 * legacy surface the graph still depends on. What is left is the authorizer, which the server
 * builds and installs after the environment is initialized. It is deleted when the migration is
 * complete.
 *
 * <p>Implementations must not return {@code null}: the graph has no nullable bindings.
 */
public interface LegacyBridge {

  /**
   * Returns the authorizer the server installed. When none is installed, it returns {@code
   * DenyAllGravitinoAuthorizer.INSTANCE} if authorization is enabled, so a check fails closed, and
   * {@code NoopGravitinoAuthorizer.INSTANCE} otherwise. The server installs it after the component
   * is built, so this is read on every request.
   *
   * @return the authorizer, never null
   */
  GravitinoAuthorizer gravitinoAuthorizer();
}
