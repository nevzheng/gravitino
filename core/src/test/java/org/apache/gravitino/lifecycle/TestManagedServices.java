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
package org.apache.gravitino.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestManagedServices {

  private final List<String> events = new ArrayList<>();

  @Test
  public void testRegisterKeepsOnlyManagedServices() {
    ManagedServices services = new ManagedServices();
    Object plain = new Object();
    FakeService managed = new FakeService("a");

    assertSame(plain, services.register(plain));
    assertSame(managed, services.register(managed));
    // Registering the same service twice records it once.
    services.register(managed);

    assertEquals(List.of(managed), services.services());
  }

  @Test
  public void testStartsInRegistrationOrderAndClosesInReverse() throws Exception {
    ManagedServices services = new ManagedServices();
    services.register(new FakeService("a"));
    services.register(new FakeService("b"));
    services.register(new FakeService("c"));

    services.start();
    assertEquals(ManagedServices.State.STARTED, services.state());
    services.close();

    assertEquals(List.of("start a", "start b", "start c", "close c", "close b", "close a"), events);
    assertEquals(ManagedServices.State.CLOSED, services.state());
  }

  @Test
  public void testFailedStartClosesTheStartedServicesInReverse() throws Exception {
    ManagedServices services = new ManagedServices();
    services.register(new FakeService("a"));
    services.register(new FakeService("b"));
    FakeService failing = new FakeService("c");
    failing.startFailure = new IllegalStateException("c cannot start");
    services.register(failing);
    services.register(new FakeService("d"));

    IllegalStateException e = assertThrows(IllegalStateException.class, services::start);

    assertSame(failing.startFailure, e);
    assertEquals(List.of("start a", "start b", "close b", "close a"), events);
    assertEquals(ManagedServices.State.CLOSED, services.state());

    // Closing afterwards closes what is still open, the failed service included.
    events.clear();
    services.close();
    assertEquals(List.of("close d", "close c"), events);
  }

  @Test
  public void testRollbackFailuresAreSuppressed() {
    ManagedServices services = new ManagedServices();
    FakeService first = new FakeService("a");
    first.closeFailure = new IllegalStateException("a cannot close");
    services.register(first);
    FakeService failing = new FakeService("b");
    failing.startFailure = new IllegalStateException("b cannot start");
    services.register(failing);

    IllegalStateException e = assertThrows(IllegalStateException.class, services::start);

    assertSame(failing.startFailure, e);
    assertArrayEquals(new Throwable[] {first.closeFailure}, e.getSuppressed());
  }

  @Test
  public void testCloseContinuesPastFailuresAndThrowsTheFirst() throws Exception {
    ManagedServices services = new ManagedServices();
    FakeService a = new FakeService("a");
    a.closeFailure = new IllegalStateException("a cannot close");
    FakeService c = new FakeService("c");
    c.closeFailure = new IllegalStateException("c cannot close");
    services.register(a);
    services.register(new FakeService("b"));
    services.register(c);
    services.start();
    events.clear();

    IllegalStateException e = assertThrows(IllegalStateException.class, services::close);

    assertSame(c.closeFailure, e);
    assertArrayEquals(new Throwable[] {a.closeFailure}, e.getSuppressed());
    assertEquals(List.of("close c", "close b", "close a"), events);

    // Closing again closes nothing twice.
    events.clear();
    services.close();
    assertTrue(events.isEmpty());
  }

  @Test
  public void testCloseWithoutStartClosesWhatWasRegistered() throws Exception {
    ManagedServices services = new ManagedServices();
    services.register(new FakeService("a"));
    services.register(new FakeService("b"));

    services.close();

    assertEquals(List.of("close b", "close a"), events);
  }

  @Test
  public void testServiceRegisteredAfterStartIsStartedThen() throws Exception {
    ManagedServices services = new ManagedServices();
    services.register(new FakeService("a"));
    services.start();

    services.register(new FakeService("b"));
    services.close();

    assertEquals(List.of("start a", "start b", "close b", "close a"), events);
  }

  @Test
  public void testServiceRegisteredAfterStartThatFailsIsClosed() throws Exception {
    ManagedServices services = new ManagedServices();
    services.start();
    FakeService failing = new FakeService("a");
    failing.startFailure = new IllegalStateException("a cannot start");

    ManagedServices.ManagedServiceException e =
        assertThrows(
            ManagedServices.ManagedServiceException.class, () -> services.register(failing));

    assertSame(failing.startFailure, e.getCause());
    assertEquals(List.of("close a"), events);
    assertEquals(ManagedServices.State.STARTED, services.state());
  }

  @Test
  public void testServiceRegisteredWhileStartingIsStartedInTurn() throws Exception {
    ManagedServices services = new ManagedServices();
    FakeService late = new FakeService("late");
    FakeService builder =
        new FakeService("builder") {
          @Override
          public void start() throws Exception {
            super.start();
            services.register(late);
          }
        };
    services.register(builder);

    services.start();

    assertEquals(List.of("start builder", "start late"), events);
  }

  @Test
  public void testRegisterAfterCloseFails() throws Exception {
    ManagedServices services = new ManagedServices();
    services.close();

    assertThrows(IllegalStateException.class, () -> services.register(new FakeService("a")));
    // A plain object is not a service, so the registry hands it back.
    Object plain = new Object();
    assertSame(plain, services.register(plain));
  }

  @Test
  public void testStartTwiceFails() throws Exception {
    ManagedServices services = new ManagedServices();
    services.start();

    assertThrows(IllegalStateException.class, services::start);
  }

  @Test
  public void testDefaultNameAndClosingAdapter() throws Exception {
    assertEquals("FakeService", new FakeService("a").name());

    ManagedService closing = ManagedService.closing("store", () -> events.add("closed store"));
    assertEquals("store", closing.name());
    closing.start();
    closing.close();
    assertEquals(List.of("closed store"), events);
  }

  private class FakeService implements ManagedService {
    private final String id;
    Exception startFailure;
    Exception closeFailure;

    FakeService(String id) {
      this.id = id;
    }

    @Override
    public void start() throws Exception {
      if (startFailure != null) {
        throw startFailure;
      }
      events.add("start " + id);
    }

    @Override
    public void close() throws Exception {
      events.add("close " + id);
      if (closeFailure != null) {
        throw closeFailure;
      }
    }
  }
}
