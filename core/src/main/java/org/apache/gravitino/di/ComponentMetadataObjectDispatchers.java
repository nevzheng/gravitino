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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.MetadataObjectDispatchers;

/**
 * The {@link MetadataObjectDispatchers} of the component: its internal dispatcher chains, each
 * resolved when it is asked for. The access control dispatcher is the one of the component's {@link
 * AuthorizationServices}, null while authorization is disabled.
 *
 * <p>Like {@link ComponentAuthorizationServices}, the providers resolve each chain lazily. See
 * section 5.3 of the design document.
 */
@Singleton
final class ComponentMetadataObjectDispatchers implements MetadataObjectDispatchers {

  private final Provider<MetalakeDispatcher> internalMetalakeDispatcher;
  private final Provider<CatalogDispatcher> internalCatalogDispatcher;
  private final Provider<SchemaDispatcher> internalSchemaDispatcher;
  private final Provider<TableDispatcher> internalTableDispatcher;
  private final Provider<ViewDispatcher> internalViewDispatcher;
  private final Provider<FilesetDispatcher> internalFilesetDispatcher;
  private final Provider<TopicDispatcher> internalTopicDispatcher;
  private final Provider<ModelDispatcher> internalModelDispatcher;
  private final Provider<FunctionDispatcher> internalFunctionDispatcher;
  private final Provider<SemanticModelDispatcher> internalSemanticModelDispatcher;
  private final Provider<TagDispatcher> internalTagDispatcher;
  private final Provider<PolicyDispatcher> internalPolicyDispatcher;
  private final Provider<JobOperationDispatcher> internalJobOperationDispatcher;
  private final Provider<AuthorizationServices> authorizationServices;

  @Inject
  ComponentMetadataObjectDispatchers(
      @Internal Provider<MetalakeDispatcher> internalMetalakeDispatcher,
      @Internal Provider<CatalogDispatcher> internalCatalogDispatcher,
      @Internal Provider<SchemaDispatcher> internalSchemaDispatcher,
      @Internal Provider<TableDispatcher> internalTableDispatcher,
      @Internal Provider<ViewDispatcher> internalViewDispatcher,
      @Internal Provider<FilesetDispatcher> internalFilesetDispatcher,
      @Internal Provider<TopicDispatcher> internalTopicDispatcher,
      @Internal Provider<ModelDispatcher> internalModelDispatcher,
      @Internal Provider<FunctionDispatcher> internalFunctionDispatcher,
      @Internal Provider<SemanticModelDispatcher> internalSemanticModelDispatcher,
      @Internal Provider<TagDispatcher> internalTagDispatcher,
      @Internal Provider<PolicyDispatcher> internalPolicyDispatcher,
      @Internal Provider<JobOperationDispatcher> internalJobOperationDispatcher,
      Provider<AuthorizationServices> authorizationServices) {
    this.internalMetalakeDispatcher = internalMetalakeDispatcher;
    this.internalCatalogDispatcher = internalCatalogDispatcher;
    this.internalSchemaDispatcher = internalSchemaDispatcher;
    this.internalTableDispatcher = internalTableDispatcher;
    this.internalViewDispatcher = internalViewDispatcher;
    this.internalFilesetDispatcher = internalFilesetDispatcher;
    this.internalTopicDispatcher = internalTopicDispatcher;
    this.internalModelDispatcher = internalModelDispatcher;
    this.internalFunctionDispatcher = internalFunctionDispatcher;
    this.internalSemanticModelDispatcher = internalSemanticModelDispatcher;
    this.internalTagDispatcher = internalTagDispatcher;
    this.internalPolicyDispatcher = internalPolicyDispatcher;
    this.internalJobOperationDispatcher = internalJobOperationDispatcher;
    this.authorizationServices = authorizationServices;
  }

  @Override
  public MetalakeDispatcher internalMetalakeDispatcher() {
    return internalMetalakeDispatcher.get();
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return internalCatalogDispatcher.get();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return internalSchemaDispatcher.get();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return internalTableDispatcher.get();
  }

  @Override
  public ViewDispatcher internalViewDispatcher() {
    return internalViewDispatcher.get();
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return internalFilesetDispatcher.get();
  }

  @Override
  public TopicDispatcher internalTopicDispatcher() {
    return internalTopicDispatcher.get();
  }

  @Override
  public ModelDispatcher internalModelDispatcher() {
    return internalModelDispatcher.get();
  }

  @Override
  public FunctionDispatcher internalFunctionDispatcher() {
    return internalFunctionDispatcher.get();
  }

  @Override
  public SemanticModelDispatcher internalSemanticModelDispatcher() {
    return internalSemanticModelDispatcher.get();
  }

  @Override
  public TagDispatcher internalTagDispatcher() {
    return internalTagDispatcher.get();
  }

  @Override
  public PolicyDispatcher internalPolicyDispatcher() {
    return internalPolicyDispatcher.get();
  }

  @Override
  public JobOperationDispatcher internalJobOperationDispatcher() {
    return internalJobOperationDispatcher.get();
  }

  @Override
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return authorizationServices.get().internalAccessControlDispatcher();
  }
}
