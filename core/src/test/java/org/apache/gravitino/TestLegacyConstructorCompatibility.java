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
package org.apache.gravitino;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Collections;
import java.util.function.Supplier;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.tag.TagManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@SuppressWarnings("removal")
public class TestLegacyConstructorCompatibility {

  @Test
  void testLegacyPublicConstructorsRemainAvailable() {
    assertDoesNotThrow(() -> JobHookDispatcher.class.getConstructor(JobOperationDispatcher.class));
    assertDoesNotThrow(() -> TagHookDispatcher.class.getConstructor(TagDispatcher.class));
    assertDoesNotThrow(() -> PolicyHookDispatcher.class.getConstructor(PolicyDispatcher.class));
    assertDoesNotThrow(() -> TagManager.class.getConstructor(IdGenerator.class, EntityStore.class));
    assertDoesNotThrow(
        () -> PolicyManager.class.getConstructor(IdGenerator.class, EntityStore.class));
    assertDoesNotThrow(
        () ->
            TableOperationDispatcher.class.getConstructor(
                CatalogManager.class, EntityStore.class, IdGenerator.class));
    assertDoesNotThrow(
        () ->
            TableOperationDispatcher.class.getConstructor(
                CatalogManager.class, EntityStore.class, IdGenerator.class, Supplier.class));
  }

  @Test
  void testLegacyConstructorsDoNotResolveDependenciesEagerly() {
    try (MockedStatic<LegacyRuntimeDependencies> dependencies =
        Mockito.mockStatic(LegacyRuntimeDependencies.class)) {
      JobOperationDispatcher jobDispatcher = Mockito.mock(JobOperationDispatcher.class);
      TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
      PolicyDispatcher policyDispatcher = Mockito.mock(PolicyDispatcher.class);
      IdGenerator idGenerator = Mockito.mock(IdGenerator.class);
      EntityStore entityStore = Mockito.mock(EntityStore.class);
      EntityStore relationalEntityStore =
          Mockito.mock(
              EntityStore.class,
              Mockito.withSettings().extraInterfaces(SupportsRelationOperations.class));
      CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
      Supplier<SchemaDispatcher> schemaDispatcherSupplier =
          () -> Mockito.mock(SchemaDispatcher.class);

      new JobHookDispatcher(jobDispatcher);
      new TagHookDispatcher(tagDispatcher);
      new PolicyHookDispatcher(policyDispatcher);
      new TagManager(idGenerator, entityStore);
      new PolicyManager(idGenerator, relationalEntityStore);
      new TableOperationDispatcher(catalogManager, entityStore, idGenerator);
      new TableOperationDispatcher(
          catalogManager, entityStore, idGenerator, schemaDispatcherSupplier);

      dependencies.verifyNoInteractions();
    }
  }

  @Test
  void testLegacyHookResolvesOptionalOwnerForEveryOperation() {
    try (MockedStatic<LegacyRuntimeDependencies> dependencies =
        Mockito.mockStatic(LegacyRuntimeDependencies.class)) {
      TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
      TagHookDispatcher hook = new TagHookDispatcher(tagDispatcher);

      hook.createTag("metalake", "tag", null, Collections.emptyMap());
      hook.createTag("metalake", "tag", null, Collections.emptyMap());

      dependencies.verify(LegacyRuntimeDependencies::ownerDispatcher, Mockito.times(2));
    }
  }
}
