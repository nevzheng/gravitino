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

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;

/**
 * Binds the event listener manager, the event bus it creates, and the audit log manager that
 * listens to it.
 *
 * <p>Dagger does not start or stop the listener manager; {@code GravitinoEnv} does.
 */
@Module
abstract class EventModule {

  private EventModule() {}

  /** Builds the listener manager and loads the listeners named by the configuration. */
  @Provides
  @Singleton
  static EventListenerManager eventListenerManager(Config config) {
    EventListenerManager eventListenerManager = new EventListenerManager();
    eventListenerManager.init(
        config.getConfigsWithPrefix(EventListenerManager.GRAVITINO_EVENT_LISTENER_PREFIX));
    return eventListenerManager;
  }

  /**
   * Creates the event bus. It depends on the audit log manager, so the audit listener is registered
   * before anything can post an event, whichever object asks for the bus first; no caller has to
   * build the audit log manager for its side effect.
   */
  @Provides
  @Singleton
  static EventBus eventBus(
      EventListenerManager eventListenerManager,
      // A dependency only for its order: building it registers the audit listener.
      @SuppressWarnings("UnusedVariable") AuditLogManager auditLog) {
    return eventListenerManager.createEventBus();
  }

  /**
   * Builds the audit log manager, which registers its audit listener with the listener manager when
   * audit logging is enabled.
   */
  @Provides
  @Singleton
  static AuditLogManager auditLogManager(Config config, EventListenerManager eventListenerManager) {
    AuditLogManager auditLogManager = new AuditLogManager();
    auditLogManager.init(config, eventListenerManager);
    return auditLogManager;
  }
}
