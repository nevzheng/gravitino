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
import dagger.multibindings.IntoSet;
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
import org.apache.gravitino.di.Public;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.apache.gravitino.tag.TagDispatcher;

/**
 * The module of {@link DownstreamComponent}: one tagging decorator for every chain, two more around
 * the public table chain to show the order.
 */
@Module
abstract class DownstreamModule {

  private DownstreamModule() {}

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<MetalakeDispatcher> internalMetalakeTag() {
    return Tagging.decorator(MetalakeDispatcher.class, 0, "@Internal MetalakeDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<MetalakeDispatcher> metalakeTag() {
    return Tagging.decorator(MetalakeDispatcher.class, 0, "@Public MetalakeDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<CatalogDispatcher> internalCatalogTag() {
    return Tagging.decorator(CatalogDispatcher.class, 0, "@Internal CatalogDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<CatalogDispatcher> catalogTag() {
    return Tagging.decorator(CatalogDispatcher.class, 0, "@Public CatalogDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<SchemaDispatcher> internalSchemaTag() {
    return Tagging.decorator(SchemaDispatcher.class, 0, "@Internal SchemaDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<SchemaDispatcher> schemaTag() {
    return Tagging.decorator(SchemaDispatcher.class, 0, "@Public SchemaDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<TableDispatcher> internalTableTag() {
    return Tagging.decorator(TableDispatcher.class, 0, "@Internal TableDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<TableDispatcher> tableTag() {
    return Tagging.decorator(TableDispatcher.class, 0, "@Public TableDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<PartitionDispatcher> internalPartitionTag() {
    return Tagging.decorator(PartitionDispatcher.class, 0, "@Internal PartitionDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<PartitionDispatcher> partitionTag() {
    return Tagging.decorator(PartitionDispatcher.class, 0, "@Public PartitionDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<ViewDispatcher> internalViewTag() {
    return Tagging.decorator(ViewDispatcher.class, 0, "@Internal ViewDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<ViewDispatcher> viewTag() {
    return Tagging.decorator(ViewDispatcher.class, 0, "@Public ViewDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<FilesetDispatcher> internalFilesetTag() {
    return Tagging.decorator(FilesetDispatcher.class, 0, "@Internal FilesetDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<FilesetDispatcher> filesetTag() {
    return Tagging.decorator(FilesetDispatcher.class, 0, "@Public FilesetDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<TopicDispatcher> internalTopicTag() {
    return Tagging.decorator(TopicDispatcher.class, 0, "@Internal TopicDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<TopicDispatcher> topicTag() {
    return Tagging.decorator(TopicDispatcher.class, 0, "@Public TopicDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<ModelDispatcher> internalModelTag() {
    return Tagging.decorator(ModelDispatcher.class, 0, "@Internal ModelDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<ModelDispatcher> modelTag() {
    return Tagging.decorator(ModelDispatcher.class, 0, "@Public ModelDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<FunctionDispatcher> internalFunctionTag() {
    return Tagging.decorator(FunctionDispatcher.class, 0, "@Internal FunctionDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<FunctionDispatcher> functionTag() {
    return Tagging.decorator(FunctionDispatcher.class, 0, "@Public FunctionDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<SemanticModelDispatcher> internalSemanticModelTag() {
    return Tagging.decorator(SemanticModelDispatcher.class, 0, "@Internal SemanticModelDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<SemanticModelDispatcher> semanticModelTag() {
    return Tagging.decorator(SemanticModelDispatcher.class, 0, "@Public SemanticModelDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<TagDispatcher> internalTagTag() {
    return Tagging.decorator(TagDispatcher.class, 0, "@Internal TagDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<TagDispatcher> tagTag() {
    return Tagging.decorator(TagDispatcher.class, 0, "@Public TagDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<PolicyDispatcher> internalPolicyTag() {
    return Tagging.decorator(PolicyDispatcher.class, 0, "@Internal PolicyDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<PolicyDispatcher> policyTag() {
    return Tagging.decorator(PolicyDispatcher.class, 0, "@Public PolicyDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<OwnerDispatcher> internalOwnerTag() {
    return Tagging.decorator(OwnerDispatcher.class, 0, "@Internal OwnerDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<OwnerDispatcher> ownerTag() {
    return Tagging.decorator(OwnerDispatcher.class, 0, "@Public OwnerDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<StatisticDispatcher> internalStatisticTag() {
    return Tagging.decorator(StatisticDispatcher.class, 0, "@Internal StatisticDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<StatisticDispatcher> statisticTag() {
    return Tagging.decorator(StatisticDispatcher.class, 0, "@Public StatisticDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<AccessControlDispatcher> internalAccessControlTag() {
    return Tagging.decorator(AccessControlDispatcher.class, 0, "@Internal AccessControlDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<AccessControlDispatcher> accessControlTag() {
    return Tagging.decorator(AccessControlDispatcher.class, 0, "@Public AccessControlDispatcher");
  }

  @Provides
  @IntoSet
  @Internal
  static DispatcherDecorator<JobOperationDispatcher> internalJobOperationTag() {
    return Tagging.decorator(JobOperationDispatcher.class, 0, "@Internal JobOperationDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<JobOperationDispatcher> jobOperationTag() {
    return Tagging.decorator(JobOperationDispatcher.class, 0, "@Public JobOperationDispatcher");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<TableDispatcher> outerTableTag() {
    return Tagging.decorator(TableDispatcher.class, 10, "outer");
  }

  @Provides
  @IntoSet
  @Public
  static DispatcherDecorator<TableDispatcher> innerTableTag() {
    return Tagging.decorator(TableDispatcher.class, -10, "inner");
  }
}
