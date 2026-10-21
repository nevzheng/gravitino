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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lifecycle.Lifecycle;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.stats.StatisticDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds the {@link Lifecycle} that starts and stops the services of the component.
 *
 * <p>It keeps the order {@code GravitinoEnv} has always used. Start: the metrics system, the event
 * listeners, then the auxiliary services. Stop: the entity store, the catalog manager, the
 * auxiliary services, the metrics system, the event listeners, the metalake manager, the job
 * dispatcher, the statistic dispatcher, the KMS client registry, the secret manager, then the lock
 * manager, so a component leaves none of its lock threads running. Stop is not the reverse of start
 * for the metrics system and the event listeners, so they are one entry that starts and stops them
 * in that order.
 *
 * <p>The lifecycle only touches services the component has built (see {@link BuiltServices}), and
 * never builds one. It is built with {@link Lifecycle.Builder#alwaysStop()}: every service holds
 * resources from the moment it is built, so it is stopped even if the lifecycle never started, as
 * {@code GravitinoEnv.shutdown()} always did. The entity store, auxiliary services, job dispatcher
 * and statistic dispatcher log a failure to stop and carry on, as before; other failures are
 * collected and thrown after every service has been stopped.
 *
 * <p>The {@link ManagedService managed services} a downstream component contributes start after the
 * auxiliary services, in ascending order, and stop first, in reverse. Unlike the services above,
 * they are built when the lifecycle starts, not when it is built: a lifecycle that closes without
 * starting, for example after an initialization that failed part way, builds and stops none of
 * them. Only the managed services that started are stopped.
 */
@Module
abstract class LifecycleModule {

  private static final Logger LOG = LoggerFactory.getLogger(LifecycleModule.class);

  private LifecycleModule() {}

  /** The services a downstream component adds; upstream contributes none. */
  @Multibinds
  abstract Set<ManagedService> managedServices();

  @Provides
  @Singleton
  static Lifecycle lifecycle(
      BuiltServices built, LegacyBridge legacy, Provider<Set<ManagedService>> managedServices) {
    // Services are listed in start order and stop in reverse.
    Lifecycle.Builder lifecycle = Lifecycle.builder();
    lifecycle
        .alwaysStop()
        // Stopped last: the services above may take tree locks while they stop.
        .addStopOnly(
            "lock manager", () -> built.ifBuilt(BuiltServices.LOCK_MANAGER, LockManager::close))
        .addStopOnly(
            "secret manager",
            () -> built.ifBuilt(BuiltServices.SECRET_MANAGER, SecretManager::close))
        .addStopOnly(
            "KMS client registry",
            () -> built.ifBuilt(BuiltServices.KMS_CLIENT_REGISTRY, KmsClientRegistry::close))
        .addStopOnly(
            "statistic dispatcher",
            logFailure("Failed to close StatisticDispatcher", () -> closeStatistics(built)))
        .addStopOnly(
            "job dispatcher",
            logFailure(
                "Failed to close JobOperationDispatcher",
                () ->
                    built.ifBuilt(
                        BuiltServices.JOB_OPERATION_DISPATCHER, JobOperationDispatcher::close)))
        .addStopOnly(
            "metalake manager",
            () -> built.ifBuilt(BuiltServices.METALAKE_MANAGER, MetalakeManager::close))
        .add(
            "metrics system and event listeners",
            () -> {
              built.ifBuilt(BuiltServices.METRICS_SYSTEM, MetricsSystem::start);
              built.ifBuilt(BuiltServices.EVENT_LISTENER_MANAGER, EventListenerManager::start);
            },
            () ->
                runAll(
                    () -> built.ifBuilt(BuiltServices.METRICS_SYSTEM, MetricsSystem::close),
                    () ->
                        built.ifBuilt(
                            BuiltServices.EVENT_LISTENER_MANAGER, EventListenerManager::stop)))
        .add("auxiliary services", legacy::startAuxiliaryServices, legacy::stopAuxiliaryServices)
        .addStopOnly(
            "catalog manager",
            () -> built.ifBuilt(BuiltServices.CATALOG_MANAGER, CatalogManager::close))
        .addStopOnly(
            "entity store",
            logFailure(
                "Failed to close EntityStore.",
                () -> built.ifBuilt(BuiltServices.ENTITY_STORE, EntityStore::close)));
    // Managed services start after the services above and stop before them.
    ManagedServices managed = new ManagedServices(managedServices);
    lifecycle.add("managed services", managed::start, managed::stop);
    return lifecycle.build();
  }

  /** Sorts the managed services by order, and rejects two with the same order. */
  private static List<ManagedService> inOrder(Set<ManagedService> services) {
    List<ManagedService> ordered = new ArrayList<>(services);
    ordered.sort(Comparator.comparingInt(ManagedService::order));
    for (int i = 1; i < ordered.size(); i++) {
      Preconditions.checkState(
          ordered.get(i - 1).order() != ordered.get(i).order(),
          "Managed services %s and %s have order %s",
          ordered.get(i - 1).name(),
          ordered.get(i).name(),
          ordered.get(i).order());
    }
    return ordered;
  }

  /** Closes the public statistic chain if it was built, and the statistic manager otherwise. */
  private static void closeStatistics(BuiltServices built) throws Exception {
    StatisticDispatcher statistics = built.get(BuiltServices.STATISTIC_DISPATCHER);
    if (statistics == null) {
      statistics = built.get(BuiltServices.INTERNAL_STATISTIC_DISPATCHER);
    }
    if (statistics != null) {
      statistics.close();
    }
  }

  private static Lifecycle.Action logFailure(String message, Lifecycle.Action action) {
    return () -> {
      try {
        action.run();
      } catch (Exception e) {
        LOG.warn(message, e);
      }
    };
  }

  /** Runs every action, then throws the first failure with the others suppressed. */
  private static void runAll(Lifecycle.Action... actions) throws Exception {
    Exception failure = null;
    for (Lifecycle.Action action : actions) {
      try {
        action.run();
      } catch (Exception e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * The managed services of the lifecycle. They are built when the lifecycle starts, so building or
   * closing a lifecycle that never started builds none of them. Each one is recorded once its start
   * returns, and only the recorded ones are stopped when the lifecycle closes or its start fails: a
   * service whose start threw, and the services after it, are not stopped. The lifecycle calls
   * these methods while it holds its lock.
   */
  private static final class ManagedServices {
    private final Provider<Set<ManagedService>> provider;
    private List<ManagedService> started = new ArrayList<>();

    private ManagedServices(Provider<Set<ManagedService>> provider) {
      this.provider = provider;
    }

    /** Builds the managed services, then starts them in ascending order. */
    private void start() throws Exception {
      for (ManagedService service : inOrder(provider.get())) {
        service.start();
        started.add(service);
      }
    }

    /** Stops every managed service that started, in reverse order, once. */
    private void stop() throws Exception {
      List<ManagedService> toStop = started;
      started = new ArrayList<>();
      Exception failure = null;
      for (ManagedService service : Lists.reverse(toStop)) {
        try {
          service.stop();
        } catch (Exception e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
