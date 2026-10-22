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
package org.apache.gravitino.authorization.chain;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

/** Implementation of a Chained authorization in Gravitino. */
public class ChainedAuthorization extends BaseAuthorization<ChainedAuthorization> {
  @Override
  public String shortName() {
    return "chain";
  }

  /**
   * Not supported: the plugins of this authorization read host services through an {@link
   * AuthorizationPluginContext}, which the catalog passes to {@link #newPlugin(String, String, Map,
   * AuthorizationPluginContext)}.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    throw new UnsupportedOperationException(
        "ChainedAuthorization builds its plugins with an AuthorizationPluginContext");
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake,
      String catalogProvider,
      Map<String, String> config,
      AuthorizationPluginContext context) {
    switch (catalogProvider) {
      case "hive":
        return new ChainedAuthorizationPlugin(metalake, catalogProvider, config, context);
      default:
        throw new IllegalArgumentException("Unknown catalog provider: " + catalogProvider);
    }
  }
}
