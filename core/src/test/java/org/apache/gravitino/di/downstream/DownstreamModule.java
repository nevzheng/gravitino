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
package org.apache.gravitino.di.downstream;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
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
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.di.Internal;
import org.apache.gravitino.di.Layer;
import org.apache.gravitino.di.LayerKey;
import org.apache.gravitino.di.Public;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.tag.TagDispatcher;

/**
 * The module of {@link DownstreamComponent}: one tagging decorator at {@link Layer#EXTENSION} for
 * every chain, two more inside the public table chain and one at the hook position of the internal
 * schema chain, which has no hook layer, to show where each layer sits.
 */
@Module
abstract class DownstreamModule {

  private DownstreamModule() {}

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<MetalakeDispatcher> internalMetalakeTag() {
    return Tagging.decorator(MetalakeDispatcher.class, "@Internal MetalakeDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<MetalakeDispatcher> metalakeTag() {
    return Tagging.decorator(MetalakeDispatcher.class, "@Public MetalakeDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<CatalogDispatcher> internalCatalogTag() {
    return Tagging.decorator(CatalogDispatcher.class, "@Internal CatalogDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<CatalogDispatcher> catalogTag() {
    return Tagging.decorator(CatalogDispatcher.class, "@Public CatalogDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<SchemaDispatcher> internalSchemaTag() {
    return Tagging.decorator(SchemaDispatcher.class, "@Internal SchemaDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<SchemaDispatcher> schemaTag() {
    return Tagging.decorator(SchemaDispatcher.class, "@Public SchemaDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<TableDispatcher> internalTableTag() {
    return Tagging.decorator(TableDispatcher.class, "@Internal TableDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<TableDispatcher> tableTag() {
    return Tagging.decorator(TableDispatcher.class, "@Public TableDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<PartitionDispatcher> internalPartitionTag() {
    return Tagging.decorator(PartitionDispatcher.class, "@Internal PartitionDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<PartitionDispatcher> partitionTag() {
    return Tagging.decorator(PartitionDispatcher.class, "@Public PartitionDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<ViewDispatcher> internalViewTag() {
    return Tagging.decorator(ViewDispatcher.class, "@Internal ViewDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<ViewDispatcher> viewTag() {
    return Tagging.decorator(ViewDispatcher.class, "@Public ViewDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<FilesetDispatcher> internalFilesetTag() {
    return Tagging.decorator(FilesetDispatcher.class, "@Internal FilesetDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<FilesetDispatcher> filesetTag() {
    return Tagging.decorator(FilesetDispatcher.class, "@Public FilesetDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<TopicDispatcher> internalTopicTag() {
    return Tagging.decorator(TopicDispatcher.class, "@Internal TopicDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<TopicDispatcher> topicTag() {
    return Tagging.decorator(TopicDispatcher.class, "@Public TopicDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<ModelDispatcher> internalModelTag() {
    return Tagging.decorator(ModelDispatcher.class, "@Internal ModelDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<ModelDispatcher> modelTag() {
    return Tagging.decorator(ModelDispatcher.class, "@Public ModelDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<FunctionDispatcher> internalFunctionTag() {
    return Tagging.decorator(FunctionDispatcher.class, "@Internal FunctionDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<FunctionDispatcher> functionTag() {
    return Tagging.decorator(FunctionDispatcher.class, "@Public FunctionDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<SemanticModelDispatcher> internalSemanticModelTag() {
    return Tagging.decorator(SemanticModelDispatcher.class, "@Internal SemanticModelDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<SemanticModelDispatcher> semanticModelTag() {
    return Tagging.decorator(SemanticModelDispatcher.class, "@Public SemanticModelDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<TagDispatcher> internalTagTag() {
    return Tagging.decorator(TagDispatcher.class, "@Internal TagDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<TagDispatcher> tagTag() {
    return Tagging.decorator(TagDispatcher.class, "@Public TagDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<PolicyDispatcher> internalPolicyTag() {
    return Tagging.decorator(PolicyDispatcher.class, "@Internal PolicyDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<PolicyDispatcher> policyTag() {
    return Tagging.decorator(PolicyDispatcher.class, "@Public PolicyDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<OwnerDispatcher> internalOwnerTag() {
    return Tagging.decorator(OwnerDispatcher.class, "@Internal OwnerDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<OwnerDispatcher> ownerTag() {
    return Tagging.decorator(OwnerDispatcher.class, "@Public OwnerDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<StatisticDispatcher> internalStatisticTag() {
    return Tagging.decorator(StatisticDispatcher.class, "@Internal StatisticDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<StatisticDispatcher> statisticTag() {
    return Tagging.decorator(StatisticDispatcher.class, "@Public StatisticDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<AccessControlDispatcher> internalAccessControlTag() {
    return Tagging.decorator(AccessControlDispatcher.class, "@Internal AccessControlDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<AccessControlDispatcher> accessControlTag() {
    return Tagging.decorator(AccessControlDispatcher.class, "@Public AccessControlDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Internal
  static DispatcherDecorator<JobOperationDispatcher> internalJobOperationTag() {
    return Tagging.decorator(JobOperationDispatcher.class, "@Internal JobOperationDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EXTENSION)
  @Public
  static DispatcherDecorator<JobOperationDispatcher> jobOperationTag() {
    return Tagging.decorator(JobOperationDispatcher.class, "@Public JobOperationDispatcher");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.EVENT)
  @Public
  static DispatcherDecorator<TableDispatcher> overEventTableTag() {
    return Tagging.decorator(TableDispatcher.class, "over event");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.OPERATION)
  @Public
  static DispatcherDecorator<TableDispatcher> overOperationTableTag() {
    return Tagging.decorator(TableDispatcher.class, "over operation");
  }

  @Provides
  @IntoMap
  @LayerKey(Layer.HOOK)
  @Internal
  static DispatcherDecorator<SchemaDispatcher> hookPositionSchemaTag() {
    return Tagging.decorator(SchemaDispatcher.class, "hook position");
  }
}
