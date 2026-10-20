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

import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.audit.AuditLogManager;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;

/**
 * A graph with the production modules and fake leaves: {@link FakeLeavesModule} replaces the
 * storage and event modules and the legacy bridge, so module tests run against mocks.
 */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      LockModule.class,
      CatalogModule.class,
      SchemaTableModule.class,
      FilesetTopicModelModule.class,
      GovernanceModule.class,
      AuthorizationModule.class,
      JobModule.class,
      FakeLeavesModule.class
    })
interface ModuleTestComponent extends GravitinoComponent {

  KmsClientRegistry kmsClientRegistry();

  EventListenerManager eventListenerManager();

  AuditLogManager auditLogManager();

  MetalakeManager metalakeManager();

  @Internal
  MetalakeDispatcher internalMetalakeDispatcher();

  @Public
  MetalakeDispatcher metalakeDispatcher();

  @Internal
  CatalogDispatcher internalCatalogDispatcher();

  @Public
  CatalogDispatcher catalogDispatcher();

  CredentialOperationDispatcher credentialOperationDispatcher();

  SecretPropertyOperationDispatcher secretPropertyOperationDispatcher();

  @Internal
  FilesetDispatcher internalFilesetDispatcher();

  @Public
  FilesetDispatcher filesetDispatcher();

  @Internal
  TopicDispatcher internalTopicDispatcher();

  @Public
  TopicDispatcher topicDispatcher();

  @Internal
  ModelDispatcher internalModelDispatcher();

  @Public
  ModelDispatcher modelDispatcher();

  @Internal
  FunctionDispatcher internalFunctionDispatcher();

  @Public
  FunctionDispatcher functionDispatcher();

  @Internal
  SemanticModelDispatcher internalSemanticModelDispatcher();

  @Public
  SemanticModelDispatcher semanticModelDispatcher();

  @Internal
  JobOperationDispatcher internalJobOperationDispatcher();

  @Public
  JobOperationDispatcher jobOperationDispatcher();

  BuiltInJobTemplateEventListener builtInJobTemplateEventListener();

  /**
   * Builds a test graph.
   *
   * @param config the configuration
   * @param leaves the fake leaves
   * @return the graph
   */
  static ModuleTestComponent create(Config config, FakeLeaves leaves) {
    return DaggerModuleTestComponent.factory().create(config, leaves);
  }

  /** Creates {@link ModuleTestComponent} instances. */
  @Component.Factory
  interface Factory {
    ModuleTestComponent create(@BindsInstance Config config, @BindsInstance FakeLeaves leaves);
  }
}
