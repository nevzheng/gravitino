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

import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
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
import org.apache.gravitino.di.GravitinoComponent;
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
import org.apache.gravitino.utils.MetadataObjectDispatchers;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/**
 * Binds the dispatchers the REST resources inject into HK2, read from the provision methods of the
 * server's {@link GravitinoComponent} (design section 5.6).
 *
 * <p>Each dispatcher is the {@code @Public} chain of its type, so it carries the decorators a
 * downstream component contributes.
 */
class GravitinoComponentBinder extends AbstractBinder {

  private final GravitinoComponent component;
  private final boolean enableAuthorization;
  private final LineageDispatcher lineageDispatcher;

  /**
   * Creates a binder.
   *
   * @param component the component whose provision methods supply the dispatchers
   * @param enableAuthorization whether to bind the authorization interception service
   * @param lineageDispatcher the lineage dispatcher of the server
   */
  GravitinoComponentBinder(
      GravitinoComponent component,
      boolean enableAuthorization,
      LineageDispatcher lineageDispatcher) {
    this.component = component;
    this.enableAuthorization = enableAuthorization;
    this.lineageDispatcher = lineageDispatcher;
  }

  @Override
  protected void configure() {
    if (enableAuthorization) {
      bind(GravitinoInterceptionService.class).to(InterceptionService.class).in(Singleton.class);
    }
    bind(component.metalakeDispatcher()).to(MetalakeDispatcher.class).ranked(1);
    bind(component.catalogDispatcher()).to(CatalogDispatcher.class).ranked(1);
    bind(component.schemaDispatcher()).to(SchemaDispatcher.class).ranked(1);
    bindAsContract(SemanticModelSourceValidator.class);
    bind(component.tableDispatcher()).to(TableDispatcher.class).ranked(1);
    bind(component.viewDispatcher()).to(ViewDispatcher.class).ranked(1);
    bind(component.partitionDispatcher()).to(PartitionDispatcher.class).ranked(1);
    bind(component.filesetDispatcher()).to(FilesetDispatcher.class).ranked(1);
    bind(component.topicDispatcher()).to(TopicDispatcher.class).ranked(1);
    bind(component.tagDispatcher()).to(TagDispatcher.class).ranked(1);
    bind(component.policyDispatcher()).to(PolicyDispatcher.class).ranked(1);
    bind(component.credentialOperationDispatcher())
        .to(CredentialOperationDispatcher.class)
        .ranked(1);
    bind(component.secretPropertyOperationDispatcher())
        .to(SecretPropertyOperationDispatcher.class)
        .ranked(1);
    bind(component.secretManager().getRegistry()).to(SecretProviderRegistry.class).ranked(1);
    bind(component.modelDispatcher()).to(ModelDispatcher.class).ranked(1);
    bind(component.functionDispatcher()).to(FunctionDispatcher.class).ranked(1);
    bind(component.semanticModelDispatcher()).to(SemanticModelDispatcher.class).ranked(1);
    bind(lineageDispatcher).to(LineageDispatcher.class).ranked(1);
    bind(component.jobOperationDispatcher()).to(JobOperationDispatcher.class).ranked(1);
    bind(component.statisticDispatcher()).to(StatisticDispatcher.class).ranked(1);
    bindHostServices();
  }

  /**
   * Binds the host services the REST resources and filters inject besides the dispatchers. The
   * access-control services exist only when authorization is enabled, so they are bound only then;
   * a resource that runs without them injects them as {@code @Optional}.
   */
  private void bindHostServices() {
    bind(component.config()).to(Config.class).ranked(1);
    bind(component.eventBus()).to(EventBus.class).ranked(1);
    bind(component.idGenerator()).to(IdGenerator.class).ranked(1);
    bind(component.entityStore()).to(EntityStore.class).ranked(1);
    // Advertised only as CatalogAccess, so no resource can inject the catalog manager under it.
    addActiveDescriptor(ContractBinding.only(component.catalogAccess(), CatalogAccess.class));
    bind(component.auxiliaryServiceManager()).to(AuxiliaryServiceManager.class).ranked(1);
    bind(component.internalTableDispatcher())
        .to(TableDispatcher.class)
        .qualifiedBy(InternalQualifier.INSTANCE)
        .ranked(InternalQualifier.RANK);
    bind(component.internalViewDispatcher())
        .to(ViewDispatcher.class)
        .qualifiedBy(InternalQualifier.INSTANCE)
        .ranked(InternalQualifier.RANK);
    bind(new MetadataAccessChecker(
            component.config(),
            component::gravitinoAuthorizer,
            component::entityStore,
            component::internalSchemaDispatcher))
        .to(MetadataAccessChecker.class)
        .ranked(1);
    bind(component.metadataObjectDispatchers()).to(MetadataObjectDispatchers.class).ranked(1);
    bind(component.authorizationServices()).to(AuthorizationServices.class).ranked(1);
    bind(new MetadataIdResolver(component::catalogAccess, component::entityStore))
        .to(MetadataIdResolver.class)
        .ranked(1);
    if (enableAuthorization) {
      bind(component.accessControlDispatcher()).to(AccessControlDispatcher.class).ranked(1);
      bind(component.ownerDispatcher()).to(OwnerDispatcher.class).ranked(1);
      bind(component.internalOwnerDispatcher())
          .to(OwnerDispatcher.class)
          .qualifiedBy(InternalQualifier.INSTANCE)
          .ranked(InternalQualifier.RANK);
      bind(component.bulkManager()).to(BulkManager.class).ranked(1);
    }
  }
}
