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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.apache.commons.lang3.reflect.FieldUtils;
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
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lineage.LineageDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.server.authorization.MetadataAccessChecker;
import org.apache.gravitino.server.authorization.MetadataIdResolver;
import org.apache.gravitino.server.web.filter.GravitinoInterceptionService;
import org.apache.gravitino.server.web.rest.AuthnOperations;
import org.apache.gravitino.server.web.rest.BulkOperations;
import org.apache.gravitino.server.web.rest.GroupOperations;
import org.apache.gravitino.server.web.rest.HealthOperations;
import org.apache.gravitino.server.web.rest.IcebergRESTServiceOperations;
import org.apache.gravitino.server.web.rest.JobOperations;
import org.apache.gravitino.server.web.rest.MetadataObjectRoleOperations;
import org.apache.gravitino.server.web.rest.OwnerOperations;
import org.apache.gravitino.server.web.rest.PermissionOperations;
import org.apache.gravitino.server.web.rest.RoleOperations;
import org.apache.gravitino.server.web.rest.SemanticModelSourceValidator;
import org.apache.gravitino.server.web.rest.UserOperations;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.EnvironmentMetadataObjectDispatchers;
import org.apache.gravitino.utils.MetadataObjectDispatchers;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.junit.jupiter.api.Test;

/** Checks that HK2 serves the dispatchers the provision methods of the component return. */
public class TestGravitinoComponentBinder {

  @Test
  public void testBindsWhatTheProvisionMethodsReturn() {
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
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
    SecretManager secrets = mock(SecretManager.class);
    SecretProviderRegistry secretProviders = mock(SecretProviderRegistry.class);
    ModelDispatcher models = mock(ModelDispatcher.class);
    FunctionDispatcher functions = mock(FunctionDispatcher.class);
    SemanticModelDispatcher semanticModels = mock(SemanticModelDispatcher.class);
    JobOperationDispatcher jobs = mock(JobOperationDispatcher.class);
    StatisticDispatcher statistics = mock(StatisticDispatcher.class);
    LineageDispatcher lineage = mock(LineageDispatcher.class);
    when(component.metalakeDispatcher()).thenReturn(metalakes);
    when(component.catalogDispatcher()).thenReturn(catalogs);
    when(component.schemaDispatcher()).thenReturn(schemas);
    when(component.tableDispatcher()).thenReturn(tables);
    when(component.viewDispatcher()).thenReturn(views);
    when(component.partitionDispatcher()).thenReturn(partitions);
    when(component.filesetDispatcher()).thenReturn(filesets);
    when(component.topicDispatcher()).thenReturn(topics);
    when(component.tagDispatcher()).thenReturn(tags);
    when(component.policyDispatcher()).thenReturn(policies);
    when(component.credentialOperationDispatcher()).thenReturn(credentials);
    when(component.secretPropertyOperationDispatcher()).thenReturn(secretProperties);
    when(component.secretManager()).thenReturn(secrets);
    when(secrets.getRegistry()).thenReturn(secretProviders);
    when(component.modelDispatcher()).thenReturn(models);
    when(component.functionDispatcher()).thenReturn(functions);
    when(component.semanticModelDispatcher()).thenReturn(semanticModels);
    when(component.jobOperationDispatcher()).thenReturn(jobs);
    when(component.statisticDispatcher()).thenReturn(statistics);

    ServiceLocator locator =
        ServiceLocatorUtilities.bind(new GravitinoComponentBinder(component, false, lineage));
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
    } finally {
      locator.shutdown();
    }
  }

  @Test
  public void testBindsTheContractsAndRanksOfTheEnvironmentBinder() {
    for (boolean enableAuthorization : new boolean[] {false, true}) {
      LineageDispatcher lineage = mock(LineageDispatcher.class);
      GravitinoEnv env = mock(GravitinoEnv.class, RETURNS_MOCKS);
      GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
      // Both binders then advertise the same implementation class for the object dispatchers.
      when(component.metadataObjectDispatchers())
          .thenReturn(EnvironmentMetadataObjectDispatchers.of(env));
      Set<String> fromComponent =
          descriptors(new GravitinoComponentBinder(component, enableAuthorization, lineage));
      if (!enableAuthorization) {
        // The environment builds no access-control services when authorization is disabled.
        when(env.accessControlDispatcher()).thenReturn(null);
        when(env.ownerDispatcher()).thenReturn(null);
        when(env.internalOwnerDispatcher()).thenReturn(null);
        when(env.bulkManager()).thenReturn(null);
      }
      Set<String> fromEnvironment =
          descriptors(new GravitinoEnvBinder(env, enableAuthorization, lineage));

      assertEquals(fromEnvironment, fromComponent);
      assertEquals(
          enableAuthorization,
          fromComponent.stream().anyMatch(d -> d.contains(InterceptionService.class.getName())));
      assertTrue(fromComponent.stream().anyMatch(d -> d.contains(TableDispatcher.class.getName())));
      assertFalse(fromComponent.isEmpty());
    }
  }

  @Test
  public void testBindsTheHostServicesOfTheComponent() {
    for (boolean enableAuthorization : new boolean[] {false, true}) {
      GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
      Config config = mock(Config.class);
      EventBus eventBus = mock(EventBus.class);
      IdGenerator idGenerator = mock(IdGenerator.class);
      EntityStore entityStore = mock(EntityStore.class);
      CatalogManager catalogManager = mock(CatalogManager.class);
      AuxiliaryServiceManager auxiliaryServiceManager = mock(AuxiliaryServiceManager.class);
      TableDispatcher tables = mock(TableDispatcher.class);
      TableDispatcher internalTables = mock(TableDispatcher.class);
      AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);
      OwnerDispatcher owners = mock(OwnerDispatcher.class);
      OwnerDispatcher internalOwners = mock(OwnerDispatcher.class);
      BulkManager bulkManager = mock(BulkManager.class);
      when(component.config()).thenReturn(config);
      when(component.eventBus()).thenReturn(eventBus);
      when(component.idGenerator()).thenReturn(idGenerator);
      when(component.entityStore()).thenReturn(entityStore);
      when(component.catalogManager()).thenReturn(catalogManager);
      when(component.catalogAccess()).thenReturn(catalogManager);
      when(component.auxiliaryServiceManager()).thenReturn(auxiliaryServiceManager);
      when(component.tableDispatcher()).thenReturn(tables);
      when(component.internalTableDispatcher()).thenReturn(internalTables);
      when(component.accessControlDispatcher()).thenReturn(accessControl);
      when(component.ownerDispatcher()).thenReturn(owners);
      when(component.internalOwnerDispatcher()).thenReturn(internalOwners);
      when(component.bulkManager()).thenReturn(bulkManager);
      MetadataObjectDispatchers objectDispatchers = mock(MetadataObjectDispatchers.class);
      when(component.metadataObjectDispatchers()).thenReturn(objectDispatchers);

      ServiceLocator locator =
          ServiceLocatorUtilities.bind(
              new GravitinoComponentBinder(
                  component, enableAuthorization, mock(LineageDispatcher.class)));
      try {
        assertSame(config, locator.getService(Config.class));
        assertSame(eventBus, locator.getService(EventBus.class));
        assertSame(idGenerator, locator.getService(IdGenerator.class));
        assertSame(entityStore, locator.getService(EntityStore.class));
        assertSame(catalogManager, locator.getService(CatalogManager.class));
        assertSame(catalogManager, locator.getService(CatalogAccess.class));
        assertSame(auxiliaryServiceManager, locator.getService(AuxiliaryServiceManager.class));
        assertNotNull(locator.getService(MetadataAccessChecker.class));
        assertNotNull(locator.getService(MetadataIdResolver.class));
        assertSame(objectDispatchers, locator.getService(MetadataObjectDispatchers.class));
        assertSame(tables, locator.getService(TableDispatcher.class));
        assertSame(
            internalTables, locator.getService(TableDispatcher.class, InternalQualifier.INSTANCE));
        if (enableAuthorization) {
          assertSame(accessControl, locator.getService(AccessControlDispatcher.class));
          assertSame(owners, locator.getService(OwnerDispatcher.class));
          assertSame(
              internalOwners,
              locator.getService(OwnerDispatcher.class, InternalQualifier.INSTANCE));
          assertSame(bulkManager, locator.getService(BulkManager.class));
        } else {
          assertNull(locator.getService(AccessControlDispatcher.class));
          assertNull(locator.getService(OwnerDispatcher.class));
          assertNull(locator.getService(OwnerDispatcher.class, InternalQualifier.INSTANCE));
          assertNull(locator.getService(BulkManager.class));
        }
      } finally {
        locator.shutdown();
      }
    }
  }

  @Test
  public void testBuildsTheServerResourcesFromTheHostServices() throws Exception {
    for (boolean enableAuthorization : new boolean[] {false, true}) {
      GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
      Config config = mock(Config.class);
      EventBus eventBus = mock(EventBus.class);
      IdGenerator idGenerator = mock(IdGenerator.class);
      EntityStore entityStore = mock(EntityStore.class);
      CatalogManager catalogManager = mock(CatalogManager.class);
      AuxiliaryServiceManager auxiliaryServiceManager = mock(AuxiliaryServiceManager.class);
      TableDispatcher internalTables = mock(TableDispatcher.class);
      AccessControlDispatcher accessControl = mock(AccessControlDispatcher.class);
      OwnerDispatcher owners = mock(OwnerDispatcher.class);
      OwnerDispatcher internalOwners = mock(OwnerDispatcher.class);
      BulkManager bulkManager = mock(BulkManager.class);
      when(component.config()).thenReturn(config);
      when(component.eventBus()).thenReturn(eventBus);
      when(component.idGenerator()).thenReturn(idGenerator);
      when(component.entityStore()).thenReturn(entityStore);
      when(component.catalogManager()).thenReturn(catalogManager);
      when(component.catalogAccess()).thenReturn(catalogManager);
      when(component.auxiliaryServiceManager()).thenReturn(auxiliaryServiceManager);
      when(component.internalTableDispatcher()).thenReturn(internalTables);
      when(component.accessControlDispatcher()).thenReturn(accessControl);
      when(component.ownerDispatcher()).thenReturn(owners);
      when(component.internalOwnerDispatcher()).thenReturn(internalOwners);
      when(component.bulkManager()).thenReturn(bulkManager);

      ServiceLocator locator =
          ServiceLocatorUtilities.bind(
              new GravitinoComponentBinder(
                  component, enableAuthorization, mock(LineageDispatcher.class)));
      try {
        AccessControlDispatcher expectedAccessControl = enableAuthorization ? accessControl : null;
        OwnerDispatcher expectedOwners = enableAuthorization ? owners : null;
        OwnerDispatcher expectedInternalOwners = enableAuthorization ? internalOwners : null;

        UserOperations users = locator.createAndInitialize(UserOperations.class);
        assertSame(expectedAccessControl, field(users, "accessControlManager"));
        assertSame(expectedInternalOwners, field(users, "ownerManager"));
        GroupOperations groups = locator.createAndInitialize(GroupOperations.class);
        assertSame(expectedAccessControl, field(groups, "accessControlManager"));
        assertSame(expectedInternalOwners, field(groups, "ownerDispatcher"));
        assertSame(
            expectedAccessControl,
            field(locator.createAndInitialize(RoleOperations.class), "accessControlManager"));
        assertSame(
            expectedAccessControl,
            field(locator.createAndInitialize(PermissionOperations.class), "accessControlManager"));
        assertSame(
            expectedAccessControl,
            field(
                locator.createAndInitialize(MetadataObjectRoleOperations.class),
                "accessControlDispatcher"));
        assertSame(
            expectedAccessControl,
            field(locator.createAndInitialize(AuthnOperations.class), "accessControlDispatcher"));
        assertSame(
            expectedOwners,
            field(locator.createAndInitialize(OwnerOperations.class), "ownerDispatcher"));
        BulkOperations bulk = locator.createAndInitialize(BulkOperations.class);
        assertSame(enableAuthorization ? bulkManager : null, field(bulk, "bulkManager"));
        assertSame(expectedAccessControl, field(bulk, "accessControlDispatcher"));
        assertSame(expectedInternalOwners, field(bulk, "ownerDispatcher"));

        assertSame(
            idGenerator, supplied(locator.createAndInitialize(JobOperations.class), "idGenerator"));
        HealthOperations health = locator.createAndInitialize(HealthOperations.class);
        assertSame(entityStore, supplied(health, "entityStore"));
        assertSame(config, supplied(health, "config"));
        IcebergRESTServiceOperations iceberg =
            locator.createAndInitialize(IcebergRESTServiceOperations.class);
        assertSame(auxiliaryServiceManager, supplied(iceberg, "auxServiceManager"));
        assertSame(config, supplied(iceberg, "config"));
        GravitinoInterceptionService interception =
            locator.createAndInitialize(GravitinoInterceptionService.class);
        assertSame(eventBus, supplied(interception, "eventBus"));
        assertSame(internalTables, supplied(interception, "internalTableDispatcher"));
        assertNotNull(locator.getService(SemanticModelSourceValidator.class));
      } finally {
        locator.shutdown();
      }
    }
  }

  private static Object field(Object target, String name) throws IllegalAccessException {
    return FieldUtils.readField(target, name, true);
  }

  private static Object supplied(Object target, String name) throws IllegalAccessException {
    return ((Supplier<?>) field(target, name)).get();
  }

  /** Returns the contracts, rank and scope of every service a binder adds, as sortable strings. */
  private static Set<String> descriptors(AbstractBinder binder) {
    ServiceLocator locator = ServiceLocatorUtilities.bind(binder);
    try {
      Set<String> descriptors = new TreeSet<>();
      for (ActiveDescriptor<?> descriptor : locator.getDescriptors(d -> true)) {
        descriptors.add(
            new TreeSet<>(descriptor.getAdvertisedContracts())
                + " rank="
                + descriptor.getRanking()
                + " scope="
                + descriptor.getScope());
      }
      return descriptors;
    } finally {
      locator.shutdown();
    }
  }
}
