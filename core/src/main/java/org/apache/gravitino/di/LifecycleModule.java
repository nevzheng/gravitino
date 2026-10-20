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

import dagger.Module;
import dagger.Provides;
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
 */
@Module
abstract class LifecycleModule {

  private static final Logger LOG = LoggerFactory.getLogger(LifecycleModule.class);

  private LifecycleModule() {}

  @Provides
  @Singleton
  static Lifecycle lifecycle(BuiltServices built, LegacyBridge legacy) {
    // Services are listed in start order and stop in reverse.
    return Lifecycle.builder()
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
                () -> built.ifBuilt(BuiltServices.ENTITY_STORE, EntityStore::close)))
        .build();
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
}
