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
package org.apache.gravitino.lance.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.auxiliary.AuxiliaryContext;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.server.ServerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestGravitinoLanceRESTServer {

  @Test
  public void testStandaloneContextHasTheBaseServicesOfTheComponent() {
    GravitinoComponent component = mock(GravitinoComponent.class);
    Config config = mock(Config.class);
    EventBus eventBus = mock(EventBus.class);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    SecretManager secretManager = mock(SecretManager.class);
    KmsClientRegistry kmsClientRegistry = mock(KmsClientRegistry.class);
    when(component.config()).thenReturn(config);
    when(component.eventBus()).thenReturn(eventBus);
    when(component.metricsSystem()).thenReturn(metricsSystem);
    when(component.secretManager()).thenReturn(secretManager);
    when(component.kmsClientRegistry()).thenReturn(kmsClientRegistry);

    AuxiliaryContext context = GravitinoLanceRESTServer.standaloneContext(component);

    Assertions.assertSame(config, context.config());
    Assertions.assertSame(eventBus, context.eventBus());
    Assertions.assertSame(metricsSystem, context.metricsSystem());
    Assertions.assertSame(secretManager, context.secretManager());
    Assertions.assertSame(kmsClientRegistry, context.kmsClientRegistry());
    Assertions.assertThrows(IllegalStateException.class, context::catalogAccess);
    Assertions.assertThrows(IllegalStateException.class, context::idGenerator);
  }

  @Test
  public void testBuildsNoComponentUntilItIsInitialized() {
    // The server builds its component from the factory, and only when it
    // initializes.
    GravitinoComponent.Factory factory = mock(GravitinoComponent.Factory.class);

    new GravitinoLanceRESTServer(new ServerConfig(), factory);

    verifyNoInteractions(factory);
  }

  @Test
  public void testRejectsAMissingComponentFactory() {
    // A downstream distribution passes the factory of its root component; it cannot be omitted.
    Assertions.assertThrows(
        NullPointerException.class, () -> new GravitinoLanceRESTServer(new ServerConfig(), null));
  }
}
