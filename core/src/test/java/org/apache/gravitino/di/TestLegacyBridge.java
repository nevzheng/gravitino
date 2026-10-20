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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.junit.jupiter.api.Test;

/** Checks how {@link LegacyModule} exposes the objects of a {@link LegacyBridge}. */
public class TestLegacyBridge {

  @Test
  public void testBindingsReadTheBridgeOnEveryRequest() {
    TestGravitinoComponent.FakeLegacyBridge legacy = new TestGravitinoComponent.FakeLegacyBridge();
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, legacy);
    GravitinoAuthorizer first = component.authorizationServices().gravitinoAuthorizer();

    // The server installs its authorizer after the component is built.
    GravitinoAuthorizer installed = mock(GravitinoAuthorizer.class);
    legacy.gravitinoAuthorizer = installed;

    // The bridge owns the authorizer, so the graph must not cache what it read before.
    assertSame(installed, component.authorizationServices().gravitinoAuthorizer());
    assertNotSame(first, component.authorizationServices().gravitinoAuthorizer());
  }

  @Test
  public void testBridgeFailurePropagates() {
    LegacyBridge failing =
        () -> {
          throw new IllegalStateException("no authorizer");
        };
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, failing);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> component.authorizationServices().gravitinoAuthorizer());
    assertEquals("no authorizer", exception.getMessage());
  }

  @Test
  public void testBridgeIsNotReadUntilRequested() {
    LegacyBridge failing =
        () -> {
          throw new IllegalStateException("must not be read");
        };

    // Building the component and the objects it owns must not touch the bridge.
    GravitinoComponent component =
        DaggerGravitinoComponent.factory().create(new Config(false) {}, failing);
    component.lockManager();
    component.idGenerator();
    component.metricsSystem();
    component.secretManager();
    component.eventBus();
    // Building the services that hand out the authorizer does not read it either.
    component.authorizationServices();
  }

  @Test
  public void testTheAuthorizerIsBoundOnlyBehindASupplier() {
    // The authorizer changes after the graph is built, so a binding of the bare type would let a
    // consumer keep the authorizer that was installed when it was built.
    for (Method method : GravitinoComponent.class.getMethods()) {
      assertNotEquals(GravitinoAuthorizer.class, method.getReturnType(), method.getName());
    }
    for (Method method : LegacyModule.class.getDeclaredMethods()) {
      assertNotEquals(GravitinoAuthorizer.class, method.getReturnType(), method.getName());
    }
  }

  @Test
  public void testTheSupplierReadsTheBridgeOnEveryCall() {
    TestGravitinoComponent.FakeLegacyBridge legacy = new TestGravitinoComponent.FakeLegacyBridge();
    Supplier<GravitinoAuthorizer> authorizer = LegacyModule.gravitinoAuthorizer(legacy);
    GravitinoAuthorizer installed = mock(GravitinoAuthorizer.class);

    legacy.gravitinoAuthorizer = installed;

    assertSame(installed, authorizer.get());
  }
}
