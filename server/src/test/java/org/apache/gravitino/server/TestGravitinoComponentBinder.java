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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.gravitino.catalog.CatalogDispatcher;
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
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
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
    GravitinoComponent component = mock(GravitinoComponent.class);
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
  public void testBindsEachDispatcherAtRankOneAndInterceptsOnlyWithAuthorization() {
    List<Class<?>> contracts =
        List.of(
            MetalakeDispatcher.class,
            CatalogDispatcher.class,
            SchemaDispatcher.class,
            TableDispatcher.class,
            ViewDispatcher.class,
            PartitionDispatcher.class,
            FilesetDispatcher.class,
            TopicDispatcher.class,
            TagDispatcher.class,
            PolicyDispatcher.class,
            CredentialOperationDispatcher.class,
            SecretPropertyOperationDispatcher.class,
            SecretProviderRegistry.class,
            ModelDispatcher.class,
            FunctionDispatcher.class,
            SemanticModelDispatcher.class,
            LineageDispatcher.class,
            JobOperationDispatcher.class,
            StatisticDispatcher.class);
    for (boolean enableAuthorization : new boolean[] {false, true}) {
      Set<String> descriptors =
          descriptors(
              new GravitinoComponentBinder(
                  mock(GravitinoComponent.class, RETURNS_MOCKS),
                  enableAuthorization,
                  mock(LineageDispatcher.class)));

      assertEquals(
          enableAuthorization,
          descriptors.stream().anyMatch(d -> d.contains(InterceptionService.class.getName())));
      for (Class<?> contract : contracts) {
        assertTrue(
            descriptors.stream()
                .anyMatch(d -> d.contains(contract.getName()) && d.contains("rank=1")),
            contract.getSimpleName() + " is not bound at rank 1: " + descriptors);
      }
    }
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
