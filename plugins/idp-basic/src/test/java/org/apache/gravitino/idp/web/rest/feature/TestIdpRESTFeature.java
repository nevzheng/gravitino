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

package org.apache.gravitino.idp.web.rest.feature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthenticatorType;
import org.apache.gravitino.idp.SystemExitTestHelper;
import org.apache.gravitino.idp.SystemExitTestHelper.SystemExitException;
import org.apache.gravitino.storage.IdGenerator;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.junit.jupiter.api.Test;

class TestIdpRESTFeature {

  @Test
  void testHk2InjectsTheHostServices() throws IllegalAccessException {
    Config config = new Config(false) {};
    IdGenerator idGenerator = mock(IdGenerator.class);
    ServiceLocator locator =
        ServiceLocatorUtilities.bind(
            new AbstractBinder() {
              @Override
              protected void configure() {
                bind(config).to(Config.class);
                bind(idGenerator).to(IdGenerator.class);
              }
            });
    try {
      IdpRESTFeature feature = locator.createAndInitialize(IdpRESTFeature.class);
      assertSame(config, FieldUtils.readField(feature, "config", true));
      assertSame(idGenerator, FieldUtils.readField(feature, "idGenerator", true));
    } finally {
      locator.shutdown();
    }
  }

  @Test
  void testSimpleWithBasicFails() {
    Config config = new Config(false) {};
    config.set(
        Configs.AUTHENTICATORS,
        Lists.newArrayList(
            AuthenticatorType.SIMPLE.name().toLowerCase(),
            AuthenticatorType.BASIC.name().toLowerCase()));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> IdpRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testExtensionWithoutBasicFails() {
    Config config = new Config(false) {};
    config.set(
        Configs.AUTHENTICATORS, Lists.newArrayList(AuthenticatorType.OAUTH.name().toLowerCase()));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> IdpRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testBasicOk() {
    Config config = new Config(false) {};
    config.set(
        Configs.AUTHENTICATORS, Lists.newArrayList(AuthenticatorType.BASIC.name().toLowerCase()));

    assertDoesNotThrow(() -> IdpRESTFeature.validateConfiguration(config));
  }
}
