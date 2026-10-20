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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.secret.SecretManager;

/**
 * The leaves {@link ModuleTestComponent} takes instead of building them: the stores, the event
 * objects and the authorizer. Tests replace a field before the first request to use another fake.
 */
class FakeLeaves {
  EntityStore entityStore =
      mock(EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
  KmsClientRegistry kmsClientRegistry = new KmsClientRegistry(new Config(false) {});
  SecretManager secretManager = mock(SecretManager.class);
  EventListenerManager eventListenerManager = mock(EventListenerManager.class);
  EventBus eventBus = mock(EventBus.class);
  AuditLogManager auditLogManager = mock(AuditLogManager.class);
  GravitinoAuthorizer gravitinoAuthorizer = NoopGravitinoAuthorizer.INSTANCE;
}
