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
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.secret.SecretManager;

/** Binds the {@link FakeLeaves} in place of the storage and event modules. */
@SuppressWarnings("CloseableProvides")
@Module
abstract class FakeLeavesModule {

  private FakeLeavesModule() {}

  @Provides
  static EntityStore entityStore(FakeLeaves leaves) {
    return leaves.entityStore;
  }

  @Provides
  static KmsClientRegistry kmsClientRegistry(FakeLeaves leaves) {
    return leaves.kmsClientRegistry;
  }

  @Provides
  static SecretManager secretManager(FakeLeaves leaves) {
    return leaves.secretManager;
  }

  @Provides
  static EventListenerManager eventListenerManager(FakeLeaves leaves) {
    return leaves.eventListenerManager;
  }

  @Provides
  static EventBus eventBus(FakeLeaves leaves) {
    return leaves.eventBus;
  }

  @Provides
  static AuditLogManager auditLogManager(FakeLeaves leaves) {
    return leaves.auditLogManager;
  }

  @Provides
  static GravitinoAuthorizer gravitinoAuthorizer(FakeLeaves leaves) {
    return leaves.gravitinoAuthorizer;
  }

  @Provides
  static LegacyBridge legacyBridge(FakeLeaves leaves) {
    return () -> leaves.gravitinoAuthorizer;
  }
}
