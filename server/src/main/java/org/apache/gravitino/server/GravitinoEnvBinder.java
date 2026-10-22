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

import javax.annotation.Nullable;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.EnvironmentAuthorizationServices;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.bulk.BulkManager;
import org.apache.gravitino.catalog.CatalogAccess;
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
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lineage.LineageDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.server.authorization.MetadataAccessChecker;
import org.apache.gravitino.server.authorization.MetadataIdResolver;
import org.apache.gravitino.server.web.filter.GravitinoInterceptionService;
import org.apache.gravitino.server.web.rest.SemanticModelSourceValidator;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.EnvironmentMetadataObjectDispatchers;
import org.apache.gravitino.utils.MetadataObjectDispatchers;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/**
 * Binds the dispatchers the REST resources inject into HK2, read from the {@link GravitinoEnv}
 * getters of the environment the server was given.
 *
 * <p>Only a server built with the deprecated {@code GravitinoServer(ServerConfig, GravitinoEnv)}
 * uses it: the getters are virtual, so a subclass of {@link GravitinoEnv} that overrides them gets
 * the dispatchers it returns. Every other server binds from the component's provision methods with
 * {@link GravitinoComponentBinder}, which binds the same contracts and ranks. This binder is
 * deleted with the deprecated constructor.
 */
class GravitinoEnvBinder extends AbstractBinder {

  private final GravitinoEnv gravitinoEnv;
  private final boolean enableAuthorization;
  private final LineageDispatcher lineageDispatcher;

  /**
   * Creates a binder.
   *
   * @param gravitinoEnv the environment whose getters supply the dispatchers
   * @param enableAuthorization whether to bind the authorization interception service
   * @param lineageDispatcher the lineage dispatcher of the server
   */
  GravitinoEnvBinder(
      GravitinoEnv gravitinoEnv, boolean enableAuthorization, LineageDispatcher lineageDispatcher) {
    this.gravitinoEnv = gravitinoEnv;
    this.enableAuthorization = enableAuthorization;
    this.lineageDispatcher = lineageDispatcher;
  }

  @Override
  protected void configure() {
    if (enableAuthorization) {
      bind(GravitinoInterceptionService.class).to(InterceptionService.class).in(Singleton.class);
    }
    bind(gravitinoEnv.metalakeDispatcher()).to(MetalakeDispatcher.class).ranked(1);
    bind(gravitinoEnv.catalogDispatcher()).to(CatalogDispatcher.class).ranked(1);
    bind(gravitinoEnv.schemaDispatcher()).to(SchemaDispatcher.class).ranked(1);
    bindAsContract(SemanticModelSourceValidator.class);
    bind(gravitinoEnv.tableDispatcher()).to(TableDispatcher.class).ranked(1);
    bind(gravitinoEnv.viewDispatcher()).to(ViewDispatcher.class).ranked(1);
    bind(gravitinoEnv.partitionDispatcher()).to(PartitionDispatcher.class).ranked(1);
    bind(gravitinoEnv.filesetDispatcher()).to(FilesetDispatcher.class).ranked(1);
    bind(gravitinoEnv.topicDispatcher()).to(TopicDispatcher.class).ranked(1);
    bind(gravitinoEnv.tagDispatcher()).to(TagDispatcher.class).ranked(1);
    bind(gravitinoEnv.policyDispatcher()).to(PolicyDispatcher.class).ranked(1);
    bind(gravitinoEnv.credentialOperationDispatcher())
        .to(CredentialOperationDispatcher.class)
        .ranked(1);
    bind(gravitinoEnv.secretPropertyOperationDispatcher())
        .to(SecretPropertyOperationDispatcher.class)
        .ranked(1);
    bind(gravitinoEnv.secretProviderRegistry()).to(SecretProviderRegistry.class).ranked(1);
    bind(gravitinoEnv.modelDispatcher()).to(ModelDispatcher.class).ranked(1);
    bind(gravitinoEnv.functionDispatcher()).to(FunctionDispatcher.class).ranked(1);
    bind(gravitinoEnv.semanticModelDispatcher()).to(SemanticModelDispatcher.class).ranked(1);
    bind(lineageDispatcher).to(LineageDispatcher.class).ranked(1);
    bind(gravitinoEnv.jobOperationDispatcher()).to(JobOperationDispatcher.class).ranked(1);
    bind(gravitinoEnv.statisticDispatcher()).to(StatisticDispatcher.class).ranked(1);
    bindHostServices();
  }

  /**
   * Binds the host services the REST resources and filters inject besides the dispatchers. A getter
   * that returns {@code null}, such as an access-control service when authorization is disabled, is
   * not bound; a resource that runs without it injects it as {@code @Optional}.
   */
  private void bindHostServices() {
    bindIfPresent(gravitinoEnv.config(), Config.class);
    bindIfPresent(gravitinoEnv.eventBus(), EventBus.class);
    bindIfPresent(gravitinoEnv.idGenerator(), IdGenerator.class);
    bindIfPresent(gravitinoEnv.entityStore(), EntityStore.class);
    CatalogAccess catalogAccess = gravitinoEnv.catalogManager();
    if (catalogAccess != null) {
      // Advertised only as CatalogAccess, so no resource can inject the catalog manager under it.
      addActiveDescriptor(ContractBinding.only(catalogAccess, CatalogAccess.class));
    }
    bindIfPresent(gravitinoEnv.auxServiceManager(), AuxiliaryServiceManager.class);
    TableDispatcher internalTableDispatcher = gravitinoEnv.internalTableDispatcher();
    if (internalTableDispatcher != null) {
      bind(internalTableDispatcher)
          .to(TableDispatcher.class)
          .qualifiedBy(InternalQualifier.INSTANCE)
          .ranked(InternalQualifier.RANK);
    }
    ViewDispatcher internalViewDispatcher = gravitinoEnv.internalViewDispatcher();
    if (internalViewDispatcher != null) {
      bind(internalViewDispatcher)
          .to(ViewDispatcher.class)
          .qualifiedBy(InternalQualifier.INSTANCE)
          .ranked(InternalQualifier.RANK);
    }
    bindIfPresent(gravitinoEnv.accessControlDispatcher(), AccessControlDispatcher.class);
    bindIfPresent(gravitinoEnv.ownerDispatcher(), OwnerDispatcher.class);
    OwnerDispatcher internalOwnerDispatcher = gravitinoEnv.internalOwnerDispatcher();
    if (internalOwnerDispatcher != null) {
      bind(internalOwnerDispatcher)
          .to(OwnerDispatcher.class)
          .qualifiedBy(InternalQualifier.INSTANCE)
          .ranked(InternalQualifier.RANK);
    }
    bindIfPresent(gravitinoEnv.bulkManager(), BulkManager.class);
    Config config = gravitinoEnv.config();
    if (config != null) {
      bind(new MetadataAccessChecker(
              config,
              gravitinoEnv::gravitinoAuthorizer,
              gravitinoEnv::entityStore,
              gravitinoEnv::internalSchemaDispatcher))
          .to(MetadataAccessChecker.class)
          .ranked(1);
    }
    bind(EnvironmentMetadataObjectDispatchers.of(gravitinoEnv))
        .to(MetadataObjectDispatchers.class)
        .ranked(1);
    bind(EnvironmentAuthorizationServices.of(gravitinoEnv))
        .to(AuthorizationServices.class)
        .ranked(1);
    bind(new MetadataIdResolver(gravitinoEnv::catalogManager, gravitinoEnv::entityStore))
        .to(MetadataIdResolver.class)
        .ranked(1);
  }

  private <T> void bindIfPresent(@Nullable T service, Class<? super T> contract) {
    if (service != null) {
      bind(service).to(contract).ranked(1);
    }
  }
}
