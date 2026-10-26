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
package org.apache.gravitino.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Tests the authorization side effects of {@link SchemaEntityCleaner}. */
public class TestSchemaEntityCleaner {

  private static final Namespace SCHEMA_NAMESPACE = Namespace.of("metalake", "catalog");

  @Test
  public void testDeletedOrphanAncestorLosesPluginPrivileges() throws Exception {
    // A JDBC-backed Iceberg catalog keeps parent namespaces only implicitly. After "A:B" is
    // dropped, neither "A:B" nor "A" exists in the backend any more.
    NameIdentifier leaf = NameIdentifier.of(SCHEMA_NAMESPACE, "A:B");
    NameIdentifier ancestor = NameIdentifier.of(SCHEMA_NAMESPACE, "A");
    EntityStore store = mock(EntityStore.class);
    when(store.delete(any(), eq(Entity.EntityType.SCHEMA), anyBoolean())).thenReturn(true);
    Set<NameIdentifier> existing = Set.of();

    try (MockedStatic<AuthorizationUtils> authz = Mockito.mockStatic(AuthorizationUtils.class)) {
      SchemaEntityCleaner.deleteOrphanedSchemaEntities(store, leaf, false, existing::contains);

      // The cleaner cascade-deletes the outermost orphan "A", including its Gravitino owner and
      // securable-object rows.
      verify(store).delete(eq(ancestor), eq(Entity.EntityType.SCHEMA), eq(true));

      // Every schema the cleaner deletes must also lose its authorization plugin privileges (for
      // example Ranger owner policies) and its name-to-id cache entry, the same way a regular
      // schema drop does in SchemaHookDispatcher.dropSchema.
      authz.verify(
          () ->
              AuthorizationUtils.authorizationPluginRemovePrivileges(
                  eq(ancestor), eq(Entity.EntityType.SCHEMA), any()));
    }
  }

  @Test
  public void testExistingAncestorKeepsPluginPrivileges() throws Exception {
    NameIdentifier leaf = NameIdentifier.of(SCHEMA_NAMESPACE, "A:B");
    NameIdentifier ancestor = NameIdentifier.of(SCHEMA_NAMESPACE, "A");
    EntityStore store = mock(EntityStore.class);
    Set<NameIdentifier> existing = Set.of(ancestor);

    try (MockedStatic<AuthorizationUtils> authz = Mockito.mockStatic(AuthorizationUtils.class)) {
      SchemaEntityCleaner.deleteOrphanedSchemaEntities(store, leaf, false, existing::contains);

      verify(store, never()).delete(any(), any(), anyBoolean());
      authz.verify(
          () -> AuthorizationUtils.authorizationPluginRemovePrivileges(any(), any(), any()),
          never());
    }
  }
}
