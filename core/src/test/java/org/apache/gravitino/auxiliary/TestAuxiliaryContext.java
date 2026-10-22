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
package org.apache.gravitino.auxiliary;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.gravitino.Config;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.junit.jupiter.api.Test;

public class TestAuxiliaryContext {

  @Test
  public void testStandaloneContextReturnsTheBaseServices() {
    Config config = new Config(false) {};
    EventBus eventBus = mock(EventBus.class);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    SecretManager secretManager = mock(SecretManager.class);
    KmsClientRegistry kmsClientRegistry = mock(KmsClientRegistry.class);

    AuxiliaryContext context =
        AuxiliaryContext.standalone(
            config, eventBus, metricsSystem, secretManager, kmsClientRegistry);

    assertSame(config, context.config());
    assertSame(eventBus, context.eventBus());
    assertSame(metricsSystem, context.metricsSystem());
    assertSame(secretManager, context.secretManager());
    assertSame(kmsClientRegistry, context.kmsClientRegistry());
  }

  @Test
  public void testStandaloneContextRejectsServerOnlyServices() {
    AuxiliaryContext context =
        AuxiliaryContext.standalone(
            new Config(false) {},
            mock(EventBus.class),
            mock(MetricsSystem.class),
            mock(SecretManager.class),
            mock(KmsClientRegistry.class));

    List<Function<AuxiliaryContext, Object>> serverOnly =
        List.of(
            AuxiliaryContext::idGenerator,
            AuxiliaryContext::lockManager,
            AuxiliaryContext::entityStore,
            AuxiliaryContext::catalogAccess,
            AuxiliaryContext::catalogDispatcher,
            AuxiliaryContext::schemaDispatcher,
            AuxiliaryContext::tableDispatcher,
            AuxiliaryContext::policyDispatcher,
            AuxiliaryContext::secretPropertyOperationDispatcher,
            AuxiliaryContext::internalSchemaDispatcher,
            AuxiliaryContext::internalTableDispatcher,
            AuxiliaryContext::internalViewDispatcher,
            AuxiliaryContext::internalOwnerDispatcher);
    for (Function<AuxiliaryContext, Object> service : serverOnly) {
      assertThrows(IllegalStateException.class, () -> service.apply(context));
    }
    // A standalone server has no authorizer.
    assertNull(context.gravitinoAuthorizer());
  }

  @Test
  public void testServiceInitWithContextDefaultsToServiceInit() {
    RecordingService service = new RecordingService();
    Map<String, String> config = ImmutableMap.of("k", "v");

    service.serviceInit(config, true, mock(AuxiliaryContext.class));

    assertSame(config, service.config);
    assertSame(Boolean.TRUE, service.auxMode);
  }

  /** A service that only implements the two-argument initialization. */
  private static class RecordingService implements GravitinoAuxiliaryService {
    private Map<String, String> config;
    private Boolean auxMode;

    @Override
    public String shortName() {
      return "recording";
    }

    @Override
    public void serviceInit(Map<String, String> config, boolean auxMode) {
      this.config = config;
      this.auxMode = auxMode;
    }

    @Override
    public void serviceStart() {}

    @Override
    public void serviceStop() {}
  }
}
