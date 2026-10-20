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
package org.apache.gravitino.storage.relational;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Binds the {@link GravitinoEnv} fields the relational store still reads through the singleton, and
 * puts the previous values back on {@link #close()}.
 *
 * <p>The meta services, {@code POStorageReadRouting} and {@link RelationalEntityStore} are not part
 * of the component yet: they read the configuration (the cache flag and the schema separator), the
 * ID generator (nested schema ancestors) and the metrics system from {@code GravitinoEnv}. Store
 * tests bind those three values here, and only here, so that every binding is undone and no test
 * depends on what an earlier one left behind. This fixture goes away with the singleton once the
 * meta services become instances.
 *
 * <pre>{@code
 * try (LegacyStoreEnvironment env = new LegacyStoreEnvironment().withConfig(config)) {
 *   ...
 * }
 * }</pre>
 */
public final class LegacyStoreEnvironment implements AutoCloseable {

  private final Deque<Map.Entry<String, Object>> previousValues = new ArrayDeque<>();

  /** Creates a binding that has not changed anything yet. */
  public LegacyStoreEnvironment() {}

  /**
   * Binds the server configuration the store reads.
   *
   * @param config the configuration, or null to bind none
   * @return this binding
   */
  public LegacyStoreEnvironment withConfig(@Nullable Config config) {
    return bind("config", config);
  }

  /**
   * Binds the ID generator the store reads.
   *
   * @param idGenerator the ID generator, or null to bind none
   * @return this binding
   */
  public LegacyStoreEnvironment withIdGenerator(@Nullable IdGenerator idGenerator) {
    return bind("idGenerator", idGenerator);
  }

  /**
   * Binds the metrics system the store registers its sources with.
   *
   * @param metricsSystem the metrics system, or null to bind none
   * @return this binding
   */
  public LegacyStoreEnvironment withMetricsSystem(@Nullable MetricsSystem metricsSystem) {
    return bind("metricsSystem", metricsSystem);
  }

  /** Puts back every value this binding replaced, most recent first. */
  @Override
  public void close() {
    while (!previousValues.isEmpty()) {
      Map.Entry<String, Object> previous = previousValues.pop();
      write(previous.getKey(), previous.getValue());
    }
  }

  private LegacyStoreEnvironment bind(String field, @Nullable Object value) {
    try {
      // SimpleImmutableEntry, unlike Map.entry, holds the null an unset field reads as.
      previousValues.push(
          new AbstractMap.SimpleImmutableEntry<>(
              field, FieldUtils.readField(GravitinoEnv.getInstance(), field, true)));
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Cannot read GravitinoEnv." + field, e);
    }
    write(field, value);
    return this;
  }

  private static void write(String field, @Nullable Object value) {
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), field, value, true);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Cannot write GravitinoEnv." + field, e);
    }
  }
}
