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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Set;
import javax.inject.Provider;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
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
  private AuxiliaryServiceManager aux;
  private LockManager locks;
  private GravitinoAuthorizer authorizer;

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
    aux = mock(AuxiliaryServiceManager.class);
    locks = mock(LockManager.class);
    authorizer = mock(GravitinoAuthorizer.class);
  }

  @Test
  public void testFullProfileStartsAndStopsInTheEnvironmentOrder() throws Exception {
    buildFullProfile();
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, ImmutableSet::of);

    lifecycle.start();
    InOrder start = inOrder(metrics, listeners, aux);
    start.verify(metrics).start();
    start.verify(listeners).start();
    start.verify(aux).serviceStart();

    lifecycle.close();
    verifyStopOrder();
  }

  @Test
  public void testStopsEveryBuiltServiceWhenNeverStarted() throws Exception {
    buildFullProfile();
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, ImmutableSet::of);

    lifecycle.close();

    verify(metrics, never()).start();
    verify(listeners, never()).start();
    verify(aux, never()).serviceStart();
    verifyStopOrder();
  }

  @Test
  public void testTouchesOnlyBuiltServices() throws Exception {
    // The base profile: no entity store, catalogs, metalakes, statistics, jobs or auxiliary
    // services.
    built.add(BuiltServices.METRICS_SYSTEM, metrics);
    built.add(BuiltServices.EVENT_LISTENER_MANAGER, listeners);
    built.add(BuiltServices.KMS_CLIENT_REGISTRY, kms);
    built.add(BuiltServices.SECRET_MANAGER, secrets);
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, ImmutableSet::of);

    lifecycle.start();
    lifecycle.close();

    InOrder stop = inOrder(metrics, listeners, kms, secrets);
    stop.verify(metrics).close();
    stop.verify(listeners).stop();
    stop.verify(kms).close();
    stop.verify(secrets).close();
    verifyNoInteractions(
        entityStore, catalogs, metalakes, internalStatistics, statistics, jobs, aux, locks);
  }

  @Test
  public void testClosesTheStatisticManagerWithoutThePublicChain() throws Exception {
    // The metadata profile builds only the internal statistic chain.
    built.add(BuiltServices.INTERNAL_STATISTIC_DISPATCHER, internalStatistics);

    LifecycleModule.lifecycle(built, ImmutableSet::of).close();

    verify(internalStatistics).close();
  }

  @Test
  public void testLoggedFailuresDoNotStopShutdown() throws Exception {
    buildFullProfile();
    doThrow(new IOException("store")).when(entityStore).close();
    doThrow(new IOException("jobs")).when(jobs).close();
    doThrow(new IOException("statistics")).when(statistics).close();

    // These failures were always logged and swallowed, so close() does not throw.
    LifecycleModule.lifecycle(built, ImmutableSet::of).close();

    verifyStopOrder();
  }

  @Test
  public void testOtherFailuresAreThrownAfterEveryServiceStopped() throws Exception {
    buildFullProfile();
    IllegalStateException catalogFailure = new IllegalStateException("catalogs");
    IllegalStateException metricsFailure = new IllegalStateException("metrics");
    doThrow(catalogFailure).when(catalogs).close();
    doThrow(metricsFailure).when(metrics).close();

    Lifecycle lifecycle = LifecycleModule.lifecycle(built, ImmutableSet::of);
    Lifecycle.LifecycleException failure =
        assertThrows(Lifecycle.LifecycleException.class, lifecycle::close);

    assertSame(catalogFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(metricsFailure, failure.getSuppressed()[0].getCause());
    // The event listeners stop even though the metrics system, in the same entry, failed first.
    verifyStopOrder();
  }

  @Test
  public void testAuxiliaryServiceStopFailureIsLogged() throws Exception {
    buildFullProfile();
    doThrow(new IllegalStateException("aux")).when(aux).serviceStop();

    LifecycleModule.lifecycle(built, ImmutableSet::of).close();

    // GravitinoEnv.shutdown() has always logged this failure and carried on.
    verifyStopOrder();
  }

  @Test
  public void testStartFailureStopsEveryService() throws Exception {
    buildFullProfile();
    IllegalStateException failure = new IllegalStateException("aux");
    doThrow(failure).when(aux).serviceStart();

    Lifecycle lifecycle = LifecycleModule.lifecycle(built, ImmutableSet::of);

    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
    verifyStopOrder();
    assertEquals(Lifecycle.State.CLOSED, lifecycle.state());
  }

  @Test
  public void testComponentLifecycleStopsWhatTheComponentBuilt() throws Exception {
    GravitinoComponent component = DaggerGravitinoComponent.factory().create(new Config(false) {});
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
    built.add(BuiltServices.AUXILIARY_SERVICE_MANAGER, aux);
    built.add(BuiltServices.LOCK_MANAGER, locks);
    built.add(BuiltServices.AUTHORIZER, authorizer);
  }

  @Test
  public void testManagedServicesStartLastAndStopFirstInTheirOrder() throws Exception {
    buildFullProfile();
    ManagedService search = mock(ManagedService.class);
    when(search.order()).thenReturn(20);
    when(search.name()).thenReturn("search");
    ManagedService license = mock(ManagedService.class);
    when(license.order()).thenReturn(10);
    when(license.name()).thenReturn("license");
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, () -> ImmutableSet.of(search, license));

    lifecycle.start();
    InOrder start = inOrder(metrics, listeners, aux, license, search);
    start.verify(metrics).start();
    start.verify(listeners).start();
    start.verify(aux).serviceStart();
    start.verify(license).start();
    start.verify(search).start();

    lifecycle.close();
    // The authorizer stops before the managed services, as the server closed it first.
    InOrder stop = inOrder(authorizer, search, license, entityStore);
    stop.verify(authorizer).close();
    stop.verify(search).stop();
    stop.verify(license).stop();
    stop.verify(entityStore).close();
    verifyStopOrder();
  }

  @Test
  public void testManagedServicesAreNotBuiltWhenNeverStarted() throws Exception {
    buildFullProfile();
    @SuppressWarnings("unchecked")
    Provider<Set<ManagedService>> managedServices = mock(Provider.class);

    LifecycleModule.lifecycle(built, managedServices).close();

    // A shutdown after an initialization that failed part way builds no managed service.
    verifyNoInteractions(managedServices);
    verifyStopOrder();
  }

  @Test
  public void testManagedServicesThatFailToBuildStopTheOthers() throws Exception {
    buildFullProfile();
    IllegalStateException failure = new IllegalStateException("cannot build");
    Lifecycle lifecycle =
        LifecycleModule.lifecycle(
            built,
            () -> {
              throw failure;
            });

    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
    verifyStopOrder();
    lifecycle.close();
  }

  @Test
  public void testOnlyStartedManagedServicesStopWhenOneFailsToStart() throws Exception {
    buildFullProfile();
    ManagedService audit = mock(ManagedService.class);
    when(audit.order()).thenReturn(5);
    when(audit.name()).thenReturn("audit");
    ManagedService license = mock(ManagedService.class);
    when(license.order()).thenReturn(10);
    when(license.name()).thenReturn("license");
    ManagedService search = mock(ManagedService.class);
    when(search.order()).thenReturn(20);
    when(search.name()).thenReturn("search");
    IOException failure = new IOException("license expired");
    doThrow(failure).when(license).start();
    Lifecycle lifecycle =
        LifecycleModule.lifecycle(built, () -> ImmutableSet.of(search, license, audit));

    assertSame(failure, assertThrows(IOException.class, lifecycle::start));
    lifecycle.close();

    // Only the service that started is stopped, once: the failed one and the one after it never
    // started.
    verify(audit, times(1)).start();
    verify(audit, times(1)).stop();
    verify(license, never()).stop();
    verify(search, never()).start();
    verify(search, never()).stop();
    InOrder stop = inOrder(audit, entityStore);
    stop.verify(audit).stop();
    stop.verify(entityStore).close();
  }

  @Test
  public void testTwoManagedServicesWithTheSameOrderAreRejected() throws Exception {
    ManagedService first = mock(ManagedService.class);
    when(first.order()).thenReturn(5);
    ManagedService second = mock(ManagedService.class);
    when(second.order()).thenReturn(5);
    Lifecycle lifecycle = LifecycleModule.lifecycle(built, () -> ImmutableSet.of(first, second));

    assertThrows(IllegalStateException.class, lifecycle::start);
    verify(first, never()).start();
    verify(second, never()).start();
  }

  /** The order GravitinoEnv.shutdown() has always used. */
  private void verifyStopOrder() throws Exception {
    InOrder stop =
        inOrder(
            authorizer,
            entityStore,
            catalogs,
            aux,
            metrics,
            listeners,
            metalakes,
            jobs,
            statistics,
            kms,
            secrets,
            locks);
    stop.verify(authorizer).close();
    stop.verify(entityStore).close();
    stop.verify(catalogs).close();
    stop.verify(aux).serviceStop();
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
