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
import org.apache.gravitino.GravitinoEnv;
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
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.secret.SecretProviderRegistry;
import org.apache.gravitino.server.web.filter.GravitinoInterceptionService;
import org.apache.gravitino.server.web.rest.SemanticModelSourceValidator;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/**
 * Binds the dispatchers the REST resources inject into HK2, read from the {@link GravitinoEnv}
 * getters of the environment the server was given.
 *
 * <p>The getters are virtual on purpose: a server built with a subclass of {@link GravitinoEnv}
 * binds the dispatchers that subclass returns. Binding them from the provision methods of {@code
 * GravitinoComponent} instead would bypass those overrides, so it waits until the component offers
 * decorator seams that such a subclass can use instead (see the dependency injection design doc,
 * sections 8 and 10).
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
  }
}
