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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Checks that Dagger runs in this build next to Lombok and Error Prone, using the features the
 * migration relies on: {@code @Inject} constructors, Lombok-generated constructors, modules,
 * qualifiers, {@code Provider} for cycles, {@code @BindsInstance} and per-component singletons.
 */
public class TestDaggerToolchain {

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface Internal {}

  /** Stands in for a value handed in from outside the graph, like {@code Config}. */
  static final class Settings {
    final String name;

    Settings(String name) {
      this.name = name;
    }
  }

  @Singleton
  static final class Store {
    final Settings settings;

    @Inject
    Store(Settings settings) {
      this.settings = settings;
    }
  }

  /** Lombok writes the {@code @Inject} constructor; Dagger must see it. */
  @Singleton
  @Getter
  @RequiredArgsConstructor(onConstructor_ = @Inject)
  static final class Manager {
    private final Store store;
  }

  interface Dispatcher {
    String name();
  }

  /** Two dispatchers that need each other, like tables and schemas today. */
  static final class TableDispatcher implements Dispatcher {
    final Provider<Dispatcher> schema;

    TableDispatcher(Provider<Dispatcher> schema) {
      this.schema = schema;
    }

    @Override
    public String name() {
      return "table->" + schema.get().name();
    }
  }

  static final class SchemaDispatcher implements Dispatcher {
    final Dispatcher table;

    SchemaDispatcher(Dispatcher table) {
      this.table = table;
    }

    @Override
    public String name() {
      return "schema";
    }
  }

  @Module
  interface DispatcherModule {
    @Provides
    @Singleton
    static Dispatcher table(@Internal Provider<Dispatcher> schema) {
      return new TableDispatcher(schema);
    }

    @Provides
    @Singleton
    @Internal
    static Dispatcher schema(Dispatcher table) {
      return new SchemaDispatcher(table);
    }
  }

  @Singleton
  @Component(modules = DispatcherModule.class)
  interface ToolchainComponent {
    Manager manager();

    Store store();

    Dispatcher table();

    @Internal
    Dispatcher schema();

    @Component.Factory
    interface Factory {
      ToolchainComponent create(@BindsInstance Settings settings);
    }
  }

  @Test
  public void testGraphIsWiredAtCompileTime() {
    ToolchainComponent component =
        DaggerTestDaggerToolchain_ToolchainComponent.factory().create(new Settings("test"));

    assertEquals("test", component.manager().getStore().settings.name);
    assertSame(component.store(), component.manager().getStore());
    assertEquals("table->schema", component.table().name());
    assertSame(component.table(), ((SchemaDispatcher) component.schema()).table);
  }

  @Test
  public void testSingletonsArePerComponent() {
    ToolchainComponent first =
        DaggerTestDaggerToolchain_ToolchainComponent.factory().create(new Settings("a"));
    ToolchainComponent second =
        DaggerTestDaggerToolchain_ToolchainComponent.factory().create(new Settings("b"));

    assertSame(first.store(), first.store());
    assertNotSame(first.store(), second.store());
  }
}
