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
package org.apache.gravitino.iceberg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.auxiliary.EnvironmentAuxiliaryContext;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.junit.jupiter.api.Test;

public class TestRESTService {

  @Test
  public void testInitWithoutContextUsesTheEnvironment() {
    RESTService service = spy(new RESTService());
    doNothing().when(service).serviceInit(any(), anyBoolean(), any(AuxiliaryContext.class));
    Map<String, String> properties = Collections.emptyMap();

    service.serviceInit(properties, true);

    verify(service).serviceInit(properties, true, EnvironmentAuxiliaryContext.INSTANCE);
  }

  /** The documented Iceberg REST key reaches the value the service passes to its error builder. */
  @Test
  public void testIncludeErrorStackTraceReadFromServiceConfig() {
    assertTrue(JettyServerConfig.fromConfig(new IcebergConfig()).isIncludeErrorStackTrace());
    assertFalse(
        JettyServerConfig.fromConfig(
                new IcebergConfig(Collections.singletonMap("includeErrorStackTrace", "false")))
            .isIncludeErrorStackTrace());
  }

  @Test
  public void testMetricsAreEnabledOnlyForConfiguredStandaloneMode() {
    IcebergConfig disabledConfig = new IcebergConfig();
    assertFalse(RESTService.shouldEnableMetrics(false, disabledConfig));
    assertFalse(RESTService.shouldEnableMetrics(true, disabledConfig));

    IcebergConfig enabledConfig =
        new IcebergConfig(Collections.singletonMap(IcebergConfig.METRICS_ENABLED.getKey(), "true"));
    assertTrue(RESTService.shouldEnableMetrics(false, enabledConfig));
    assertFalse(RESTService.shouldEnableMetrics(true, enabledConfig));
  }
}
