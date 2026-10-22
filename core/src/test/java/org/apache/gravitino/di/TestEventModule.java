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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.audit.DummyAuditFormatter;
import org.apache.gravitino.audit.DummyAuditWriter;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.junit.jupiter.api.Test;

public class TestEventModule {

  @Test
  public void testEventObjectsAreSingletons() {
    EventComponent component =
        DaggerTestEventModule_EventComponent.factory().create(new Config(false) {});

    assertNotNull(component.eventBus());
    assertSame(component.eventListenerManager(), component.eventListenerManager());
    assertSame(component.eventBus(), component.eventBus());
    assertSame(component.auditLogManager(), component.auditLogManager());
  }

  @Test
  public void testEventBusComesFromTheListenerManager() {
    EventComponent component =
        DaggerTestEventModule_EventComponent.factory().create(new Config(false) {});

    EventListenerManager manager = component.eventListenerManager();
    // The bus holds the listeners the manager loaded; with none configured it has none.
    assertNotNull(manager);
    assertEquals(0, component.eventBus().getEventListeners().size());
  }

  @Test
  public void testTheEventBusRegistersTheAuditListenerFirst() {
    Config config = new Config(false) {};
    config.set(Configs.AUDIT_LOG_ENABLED_CONF, true);
    config.set(Configs.AUDIT_LOG_WRITER_CLASS_NAME, DummyAuditWriter.class.getName());
    config.set(Configs.AUDIT_LOG_FORMATTER_CLASS_NAME, DummyAuditFormatter.class.getName());
    EventComponent component = DaggerTestEventModule_EventComponent.factory().create(config);

    // Asking for the bus alone is enough: it depends on the audit log manager.
    EventBus eventBus = component.eventBus();

    assertEquals(1, eventBus.getEventListeners().size());
    assertSame(eventBus, component.eventBus());
  }

  /** A graph of the event module alone. */
  @Singleton
  @Component(modules = EventModule.class)
  interface EventComponent {
    EventListenerManager eventListenerManager();

    EventBus eventBus();

    AuditLogManager auditLogManager();

    /** Creates event components. */
    @Component.Factory
    interface Factory {
      EventComponent create(@BindsInstance Config config);
    }
  }
}
