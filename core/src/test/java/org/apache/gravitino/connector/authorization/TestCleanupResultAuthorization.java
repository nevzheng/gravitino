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
package org.apache.gravitino.connector.authorization;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.authorization.MetadataObjectChange;
import org.apache.gravitino.connector.authorization.ranger.TestRangerAuthorizationHadoopSQLPlugin;

/**
 * Test authorization provider whose plugin reports a configurable result for metadata changes, so
 * tests can drive a catalog through a real authorization plugin that fails its cleanup.
 */
public class TestCleanupResultAuthorization
    extends BaseAuthorization<TestCleanupResultAuthorization> {
  /** Short name used as the catalog's {@code authorization-provider} property. */
  public static final String SHORT_NAME = "test-cleanup-result";

  /** Number of {@code onMetadataUpdated} calls received by plugins of this provider. */
  public static final AtomicInteger METADATA_UPDATES = new AtomicInteger();

  /** Result returned by {@code onMetadataUpdated}. */
  public static volatile Boolean metadataUpdateResult = Boolean.TRUE;

  /** Creates the provider; loaded through {@link java.util.ServiceLoader}. */
  public TestCleanupResultAuthorization() {}

  /** Restores the default result and clears the call count. */
  public static void reset() {
    metadataUpdateResult = Boolean.TRUE;
    METADATA_UPDATES.set(0);
  }

  @Override
  public String shortName() {
    return SHORT_NAME;
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> properties) {
    return new TestRangerAuthorizationHadoopSQLPlugin() {
      @Override
      public Boolean onMetadataUpdated(MetadataObjectChange... changes) {
        METADATA_UPDATES.incrementAndGet();
        return metadataUpdateResult;
      }
    };
  }
}
