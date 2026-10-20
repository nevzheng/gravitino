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
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.metrics.source.JVMMetricsSource;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.RandomIdGenerator;

/**
 * Binds the process-wide services that depend only on configuration: the id generator, the metrics
 * system and whether authorization is enabled. The {@code Config} itself is bound by the component
 * factory.
 */
@Module
abstract class ConfigModule {

  private ConfigModule() {}

  @Provides
  @Singleton
  static IdGenerator idGenerator() {
    return new RandomIdGenerator();
  }

  @Provides
  @AuthorizationEnabled
  static boolean authorizationEnabled(Config config) {
    return config.get(Configs.ENABLE_AUTHORIZATION);
  }

  /**
   * Builds the metrics system with the JVM metrics source registered. Dagger does not start or
   * close it; {@code GravitinoEnv} does.
   */
  @SuppressWarnings("CloseableProvides")
  @Provides
  @Singleton
  static MetricsSystem metricsSystem() {
    MetricsSystem metricsSystem = new MetricsSystem();
    metricsSystem.register(new JVMMetricsSource());
    return metricsSystem;
  }
}
