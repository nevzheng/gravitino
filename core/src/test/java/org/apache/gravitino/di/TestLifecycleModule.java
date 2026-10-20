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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lifecycle.Lifecycle;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Checks the start and stop order of the {@link LifecycleModule} lifecycle. */
public class TestLifecycleModule {

  private final BuiltServices built = new BuiltServices();
  private final LegacyBridge legacy = mock(LegacyBridge.class);

  private MetricsSystem metrics;
  private EventListenerManager listeners;
  private EntityStore entityStore;
  private KmsClientRegistry kms;
  private SecretManager secrets;
  private CatalogManager catalogs;
  private MetalakeManager metalakes;
  private StatisticDispatcher internalStatistics;
  private StatisticDispatcher statistics;
  private JobOperationDispatcher jobs;
  private LockManager locks;

  @BeforeEach
  public void setUp() {
    metrics = mock(MetricsSystem.class);
    listeners = mock(EventListenerManager.class);
    entityStore = mock(EntityStore.class);
    kms = mock(KmsClientRegistry.class);
    secrets = mock(SecretManager.class);
    catalogs = mock(CatalogManager.class);
    metalakes = mock(MetalakeManager.class);
    internalStatistics = mock(StatisticDispatcher.class);
    statistics = mock(StatisticDispatcher.class);
    jobs = mock(JobOperationDispatcher.class);
    locks = mock(LockManager.class);
  }

  @Test
  public void testFullProfileStartsAndStopsInTheEnvironmentOrder() throws Exception {
    buildFullProfile();
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, legacy);

    lifecycle.start();
    InOrder start = inOrder(metrics, listeners, legacy);
    start.verify(metrics).start();
    start.verify(listeners).start();
    start.verify(legacy).startAuxiliaryServices();

    lifecycle.close();
    verifyStopOrder();
  }

  @Test
  public void testStopsEveryBuiltServiceWhenNeverStarted() throws Exception {
    buildFullProfile();
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, legacy);

    lifecycle.close();

    verify(metrics, never()).start();
    verify(listeners, never()).start();
    verify(legacy, never()).startAuxiliaryServices();
    verifyStopOrder();
  }

  @Test
  public void testTouchesOnlyBuiltServices() throws Exception {
    // The base profile: no entity store, catalogs, metalakes, statistics or jobs.
    built.add(BuiltServices.METRICS_SYSTEM, metrics);
    built.add(BuiltServices.EVENT_LISTENER_MANAGER, listeners);
    built.add(BuiltServices.KMS_CLIENT_REGISTRY, kms);
    built.add(BuiltServices.SECRET_MANAGER, secrets);
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, legacy);

    lifecycle.start();
    lifecycle.close();

    InOrder stop = inOrder(metrics, listeners, kms, secrets);
    stop.verify(metrics).close();
    stop.verify(listeners).stop();
    stop.verify(kms).close();
    stop.verify(secrets).close();
    verifyNoInteractions(
        entityStore, catalogs, metalakes, internalStatistics, statistics, jobs, locks);
  }

  @Test
  public void testClosesTheStatisticManagerWithoutThePublicChain() throws Exception {
    // The metadata profile builds only the internal statistic chain.
    built.add(BuiltServices.INTERNAL_STATISTIC_DISPATCHER, internalStatistics);

    LifecycleModule.lifecycle(built, legacy).close();

    verify(internalStatistics).close();
  }

  @Test
  public void testLoggedFailuresDoNotStopShutdown() throws Exception {
    buildFullProfile();
    doThrow(new IOException("store")).when(entityStore).close();
    doThrow(new IOException("jobs")).when(jobs).close();
    doThrow(new IOException("statistics")).when(statistics).close();

    // These failures were always logged and swallowed, so close() does not throw.
    LifecycleModule.lifecycle(built, legacy).close();

    verifyStopOrder();
  }

  @Test
  public void testOtherFailuresAreThrownAfterEveryServiceStopped() throws Exception {
    buildFullProfile();
    IllegalStateException catalogFailure = new IllegalStateException("catalogs");
    IllegalStateException metricsFailure = new IllegalStateException("metrics");
    doThrow(catalogFailure).when(catalogs).close();
    doThrow(metricsFailure).when(metrics).close();

    Lifecycle lifecycle = LifecycleModule.lifecycle(built, legacy);
    Lifecycle.LifecycleException failure =
        assertThrows(Lifecycle.LifecycleException.class, lifecycle::close);

    assertSame(catalogFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(metricsFailure, failure.getSuppressed()[0].getCause());
    // The event listeners stop even though the metrics system, in the same entry, failed first.
    verifyStopOrder();
  }

  @Test
  public void testStartFailureStopsEveryService() throws Exception {
    buildFullProfile();
    IllegalStateException failure = new IllegalStateException("aux");
    doThrow(failure).when(legacy).startAuxiliaryServices();

    Lifecycle lifecycle = LifecycleModule.lifecycle(built, legacy);

    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
    verifyStopOrder();
    assertEquals(Lifecycle.State.CLOSED, lifecycle.state());
  }

  @Test
  public void testComponentLifecycleStopsWhatTheComponentBuilt() throws Exception {
    GravitinoComponent component =
        DaggerGravitinoComponent.factory()
            .create(new Config(false) {}, new TestGravitinoComponent.FakeLegacyBridge());
    KmsClientRegistry registry = component.kmsClientRegistry();
    Lifecycle lifecycle = component.lifecycle();
    assertSame(lifecycle, component.lifecycle());

    // Nothing else was built, and closing builds nothing.
    lifecycle.close();

    assertThrows(
        IllegalStateException.class, () -> registry.getClient(new KmsReference("missing", "key")));
  }

  private void buildFullProfile() {
    built.add(BuiltServices.KMS_CLIENT_REGISTRY, kms);
    built.add(BuiltServices.SECRET_MANAGER, secrets);
    built.add(BuiltServices.METRICS_SYSTEM, metrics);
    built.add(BuiltServices.EVENT_LISTENER_MANAGER, listeners);
    built.add(BuiltServices.ENTITY_STORE, entityStore);
    built.add(BuiltServices.CATALOG_MANAGER, catalogs);
    built.add(BuiltServices.METALAKE_MANAGER, metalakes);
    built.add(BuiltServices.INTERNAL_STATISTIC_DISPATCHER, internalStatistics);
    built.add(BuiltServices.STATISTIC_DISPATCHER, statistics);
    built.add(BuiltServices.JOB_OPERATION_DISPATCHER, jobs);
    built.add(BuiltServices.LOCK_MANAGER, locks);
  }

  /** The order GravitinoEnv.shutdown() has always used. */
  private void verifyStopOrder() throws Exception {
    InOrder stop =
        inOrder(
            entityStore,
            catalogs,
            legacy,
            metrics,
            listeners,
            metalakes,
            jobs,
            statistics,
            kms,
            secrets,
            locks);
    stop.verify(entityStore).close();
    stop.verify(catalogs).close();
    stop.verify(legacy).stopAuxiliaryServices();
    stop.verify(metrics).close();
    stop.verify(listeners).stop();
    stop.verify(metalakes).close();
    stop.verify(jobs).close();
    stop.verify(statistics).close();
    stop.verify(kms).close();
    stop.verify(secrets).close();
    stop.verify(locks).close();
    // Only the public statistic chain is closed; it closes the manager it wraps.
    verify(internalStatistics, never()).close();
  }
}
