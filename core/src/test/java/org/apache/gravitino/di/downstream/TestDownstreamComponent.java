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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.NoopGravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerEventManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.catalog.CatalogNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.FunctionNormalizeDispatcher;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.catalog.PartitionNormalizeDispatcher;
import org.apache.gravitino.catalog.SchemaNormalizeDispatcher;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.catalog.SemanticModelNormalizeDispatcher;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.catalog.TopicNormalizeDispatcher;
import org.apache.gravitino.catalog.ViewNormalizeDispatcher;
import org.apache.gravitino.di.GravitinoComponent;
import org.apache.gravitino.hook.TableHookDispatcher;
import org.apache.gravitino.hook.TagHookDispatcher;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.apache.gravitino.listener.CatalogEventDispatcher;
import org.apache.gravitino.listener.FilesetEventDispatcher;
import org.apache.gravitino.listener.FunctionEventDispatcher;
import org.apache.gravitino.listener.JobEventDispatcher;
import org.apache.gravitino.listener.MetalakeEventDispatcher;
import org.apache.gravitino.listener.ModelEventDispatcher;
import org.apache.gravitino.listener.PartitionEventDispatcher;
import org.apache.gravitino.listener.PolicyEventDispatcher;
import org.apache.gravitino.listener.SchemaEventDispatcher;
import org.apache.gravitino.listener.SemanticModelEventDispatcher;
import org.apache.gravitino.listener.StatisticEventDispatcher;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.TagEventDispatcher;
import org.apache.gravitino.listener.TopicEventDispatcher;
import org.apache.gravitino.listener.ViewEventDispatcher;
import org.apache.gravitino.metalake.MetalakeNormalizeDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.stats.StatisticManager;
import org.apache.gravitino.stats.storage.MemoryPartitionStatsStorageFactory;
import org.apache.gravitino.tag.TagManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds a downstream root component from the upstream modules and a module of decorators, and
 * checks that every chain, and every upstream object that consumes a chain, picks them up.
 */
public class TestDownstreamComponent {

  @TempDir Path stagingDir;

  private DownstreamComponent component;

  @BeforeEach
  public void setUp() {
    Config config = new Config(false) {};
    config.set(Configs.ENTITY_STORE, RelationalInMemoryEntityStore.class.getName());
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    config.set(Configs.SERVICE_ADMINS, Collections.singletonList("admin"));
    config.set(
        Configs.PARTITION_STATS_STORAGE_FACTORY_CLASS,
        MemoryPartitionStatsStorageFactory.class.getCanonicalName());
    config.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    component =
        DaggerDownstreamComponent.factory().create(config, () -> NoopGravitinoAuthorizer.INSTANCE);
  }

  @AfterEach
  public void tearDown() throws Exception {
    component.lifecycle().close();
  }

  @Test
  public void testEveryChainIsWrappedInItsDecorators() {
    for (Row row : rows(component)) {
      Object chain = row.chain.get();
      Object upstream = untag(chain, row.name);
      assertTrue(labels(chain).contains(row.name), row.name + " misses its decorator");
      assertSame(chain, row.chain.get(), row.name);
      assertInstanceOf(row.upstream, upstream, row.name);
    }
  }

  @Test
  public void testDecoratorsWrapInAscendingOrder() {
    Object chain = component.tableDispatcher();

    assertEquals("outer", Tagging.tag(chain).label);
    Object next = Tagging.tag(chain).delegate;
    assertEquals("@Public TableDispatcher", Tagging.tag(next).label);
    next = Tagging.tag(next).delegate;
    assertEquals("inner", Tagging.tag(next).label);
    assertInstanceOf(TableEventDispatcher.class, Tagging.tag(next).delegate);
  }

  @Test
  public void testUpstreamConsumersOfAChainGetTheDecoratedChain() throws Exception {
    // The public table chain: event, normalize, hook, then operation.
    Object hook =
        field(field(untag(component.tableDispatcher(), "tables"), "dispatcher"), "dispatcher");
    assertInstanceOf(TableHookDispatcher.class, hook);
    Supplier<?> owners = (Supplier<?>) field(hook, "ownerDispatcher");
    assertSame(component.internalOwnerDispatcher(), owners.get());
    Object operations = field(hook, "dispatcher");
    assertInstanceOf(TableOperationDispatcher.class, operations);
    Supplier<?> schemas = (Supplier<?>) field(operations, "schemaDispatcherSupplier");
    assertSame(component.internalSchemaDispatcher(), schemas.get());

    // The component does not expose the schema operation dispatcher; reach it through the chain.
    Object schemaOperations =
        field(untag(component.internalSchemaDispatcher(), "schemas"), "dispatcher");
    assertInstanceOf(SchemaOperationDispatcher.class, schemaOperations);
    assertSame(component.internalFilesetDispatcher(), field(schemaOperations, "filesetDispatcher"));

    FutureGrantManager futureGrants = component.futureGrantManager();
    assertSame(component.internalOwnerDispatcher(), field(futureGrants, "ownerDispatcher"));
  }

  @Test
  public void testPublicChainsWrapTheUndecoratedSharedLayers() throws Exception {
    Object tagHook = field(untag(component.tagDispatcher(), "tags"), "dispatcher");
    assertInstanceOf(TagHookDispatcher.class, tagHook);
    assertSame(untag(component.internalTagDispatcher(), "tags"), field(tagHook, "dispatcher"));

    Object partitions = untag(component.partitionDispatcher(), "partitions");
    assertSame(
        untag(component.internalPartitionDispatcher(), "partitions"),
        field(partitions, "dispatcher"));
  }

  @Test
  public void testIsAGravitinoComponent() {
    GravitinoComponent upstream = component;
    assertNotNull(upstream.lockManager());
    assertNull(Tagging.tag(upstream.catalogManager()));
  }

  /** Strips the tagging proxies off a chain and returns the upstream chain inside them. */
  private static Object untag(Object chain, String name) {
    assertNotNull(Tagging.tag(chain), name + " has no decorator");
    Object current = chain;
    while (Tagging.tag(current) != null) {
      current = Tagging.tag(current).delegate;
    }
    assertNotNull(current, name);
    return current;
  }

  /** Returns the labels of the tagging proxies around a chain, outermost first. */
  private static List<String> labels(Object chain) {
    List<String> labels = new ArrayList<>();
    for (Object current = chain;
        Tagging.tag(current) != null;
        current = Tagging.tag(current).delegate) {
      labels.add(Tagging.tag(current).label);
    }
    return labels;
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException e) {
        // Look in the superclass.
      }
    }
    throw new NoSuchFieldException(target.getClass().getName() + "." + name);
  }

  private static List<Row> rows(DownstreamComponent c) {
    return List.of(
        row("@Public MetalakeDispatcher", c::metalakeDispatcher, MetalakeEventDispatcher.class),
        row(
            "@Internal MetalakeDispatcher",
            c::internalMetalakeDispatcher,
            MetalakeNormalizeDispatcher.class),
        row("@Public CatalogDispatcher", c::catalogDispatcher, CatalogEventDispatcher.class),
        row(
            "@Internal CatalogDispatcher",
            c::internalCatalogDispatcher,
            CatalogNormalizeDispatcher.class),
        row("@Public SchemaDispatcher", c::schemaDispatcher, SchemaEventDispatcher.class),
        row(
            "@Internal SchemaDispatcher",
            c::internalSchemaDispatcher,
            SchemaNormalizeDispatcher.class),
        row("@Public TableDispatcher", c::tableDispatcher, TableEventDispatcher.class),
        row(
            "@Internal TableDispatcher",
            c::internalTableDispatcher,
            TableNormalizeDispatcher.class),
        row("@Public PartitionDispatcher", c::partitionDispatcher, PartitionEventDispatcher.class),
        row(
            "@Internal PartitionDispatcher",
            c::internalPartitionDispatcher,
            PartitionNormalizeDispatcher.class),
        row("@Public ViewDispatcher", c::viewDispatcher, ViewEventDispatcher.class),
        row("@Internal ViewDispatcher", c::internalViewDispatcher, ViewNormalizeDispatcher.class),
        row("@Public FilesetDispatcher", c::filesetDispatcher, FilesetEventDispatcher.class),
        row(
            "@Internal FilesetDispatcher",
            c::internalFilesetDispatcher,
            FilesetNormalizeDispatcher.class),
        row("@Public TopicDispatcher", c::topicDispatcher, TopicEventDispatcher.class),
        row(
            "@Internal TopicDispatcher",
            c::internalTopicDispatcher,
            TopicNormalizeDispatcher.class),
        row("@Public ModelDispatcher", c::modelDispatcher, ModelEventDispatcher.class),
        row(
            "@Internal ModelDispatcher",
            c::internalModelDispatcher,
            ModelNormalizeDispatcher.class),
        row("@Public FunctionDispatcher", c::functionDispatcher, FunctionEventDispatcher.class),
        row(
            "@Internal FunctionDispatcher",
            c::internalFunctionDispatcher,
            FunctionNormalizeDispatcher.class),
        row(
            "@Public SemanticModelDispatcher",
            c::semanticModelDispatcher,
            SemanticModelEventDispatcher.class),
        row(
            "@Internal SemanticModelDispatcher",
            c::internalSemanticModelDispatcher,
            SemanticModelNormalizeDispatcher.class),
        row("@Public TagDispatcher", c::tagDispatcher, TagEventDispatcher.class),
        row("@Internal TagDispatcher", c::internalTagDispatcher, TagManager.class),
        row("@Public PolicyDispatcher", c::policyDispatcher, PolicyEventDispatcher.class),
        row("@Internal PolicyDispatcher", c::internalPolicyDispatcher, PolicyManager.class),
        row("@Public OwnerDispatcher", c::ownerDispatcher, OwnerEventManager.class),
        row("@Internal OwnerDispatcher", c::internalOwnerDispatcher, OwnerManager.class),
        row("@Public StatisticDispatcher", c::statisticDispatcher, StatisticEventDispatcher.class),
        row(
            "@Internal StatisticDispatcher",
            c::internalStatisticDispatcher,
            StatisticManager.class),
        row(
            "@Public AccessControlDispatcher",
            c::accessControlDispatcher,
            AccessControlEventDispatcher.class),
        row(
            "@Internal AccessControlDispatcher",
            c::internalAccessControlDispatcher,
            AccessControlManager.class),
        row("@Public JobOperationDispatcher", c::jobOperationDispatcher, JobEventDispatcher.class),
        row(
            "@Internal JobOperationDispatcher",
            c::internalJobOperationDispatcher,
            JobTemplateValidationDispatcher.class));
  }

  private static Row row(String name, Supplier<Object> chain, Class<?> upstream) {
    return new Row(name, chain, upstream);
  }

  private static final class Row {
    final String name;
    final Supplier<Object> chain;
    final Class<?> upstream;

    Row(String name, Supplier<Object> chain, Class<?> upstream) {
      this.name = name;
      this.chain = chain;
      this.upstream = upstream;
    }
  }
}
