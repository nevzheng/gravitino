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
package org.apache.gravitino.iceberg.service.dispatcher;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;

/** Compile-time composition boundary for Iceberg REST operation decorators. */
public final class IcebergHookGraph {

  private final IcebergNamespaceOperationDispatcher namespaceDispatcher;
  private final IcebergTableOperationDispatcher tableDispatcher;
  private final IcebergViewOperationDispatcher viewDispatcher;
  private final IcebergNamespaceOperationDispatcher namespaceEventDispatcher;
  private final IcebergTableOperationDispatcher tableEventDispatcher;
  private final IcebergViewOperationDispatcher viewEventDispatcher;

  private IcebergHookGraph(
      IcebergNamespaceOperationDispatcher namespaceDispatcher,
      IcebergTableOperationDispatcher tableDispatcher,
      IcebergViewOperationDispatcher viewDispatcher,
      IcebergNamespaceOperationDispatcher namespaceEventDispatcher,
      IcebergTableOperationDispatcher tableEventDispatcher,
      IcebergViewOperationDispatcher viewEventDispatcher) {
    this.namespaceDispatcher = namespaceDispatcher;
    this.tableDispatcher = tableDispatcher;
    this.viewDispatcher = viewDispatcher;
    this.namespaceEventDispatcher = namespaceEventDispatcher;
    this.tableEventDispatcher = tableEventDispatcher;
    this.viewEventDispatcher = viewEventDispatcher;
  }

  /**
   * Creates the standalone-safe graph whose final dispatchers are the event decorators.
   *
   * @param baseInputs exact inputs available in standalone mode
   * @return the event-only graph
   */
  public static IcebergHookGraph createBase(BaseInputs baseInputs) {
    BaseComponent component = DaggerIcebergHookGraph_BaseComponent.factory().create(baseInputs);
    return new IcebergHookGraph(
        component.namespaceEventDispatcher(),
        component.tableEventDispatcher(),
        component.viewEventDispatcher(),
        component.namespaceEventDispatcher(),
        component.tableEventDispatcher(),
        component.viewEventDispatcher());
  }

  /**
   * Creates the hook-enabled graph for an authorized auxiliary server.
   *
   * <p>This factory deliberately requires non-null auxiliary inputs. Callers must use {@link
   * #createBase(BaseInputs)} when authorization is disabled or the server is standalone.
   *
   * @param baseInputs raw operations and event capability
   * @param auxiliaryInputs metadata capabilities available only in auxiliary mode
   * @return the hook-enabled graph
   */
  public static IcebergHookGraph createAuxiliary(
      BaseInputs baseInputs, AuxiliaryInputs auxiliaryInputs) {
    AuxiliaryComponent component =
        DaggerIcebergHookGraph_AuxiliaryComponent.factory().create(baseInputs, auxiliaryInputs);
    return new IcebergHookGraph(
        component.namespaceHookDispatcher(),
        component.tableHookDispatcher(),
        component.viewHookDispatcher(),
        component.namespaceEventDispatcher(),
        component.tableEventDispatcher(),
        component.viewEventDispatcher());
  }

  /** Returns the final namespace dispatcher. */
  public IcebergNamespaceOperationDispatcher namespaceDispatcher() {
    return namespaceDispatcher;
  }

  /** Returns the final table dispatcher. */
  public IcebergTableOperationDispatcher tableDispatcher() {
    return tableDispatcher;
  }

  /** Returns the final view dispatcher. */
  public IcebergViewOperationDispatcher viewDispatcher() {
    return viewDispatcher;
  }

  IcebergNamespaceOperationDispatcher namespaceEventDispatcher() {
    return namespaceEventDispatcher;
  }

  IcebergTableOperationDispatcher tableEventDispatcher() {
    return tableEventDispatcher;
  }

  IcebergViewOperationDispatcher viewEventDispatcher() {
    return viewEventDispatcher;
  }

  static AuxiliaryInputs legacyAuxiliaryInputs() {
    GravitinoEnv environment = GravitinoEnv.getInstance();
    return AuxiliaryInputs.createLegacy(
        environment.entityStore(),
        environment.lockManager(),
        environment.internalSchemaDispatcher(),
        environment.internalTableDispatcher(),
        environment.internalViewDispatcher(),
        environment.internalOwnerDispatcher(),
        HierarchicalSchemaUtil.schemaSeparator());
  }

  /** Exact leaf operations and event capability shared by both graph shapes. */
  public static final class BaseInputs {

    private final IcebergNamespaceOperationDispatcher namespaceOperations;
    private final IcebergTableOperationDispatcher tableOperations;
    private final IcebergViewOperationDispatcher viewOperations;
    private final EventBus eventBus;
    private final String metalakeName;

    private BaseInputs(
        IcebergNamespaceOperationDispatcher namespaceOperations,
        IcebergTableOperationDispatcher tableOperations,
        IcebergViewOperationDispatcher viewOperations,
        EventBus eventBus,
        String metalakeName) {
      this.namespaceOperations = Objects.requireNonNull(namespaceOperations, "namespaceOperations");
      this.tableOperations = Objects.requireNonNull(tableOperations, "tableOperations");
      this.viewOperations = Objects.requireNonNull(viewOperations, "viewOperations");
      this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
      this.metalakeName = Objects.requireNonNull(metalakeName, "metalakeName");
    }

    /**
     * Creates standalone-safe graph inputs.
     *
     * @param namespaceOperations raw namespace operations
     * @param tableOperations raw table operations
     * @param viewOperations raw view operations
     * @param eventBus configured event bus
     * @param metalakeName metalake recorded on emitted events
     * @return immutable base inputs
     */
    public static BaseInputs create(
        IcebergNamespaceOperationDispatcher namespaceOperations,
        IcebergTableOperationDispatcher tableOperations,
        IcebergViewOperationDispatcher viewOperations,
        EventBus eventBus,
        String metalakeName) {
      return new BaseInputs(
          namespaceOperations, tableOperations, viewOperations, eventBus, metalakeName);
    }
  }

  /** Exact metadata capabilities required by the auxiliary hook layer. */
  public static final class AuxiliaryInputs {

    final EntityStore entityStore;
    final LockManager lockManager;
    final SchemaDispatcher schemaDispatcher;
    final TableDispatcher tableDispatcher;
    final ViewDispatcher viewDispatcher;
    final OwnerDispatcher ownerDispatcher;
    final String schemaSeparator;

    private AuxiliaryInputs(
        EntityStore entityStore,
        LockManager lockManager,
        SchemaDispatcher schemaDispatcher,
        TableDispatcher tableDispatcher,
        ViewDispatcher viewDispatcher,
        OwnerDispatcher ownerDispatcher,
        String schemaSeparator,
        boolean validate) {
      this.entityStore = requireIfRequested(entityStore, "entityStore", validate);
      this.lockManager = requireIfRequested(lockManager, "lockManager", validate);
      this.schemaDispatcher = requireIfRequested(schemaDispatcher, "schemaDispatcher", validate);
      this.tableDispatcher = requireIfRequested(tableDispatcher, "tableDispatcher", validate);
      this.viewDispatcher = requireIfRequested(viewDispatcher, "viewDispatcher", validate);
      this.ownerDispatcher = requireIfRequested(ownerDispatcher, "ownerDispatcher", validate);
      this.schemaSeparator = requireIfRequested(schemaSeparator, "schemaSeparator", validate);
    }

    /**
     * Creates the authorized auxiliary hook inputs.
     *
     * @param entityStore metadata entity store
     * @param lockManager metadata tree-lock manager
     * @param schemaDispatcher internal schema dispatcher
     * @param tableDispatcher internal table dispatcher
     * @param viewDispatcher internal view dispatcher
     * @param ownerDispatcher internal owner dispatcher
     * @param schemaSeparator configured hierarchical schema separator
     * @return immutable auxiliary inputs
     */
    public static AuxiliaryInputs create(
        EntityStore entityStore,
        LockManager lockManager,
        SchemaDispatcher schemaDispatcher,
        TableDispatcher tableDispatcher,
        ViewDispatcher viewDispatcher,
        OwnerDispatcher ownerDispatcher,
        String schemaSeparator) {
      return new AuxiliaryInputs(
          entityStore,
          lockManager,
          schemaDispatcher,
          tableDispatcher,
          viewDispatcher,
          ownerDispatcher,
          schemaSeparator,
          true);
    }

    private static AuxiliaryInputs createLegacy(
        EntityStore entityStore,
        LockManager lockManager,
        SchemaDispatcher schemaDispatcher,
        TableDispatcher tableDispatcher,
        ViewDispatcher viewDispatcher,
        OwnerDispatcher ownerDispatcher,
        String schemaSeparator) {
      return new AuxiliaryInputs(
          entityStore,
          lockManager,
          schemaDispatcher,
          tableDispatcher,
          viewDispatcher,
          ownerDispatcher,
          schemaSeparator,
          false);
    }

    private static <T> T requireIfRequested(T value, String name, boolean validate) {
      return validate ? Objects.requireNonNull(value, name) : value;
    }
  }

  @Singleton
  @Component(modules = EventModule.class)
  interface BaseComponent {

    @EventLayer
    IcebergNamespaceOperationDispatcher namespaceEventDispatcher();

    @EventLayer
    IcebergTableOperationDispatcher tableEventDispatcher();

    @EventLayer
    IcebergViewOperationDispatcher viewEventDispatcher();

    @Component.Factory
    interface Factory {

      BaseComponent create(@BindsInstance BaseInputs baseInputs);
    }
  }

  @Singleton
  @Component(modules = {EventModule.class, AuxiliaryModule.class})
  interface AuxiliaryComponent {

    @HookLayer
    IcebergNamespaceOperationDispatcher namespaceHookDispatcher();

    @HookLayer
    IcebergTableOperationDispatcher tableHookDispatcher();

    @HookLayer
    IcebergViewOperationDispatcher viewHookDispatcher();

    @EventLayer
    IcebergNamespaceOperationDispatcher namespaceEventDispatcher();

    @EventLayer
    IcebergTableOperationDispatcher tableEventDispatcher();

    @EventLayer
    IcebergViewOperationDispatcher viewEventDispatcher();

    @Component.Factory
    interface Factory {

      AuxiliaryComponent create(
          @BindsInstance BaseInputs baseInputs, @BindsInstance AuxiliaryInputs auxiliaryInputs);
    }
  }

  @Module
  static final class EventModule {

    private EventModule() {}

    @Provides
    @Singleton
    @EventLayer
    static IcebergNamespaceOperationDispatcher provideNamespaceEventDispatcher(BaseInputs inputs) {
      return new IcebergNamespaceEventDispatcher(
          inputs.namespaceOperations, inputs.eventBus, inputs.metalakeName);
    }

    @Provides
    @Singleton
    @EventLayer
    static IcebergTableOperationDispatcher provideTableEventDispatcher(BaseInputs inputs) {
      return new IcebergTableEventDispatcher(
          inputs.tableOperations, inputs.eventBus, inputs.metalakeName);
    }

    @Provides
    @Singleton
    @EventLayer
    static IcebergViewOperationDispatcher provideViewEventDispatcher(BaseInputs inputs) {
      return new IcebergViewEventDispatcher(
          inputs.viewOperations, inputs.eventBus, inputs.metalakeName);
    }
  }

  @Module
  static final class AuxiliaryModule {

    private AuxiliaryModule() {}

    @Provides
    @Singleton
    static IcebergOrphanSchemaCleanup provideOrphanSchemaCleanup(AuxiliaryInputs inputs) {
      return new IcebergOrphanSchemaCleanup(inputs.entityStore, inputs.schemaSeparator);
    }

    @Provides
    @Singleton
    @HookLayer
    static IcebergNamespaceOperationDispatcher provideNamespaceHookDispatcher(
        @EventLayer IcebergNamespaceOperationDispatcher eventDispatcher,
        AuxiliaryInputs inputs,
        BaseInputs baseInputs,
        IcebergOrphanSchemaCleanup orphanSchemaCleanup) {
      return new IcebergNamespaceHookDispatcher(
          eventDispatcher,
          baseInputs.metalakeName,
          inputs.entityStore,
          inputs.lockManager,
          inputs.schemaDispatcher,
          inputs.tableDispatcher,
          inputs.viewDispatcher,
          inputs.ownerDispatcher,
          inputs.schemaSeparator,
          orphanSchemaCleanup);
    }

    @Provides
    @Singleton
    @HookLayer
    static IcebergTableOperationDispatcher provideTableHookDispatcher(
        @EventLayer IcebergTableOperationDispatcher eventDispatcher,
        AuxiliaryInputs inputs,
        BaseInputs baseInputs,
        IcebergOrphanSchemaCleanup orphanSchemaCleanup) {
      return new IcebergTableHookDispatcher(
          eventDispatcher,
          baseInputs.namespaceOperations,
          baseInputs.metalakeName,
          inputs.entityStore,
          inputs.tableDispatcher,
          inputs.ownerDispatcher,
          inputs.schemaSeparator,
          orphanSchemaCleanup);
    }

    @Provides
    @Singleton
    @HookLayer
    static IcebergViewOperationDispatcher provideViewHookDispatcher(
        @EventLayer IcebergViewOperationDispatcher eventDispatcher,
        AuxiliaryInputs inputs,
        BaseInputs baseInputs,
        IcebergOrphanSchemaCleanup orphanSchemaCleanup) {
      return new IcebergViewHookDispatcher(
          eventDispatcher,
          baseInputs.namespaceOperations,
          baseInputs.metalakeName,
          inputs.entityStore,
          inputs.viewDispatcher,
          inputs.ownerDispatcher,
          inputs.schemaSeparator,
          orphanSchemaCleanup);
    }
  }

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface EventLayer {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface HookLayer {}
}
