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
package org.apache.gravitino.server.web.rest;

import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.server.authorization.MetadataAccessChecker;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;

/**
 * The process environment, for the deprecated no-argument constructors of the REST resources.
 *
 * <p>The server builds every resource through its {@code @Inject} constructor, which takes the host
 * services from HK2. Only a caller that still constructs a resource by hand reaches this class.
 */
final class EnvironmentServices {

  private EnvironmentServices() {}

  /**
   * Returns the process environment.
   *
   * @return the process environment.
   */
  @LegacyEnvAccess
  static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }

  /**
   * Returns the checker that reads the process environment on every check.
   *
   * @return the process-wide metadata access checker.
   */
  static MetadataAccessChecker metadataAccessChecker() {
    return MetadataAuthzHelper.processChecker();
  }
}
