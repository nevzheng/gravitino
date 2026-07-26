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
package org.apache.gravitino;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.hook.PolicyHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.JobEventDispatcher;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.listener.TagEventDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metadata.DispatcherMetadataObjectExistenceChecker;
import org.apache.gravitino.metadata.MetadataObjectExistenceChecker;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.tag.TagManager;

/** Package-private composition boundary for metadata governance services. */
final class GovernanceServices {

  private final GovernanceComponent component;

  private GovernanceServices(GovernanceComponent component) {
    this.component = component;
  }

  static GovernanceServices create(
      Config config,
      EntityStore entityStore,
      IdGenerator idGenerator,
      EventBus eventBus,
      LockManager lockManager,
      MetalakeDispatcher metalakeDispatcher,
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      FunctionDispatcher functionDispatcher,
      ViewDispatcher viewDispatcher,
      @Nullable AccessControlDispatcher accessControlDispatcher,
      @Nullable OwnerDispatcher ownerDispatcher) {
    return new GovernanceServices(
        DaggerGovernanceServices_GovernanceComponent.factory()
            .create(
                config,
                entityStore,
                idGenerator,
                eventBus,
                lockManager,
                metalakeDispatcher,
                catalogDispatcher,
                schemaDispatcher,
                tableDispatcher,
                filesetDispatcher,
                topicDispatcher,
                modelDispatcher,
                functionDispatcher,
                viewDispatcher,
                accessControlDispatcher,
                ownerDispatcher));
  }

  TagDispatcher tagDispatcher() {
    return component.tagDispatcher();
  }

  PolicyDispatcher policyDispatcher() {
    return component.policyDispatcher();
  }

  JobOperationDispatcher jobOperationDispatcher() {
    return component.jobOperationDispatcher();
  }

  BuiltInJobTemplateEventListener builtInJobTemplateEventListener() {
    return component.builtInJobTemplateEventListener();
  }

  MetadataObjectExistenceChecker metadataObjectExistenceChecker() {
    return component.metadataObjectExistenceChecker();
  }

  JobManager jobManager() {
    return component.jobManager();
  }

  @Singleton
  @Component(modules = {MetadataModule.class, TagModule.class, PolicyModule.class, JobModule.class})
  interface GovernanceComponent {

    TagDispatcher tagDispatcher();

    PolicyDispatcher policyDispatcher();

    JobOperationDispatcher jobOperationDispatcher();

    BuiltInJobTemplateEventListener builtInJobTemplateEventListener();

    MetadataObjectExistenceChecker metadataObjectExistenceChecker();

    JobManager jobManager();

    @Component.Factory
    interface Factory {

      GovernanceComponent create(
          @BindsInstance Config config,
          @BindsInstance EntityStore entityStore,
          @BindsInstance IdGenerator idGenerator,
          @BindsInstance EventBus eventBus,
          @BindsInstance LockManager lockManager,
          @BindsInstance MetalakeDispatcher metalakeDispatcher,
          @BindsInstance CatalogDispatcher catalogDispatcher,
          @BindsInstance SchemaDispatcher schemaDispatcher,
          @BindsInstance TableDispatcher tableDispatcher,
          @BindsInstance FilesetDispatcher filesetDispatcher,
          @BindsInstance TopicDispatcher topicDispatcher,
          @BindsInstance ModelDispatcher modelDispatcher,
          @BindsInstance FunctionDispatcher functionDispatcher,
          @BindsInstance ViewDispatcher viewDispatcher,
          @BindsInstance @Nullable AccessControlDispatcher accessControlDispatcher,
          @BindsInstance @Nullable OwnerDispatcher ownerDispatcher);
    }
  }

  @Module
  static final class MetadataModule {

    private MetadataModule() {}

    @Provides
    @Singleton
    static MetadataObjectExistenceChecker provideMetadataObjectExistenceChecker(
        MetalakeDispatcher metalakeDispatcher,
        CatalogDispatcher catalogDispatcher,
        SchemaDispatcher schemaDispatcher,
        TableDispatcher tableDispatcher,
        FilesetDispatcher filesetDispatcher,
        TopicDispatcher topicDispatcher,
        ModelDispatcher modelDispatcher,
        FunctionDispatcher functionDispatcher,
        ViewDispatcher viewDispatcher,
        @Nullable AccessControlDispatcher accessControlDispatcher,
        Provider<TagDispatcher> tagDispatcher,
        Provider<PolicyDispatcher> policyDispatcher,
        Provider<JobOperationDispatcher> jobOperationDispatcher) {
      return new DispatcherMetadataObjectExistenceChecker(
          metalakeDispatcher,
          catalogDispatcher,
          schemaDispatcher,
          tableDispatcher,
          filesetDispatcher,
          topicDispatcher,
          modelDispatcher,
          functionDispatcher,
          viewDispatcher,
          accessControlDispatcher,
          tagDispatcher::get,
          policyDispatcher::get,
          jobOperationDispatcher::get);
    }
  }

  @Module
  static final class TagModule {

    private TagModule() {}

    @Provides
    @Singleton
    static TagManager provideTagManager(
        IdGenerator idGenerator,
        EntityStore entityStore,
        LockManager lockManager,
        MetadataObjectExistenceChecker metadataObjectExistenceChecker) {
      return new TagManager(idGenerator, entityStore, lockManager, metadataObjectExistenceChecker);
    }

    @Provides
    @Singleton
    static TagEventDispatcher provideTagEventDispatcher(EventBus eventBus, TagManager tagManager) {
      return new TagEventDispatcher(eventBus, tagManager);
    }

    @Provides
    @Singleton
    static TagDispatcher provideTagDispatcher(
        TagEventDispatcher eventDispatcher, @Nullable OwnerDispatcher ownerDispatcher) {
      return new TagHookDispatcher(eventDispatcher, ownerDispatcher);
    }
  }

  @Module
  static final class PolicyModule {

    private PolicyModule() {}

    @Provides
    @Singleton
    static PolicyManager providePolicyManager(
        IdGenerator idGenerator,
        EntityStore entityStore,
        LockManager lockManager,
        MetadataObjectExistenceChecker metadataObjectExistenceChecker) {
      return new PolicyManager(
          idGenerator, entityStore, lockManager, metadataObjectExistenceChecker);
    }

    @Provides
    @Singleton
    static PolicyEventDispatcher providePolicyEventDispatcher(
        EventBus eventBus, PolicyManager policyManager) {
      return new PolicyEventDispatcher(eventBus, policyManager);
    }

    @Provides
    @Singleton
    static PolicyDispatcher providePolicyDispatcher(
        PolicyEventDispatcher eventDispatcher, @Nullable OwnerDispatcher ownerDispatcher) {
      return new PolicyHookDispatcher(eventDispatcher, ownerDispatcher);
    }
  }

  @Module
  @SuppressWarnings("CloseableProvides") // GravitinoEnv closes the exported dispatcher chain.
  static final class JobModule {

    private JobModule() {}

    @Provides
    @Singleton
    static JobManager provideJobManager(
        Config config, EntityStore entityStore, IdGenerator idGenerator) {
      return new JobManager(config, entityStore, idGenerator);
    }

    @Provides
    @Singleton
    static JobTemplateValidationDispatcher provideJobTemplateValidationDispatcher(
        JobManager jobManager) {
      return new JobTemplateValidationDispatcher(jobManager);
    }

    @Provides
    @Singleton
    static JobEventDispatcher provideJobEventDispatcher(
        EventBus eventBus, JobTemplateValidationDispatcher validationDispatcher) {
      return new JobEventDispatcher(eventBus, validationDispatcher);
    }

    @Provides
    @Singleton
    static JobOperationDispatcher provideJobOperationDispatcher(
        JobEventDispatcher eventDispatcher, @Nullable OwnerDispatcher ownerDispatcher) {
      return new JobHookDispatcher(eventDispatcher, ownerDispatcher);
    }

    @Provides
    @Singleton
    static BuiltInJobTemplateEventListener provideBuiltInJobTemplateEventListener(
        JobManager jobManager, EntityStore entityStore, IdGenerator idGenerator) {
      return new BuiltInJobTemplateEventListener(jobManager, entityStore, idGenerator);
    }
  }
}
