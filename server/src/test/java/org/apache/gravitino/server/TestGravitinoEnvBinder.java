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
package org.apache.gravitino.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.catalog.CatalogAccess;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lineage.LineageDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.junit.jupiter.api.Test;

/** Checks that HK2 serves the dispatchers the server's {@link GravitinoEnv} getters return. */
public class TestGravitinoEnvBinder {

  @Test
  public void testBindsWhatTheEnvironmentGettersReturn() {
    // A mock stands for a GravitinoEnv subclass that overrides its getters: HK2 must serve the
    // overriding dispatchers, not the ones the environment's component built.
    GravitinoEnv env = mock(GravitinoEnv.class);
    MetalakeDispatcher metalakes = mock(MetalakeDispatcher.class);
    CatalogDispatcher catalogs = mock(CatalogDispatcher.class);
    SchemaDispatcher schemas = mock(SchemaDispatcher.class);
    TableDispatcher tables = mock(TableDispatcher.class);
    ViewDispatcher views = mock(ViewDispatcher.class);
    PartitionDispatcher partitions = mock(PartitionDispatcher.class);
    FilesetDispatcher filesets = mock(FilesetDispatcher.class);
    TopicDispatcher topics = mock(TopicDispatcher.class);
    TagDispatcher tags = mock(TagDispatcher.class);
    PolicyDispatcher policies = mock(PolicyDispatcher.class);
    CredentialOperationDispatcher credentials = mock(CredentialOperationDispatcher.class);
    SecretPropertyOperationDispatcher secretProperties =
        mock(SecretPropertyOperationDispatcher.class);
    SecretProviderRegistry secretProviders = mock(SecretProviderRegistry.class);
    ModelDispatcher models = mock(ModelDispatcher.class);
    FunctionDispatcher functions = mock(FunctionDispatcher.class);
    SemanticModelDispatcher semanticModels = mock(SemanticModelDispatcher.class);
    JobOperationDispatcher jobs = mock(JobOperationDispatcher.class);
    StatisticDispatcher statistics = mock(StatisticDispatcher.class);
    LineageDispatcher lineage = mock(LineageDispatcher.class);
    when(env.metalakeDispatcher()).thenReturn(metalakes);
    when(env.catalogDispatcher()).thenReturn(catalogs);
    when(env.schemaDispatcher()).thenReturn(schemas);
    when(env.tableDispatcher()).thenReturn(tables);
    when(env.viewDispatcher()).thenReturn(views);
    when(env.partitionDispatcher()).thenReturn(partitions);
    when(env.filesetDispatcher()).thenReturn(filesets);
    when(env.topicDispatcher()).thenReturn(topics);
    when(env.tagDispatcher()).thenReturn(tags);
    when(env.policyDispatcher()).thenReturn(policies);
    when(env.credentialOperationDispatcher()).thenReturn(credentials);
    when(env.secretPropertyOperationDispatcher()).thenReturn(secretProperties);
    when(env.secretProviderRegistry()).thenReturn(secretProviders);
    when(env.modelDispatcher()).thenReturn(models);
    when(env.functionDispatcher()).thenReturn(functions);
    when(env.semanticModelDispatcher()).thenReturn(semanticModels);
    when(env.jobOperationDispatcher()).thenReturn(jobs);
    when(env.statisticDispatcher()).thenReturn(statistics);

    ServiceLocator locator =
        ServiceLocatorUtilities.bind(new GravitinoEnvBinder(env, false, lineage));
    try {
      assertSame(metalakes, locator.getService(MetalakeDispatcher.class));
      assertSame(catalogs, locator.getService(CatalogDispatcher.class));
      assertSame(schemas, locator.getService(SchemaDispatcher.class));
      assertSame(tables, locator.getService(TableDispatcher.class));
      assertSame(views, locator.getService(ViewDispatcher.class));
      assertSame(partitions, locator.getService(PartitionDispatcher.class));
      assertSame(filesets, locator.getService(FilesetDispatcher.class));
      assertSame(topics, locator.getService(TopicDispatcher.class));
      assertSame(tags, locator.getService(TagDispatcher.class));
      assertSame(policies, locator.getService(PolicyDispatcher.class));
      assertSame(credentials, locator.getService(CredentialOperationDispatcher.class));
      assertSame(secretProperties, locator.getService(SecretPropertyOperationDispatcher.class));
      assertSame(secretProviders, locator.getService(SecretProviderRegistry.class));
      assertSame(models, locator.getService(ModelDispatcher.class));
      assertSame(functions, locator.getService(FunctionDispatcher.class));
      assertSame(semanticModels, locator.getService(SemanticModelDispatcher.class));
      assertSame(jobs, locator.getService(JobOperationDispatcher.class));
      assertSame(statistics, locator.getService(StatisticDispatcher.class));
      assertSame(lineage, locator.getService(LineageDispatcher.class));
      // Authorization is disabled, so no interception service is bound.
      assertNull(
          locator.getBestDescriptor(
              BuilderHelper.createContractFilter(InterceptionService.class.getName())));
    } finally {
      locator.shutdown();
    }
  }

  @Test
  public void testBindsTheHostServicesTheEnvironmentHas() {
    GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
    Config config = mock(Config.class);
    EventBus eventBus = mock(EventBus.class);
    IdGenerator idGenerator = mock(IdGenerator.class);
    EntityStore entityStore = mock(EntityStore.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    AuxiliaryServiceManager auxiliaryServiceManager = mock(AuxiliaryServiceManager.class);
    TableDispatcher internalTables = mock(TableDispatcher.class);
    ViewDispatcher internalViews = mock(ViewDispatcher.class);
    OwnerDispatcher internalOwners = mock(OwnerDispatcher.class);
    when(env.config()).thenReturn(config);
    when(env.eventBus()).thenReturn(eventBus);
    when(env.idGenerator()).thenReturn(idGenerator);
    when(env.entityStore()).thenReturn(entityStore);
    when(env.catalogManager()).thenReturn(catalogManager);
    when(env.auxServiceManager()).thenReturn(auxiliaryServiceManager);
    when(env.internalTableDispatcher()).thenReturn(internalTables);
    when(env.internalViewDispatcher()).thenReturn(internalViews);
    when(env.internalOwnerDispatcher()).thenReturn(internalOwners);
    when(env.accessControlDispatcher()).thenReturn(null);
    when(env.bulkManager()).thenReturn(null);

    ServiceLocator locator =
        ServiceLocatorUtilities.bind(
            new GravitinoEnvBinder(env, false, mock(LineageDispatcher.class)));
    try {
      assertSame(config, locator.getService(Config.class));
      assertSame(eventBus, locator.getService(EventBus.class));
      assertSame(idGenerator, locator.getService(IdGenerator.class));
      assertSame(entityStore, locator.getService(EntityStore.class));
      assertNull(locator.getService(CatalogManager.class));
      assertSame(catalogManager, locator.getService(CatalogAccess.class));
      assertSame(auxiliaryServiceManager, locator.getService(AuxiliaryServiceManager.class));
      assertSame(
          internalTables, locator.getService(TableDispatcher.class, InternalQualifier.INSTANCE));
      assertSame(
          internalViews, locator.getService(ViewDispatcher.class, InternalQualifier.INSTANCE));
      assertSame(
          internalOwners, locator.getService(OwnerDispatcher.class, InternalQualifier.INSTANCE));
      // An unqualified injection point gets the public chain, not the internal one.
      assertNotSame(internalOwners, locator.getService(OwnerDispatcher.class));
      // The getters that return null are not bound.
      assertNull(locator.getService(AccessControlDispatcher.class));
      assertNull(locator.getService(BulkManager.class));
    } finally {
      locator.shutdown();
    }
  }

  @Test
  public void testBindsTheInterceptionServiceWhenAuthorizationIsEnabled() {
    ServiceLocator locator =
        ServiceLocatorUtilities.bind(
            new GravitinoEnvBinder(
                mock(GravitinoEnv.class, RETURNS_MOCKS), true, mock(LineageDispatcher.class)));
    try {
      assertNotNull(
          locator.getBestDescriptor(
              BuilderHelper.createContractFilter(InterceptionService.class.getName())));
    } finally {
      locator.shutdown();
    }
  }
}
