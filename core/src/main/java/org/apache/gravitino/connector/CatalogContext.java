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
package org.apache.gravitino.connector;

import javax.annotation.Nullable;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The host services a catalog plugin may use.
 *
 * <p>{@code CatalogManager} builds one context from the server's object graph and passes it to
 * every catalog it loads, through {@link BaseCatalog#catalogContext()} and {@link
 * CatalogOperations#initialize(java.util.Map, CatalogInfo, HasPropertyMetadata, CatalogContext)}. A
 * plugin reaches the host only through these methods, never through a static. The interface is
 * plain Java: it carries no dependency-injection types across the plugin class loader boundary.
 */
@Evolving
public interface CatalogContext {

  /**
   * Returns the entity store that holds the server's metadata.
   *
   * @return the entity store
   */
  EntityStore entityStore();

  /**
   * Returns the secret manager that resolves secret references in catalog properties.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the metrics system catalogs register their metrics sources with.
   *
   * @return the metrics system, or null when the host has none, for example in some tests
   */
  @Nullable
  MetricsSystem metricsSystem();

  /**
   * Returns the generator of entity ids.
   *
   * @return the id generator
   */
  IdGenerator idGenerator();

  /**
   * Returns whether a catalog adds the plaintext credentials of its credential providers to the
   * properties it returns, as the server configuration {@code
   * gravitino.catalog.credential.backfillToProperties} asks for legacy connectors.
   *
   * @return true if credentials are added to catalog properties; false unless the host enables it
   */
  default boolean credentialBackfillToProperties() {
    return false;
  }

  /**
   * Creates a context from host services that does not add credentials to catalog properties.
   *
   * @param entityStore the entity store
   * @param secretManager the secret manager
   * @param metricsSystem the metrics system, or null when the host has none
   * @param idGenerator the id generator
   * @return a context that returns the given services
   */
  static CatalogContext of(
      EntityStore entityStore,
      SecretManager secretManager,
      @Nullable MetricsSystem metricsSystem,
      IdGenerator idGenerator) {
    return of(entityStore, secretManager, metricsSystem, idGenerator, false);
  }

  /**
   * Creates a context from host services.
   *
   * @param entityStore the entity store
   * @param secretManager the secret manager
   * @param metricsSystem the metrics system, or null when the host has none
   * @param idGenerator the id generator
   * @param credentialBackfillToProperties whether catalogs add the plaintext credentials of their
   *     credential providers to their properties
   * @return a context that returns the given services
   */
  static CatalogContext of(
      EntityStore entityStore,
      SecretManager secretManager,
      @Nullable MetricsSystem metricsSystem,
      IdGenerator idGenerator,
      boolean credentialBackfillToProperties) {
    return new CatalogContext() {
      @Override
      public EntityStore entityStore() {
        return entityStore;
      }

      @Override
      public SecretManager secretManager() {
        return secretManager;
      }

      @Override
      public MetricsSystem metricsSystem() {
        return metricsSystem;
      }

      @Override
      public IdGenerator idGenerator() {
        return idGenerator;
      }

      @Override
      public boolean credentialBackfillToProperties() {
        return credentialBackfillToProperties;
      }
    };
  }
}
