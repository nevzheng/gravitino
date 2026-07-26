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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metadata.MetadataObjectExistenceChecker;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestGovernanceServices {

  @TempDir private Path tempDir;

  @Test
  void testComponentOwnsStableGovernanceRoots() throws IOException {
    Fixture fixture = new Fixture(tempDir.resolve("stable"));
    OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);
    GovernanceServices services = fixture.create(fixture.accessControlDispatcher, ownerDispatcher);
    JobOperationDispatcher jobOperationDispatcher = services.jobOperationDispatcher();

    try {
      assertInstanceOf(TagHookDispatcher.class, services.tagDispatcher());
      assertInstanceOf(PolicyHookDispatcher.class, services.policyDispatcher());
      assertInstanceOf(JobHookDispatcher.class, jobOperationDispatcher);
      assertInstanceOf(
          BuiltInJobTemplateEventListener.class, services.builtInJobTemplateEventListener());
      assertSame(services.tagDispatcher(), services.tagDispatcher());
      assertSame(services.policyDispatcher(), services.policyDispatcher());
      assertSame(jobOperationDispatcher, services.jobOperationDispatcher());
      assertSame(services.jobManager(), services.jobManager());
      assertSame(
          services.builtInJobTemplateEventListener(), services.builtInJobTemplateEventListener());
      assertSame(
          services.metadataObjectExistenceChecker(), services.metadataObjectExistenceChecker());
      verifyNoInteractions(
          fixture.metalakeDispatcher,
          fixture.catalogDispatcher,
          fixture.schemaDispatcher,
          fixture.tableDispatcher,
          fixture.filesetDispatcher,
          fixture.topicDispatcher,
          fixture.modelDispatcher,
          fixture.functionDispatcher,
          fixture.viewDispatcher,
          fixture.accessControlDispatcher,
          ownerDispatcher);
    } finally {
      jobOperationDispatcher.close();
    }
  }

  @Test
  void testProviderEdgesResolveCyclesWithoutPrematureJobCreation() throws IOException {
    Fixture fixture = new Fixture(tempDir.resolve("lazy"));
    GovernanceServices services = fixture.create(fixture.accessControlDispatcher, null);

    assertFalse(fixture.stagingDir.toFile().exists());
    when(fixture.catalogDispatcher.catalogExists(NameIdentifier.of("metalake", "catalog")))
        .thenReturn(true);
    MetadataObjectExistenceChecker checker = services.metadataObjectExistenceChecker();
    checker.check("metalake", MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG));
    verify(fixture.catalogDispatcher).catalogExists(NameIdentifier.of("metalake", "catalog"));
    assertFalse(fixture.stagingDir.toFile().exists());

    assertInstanceOf(TagHookDispatcher.class, services.tagDispatcher());
    assertInstanceOf(PolicyHookDispatcher.class, services.policyDispatcher());
    assertFalse(fixture.stagingDir.toFile().exists());

    JobOperationDispatcher jobOperationDispatcher = services.jobOperationDispatcher();
    try {
      assertTrue(fixture.stagingDir.toFile().isDirectory());
      verifyNoInteractions(
          fixture.metalakeDispatcher,
          fixture.schemaDispatcher,
          fixture.tableDispatcher,
          fixture.filesetDispatcher,
          fixture.topicDispatcher,
          fixture.modelDispatcher,
          fixture.functionDispatcher,
          fixture.viewDispatcher,
          fixture.accessControlDispatcher);
    } finally {
      jobOperationDispatcher.close();
    }
  }

  @Test
  void testNullableAuthorizationBindingsBuildAllRoots() throws IOException {
    Fixture fixture = new Fixture(tempDir.resolve("nullable"));
    GovernanceServices services = fixture.create(null, null);
    JobOperationDispatcher jobOperationDispatcher = services.jobOperationDispatcher();

    try {
      assertInstanceOf(TagHookDispatcher.class, services.tagDispatcher());
      assertInstanceOf(PolicyHookDispatcher.class, services.policyDispatcher());
      assertInstanceOf(JobHookDispatcher.class, jobOperationDispatcher);
      assertInstanceOf(
          BuiltInJobTemplateEventListener.class, services.builtInJobTemplateEventListener());
    } finally {
      jobOperationDispatcher.close();
    }
  }

  @Test
  void testSeparateComponentsDoNotShareGovernanceServices() {
    Fixture fixture = new Fixture(tempDir.resolve("isolated"));
    GovernanceServices first = fixture.create(fixture.accessControlDispatcher, null);
    GovernanceServices second = fixture.create(fixture.accessControlDispatcher, null);

    TagDispatcher firstTagDispatcher = first.tagDispatcher();
    PolicyDispatcher firstPolicyDispatcher = first.policyDispatcher();
    assertNotSame(firstTagDispatcher, second.tagDispatcher());
    assertNotSame(firstPolicyDispatcher, second.policyDispatcher());
    assertNotSame(first.metadataObjectExistenceChecker(), second.metadataObjectExistenceChecker());
  }

  private static final class Fixture {
    private final Path stagingDir;
    private final Config config = new Config(false) {};
    private final EntityStore entityStore =
        mock(EntityStore.class, withSettings().extraInterfaces(SupportsRelationOperations.class));
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final EventBus eventBus = mock(EventBus.class);
    private final LockManager lockManager = mock(LockManager.class);
    private final MetalakeDispatcher metalakeDispatcher = mock(MetalakeDispatcher.class);
    private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
    private final SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);
    private final TableDispatcher tableDispatcher = mock(TableDispatcher.class);
    private final FilesetDispatcher filesetDispatcher = mock(FilesetDispatcher.class);
    private final TopicDispatcher topicDispatcher = mock(TopicDispatcher.class);
    private final ModelDispatcher modelDispatcher = mock(ModelDispatcher.class);
    private final FunctionDispatcher functionDispatcher = mock(FunctionDispatcher.class);
    private final ViewDispatcher viewDispatcher = mock(ViewDispatcher.class);
    private final AccessControlDispatcher accessControlDispatcher =
        mock(AccessControlDispatcher.class);

    private Fixture(Path stagingDir) {
      this.stagingDir = stagingDir;
      config.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    }

    private GovernanceServices create(
        AccessControlDispatcher accessControlDispatcher, OwnerDispatcher ownerDispatcher) {
      return GovernanceServices.create(
          config,
          entityStore,
          idGenerator,
          eventBus,
          lockManager,
          metalakeDispatcher,
          catalogDispatcher,
          schemaDispatcher,
          tableDispatcher,
          filesetDispatcher,
          topicDispatcher,
          modelDispatcher,
          functionDispatcher,
          viewDispatcher,
          accessControlDispatcher,
          ownerDispatcher);
    }
  }
}
