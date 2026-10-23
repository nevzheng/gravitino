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
package org.apache.gravitino.auxiliary;

import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.secret.SecretPropertyOperationDispatcher;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The host services an auxiliary service may use.
 *
 * <p>The Gravitino server builds one context from its object graph and passes it to every auxiliary
 * service it loads, through {@link GravitinoAuxiliaryService#serviceInit(java.util.Map, boolean,
 * AuxiliaryContext)}. A service reaches the host only through these methods, never through a
 * static. The interface is plain Java: it carries no dependency-injection types across the class
 * loader boundary of the service.
 *
 * <p>A standalone server that runs one auxiliary service on its own, such as the Iceberg REST
 * server, builds only the base services: the configuration, the event bus, the metrics system, the
 * secret manager and the KMS client registry. It passes {@link #standalone}, whose other methods
 * throw {@link IllegalStateException}. A service learns which case it runs in from the {@code
 * auxMode} argument of {@code serviceInit}.
 *
 * <p>The dispatchers are those the server's REST API uses, unless their name says {@code internal}:
 * an internal dispatcher normalizes identifiers but runs no hooks and emits no events.
 */
@Evolving
public interface AuxiliaryContext {

  /**
   * Returns the configuration of the server that runs the service.
   *
   * @return the server configuration
   */
  Config config();

  /**
   * Returns the event bus the service dispatches its events through.
   *
   * @return the event bus
   */
  EventBus eventBus();

  /**
   * Returns the metrics system the service registers its metrics sources with.
   *
   * @return the metrics system
   */
  MetricsSystem metricsSystem();

  /**
   * Returns the secret manager that resolves secret references in properties.
   *
   * @return the secret manager
   */
  SecretManager secretManager();

  /**
   * Returns the registry of KMS clients. The host owns it; the service must not close it.
   *
   * @return the KMS client registry
   */
  KmsClientRegistry kmsClientRegistry();

  /**
   * Returns the generator of entity ids. Only available inside the Gravitino server.
   *
   * @return the id generator
   * @throws IllegalStateException if the service runs standalone
   */
  IdGenerator idGenerator();

  /**
   * Returns the tree lock manager. Only available inside the Gravitino server.
   *
   * @return the lock manager
   * @throws IllegalStateException if the service runs standalone
   */
  LockManager lockManager();

  /**
   * Returns the entity store that holds the server's metadata. Only available inside the Gravitino
   * server.
   *
   * @return the entity store
   * @throws IllegalStateException if the service runs standalone
   */
  EntityStore entityStore();

  /**
   * Returns the catalog manager. Only available inside the Gravitino server.
   *
   * @return the catalog manager
   * @throws IllegalStateException if the service runs standalone
   */
  CatalogManager catalogManager();

  /**
   * Returns the catalog dispatcher of the REST API. Only available inside the Gravitino server.
   *
   * @return the catalog dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  CatalogDispatcher catalogDispatcher();

  /**
   * Returns the schema dispatcher of the REST API. Only available inside the Gravitino server.
   *
   * @return the schema dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  SchemaDispatcher schemaDispatcher();

  /**
   * Returns the table dispatcher of the REST API. Only available inside the Gravitino server.
   *
   * @return the table dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  TableDispatcher tableDispatcher();

  /**
   * Returns the policy dispatcher of the REST API. Only available inside the Gravitino server.
   *
   * @return the policy dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  PolicyDispatcher policyDispatcher();

  /**
   * Returns the dispatcher that reads the secrets of catalogs and schemas. Only available inside
   * the Gravitino server.
   *
   * @return the secret property operation dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  SecretPropertyOperationDispatcher secretPropertyOperationDispatcher();

  /**
   * Returns the internal schema dispatcher. Only available inside the Gravitino server.
   *
   * @return the internal schema dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  SchemaDispatcher internalSchemaDispatcher();

  /**
   * Returns the internal table dispatcher. Only available inside the Gravitino server.
   *
   * @return the internal table dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  TableDispatcher internalTableDispatcher();

  /**
   * Returns the internal view dispatcher. Only available inside the Gravitino server.
   *
   * @return the internal view dispatcher
   * @throws IllegalStateException if the service runs standalone
   */
  ViewDispatcher internalViewDispatcher();

  /**
   * Returns the internal owner dispatcher, which sets the owner of new objects. Only available
   * inside the Gravitino server.
   *
   * @return the internal owner dispatcher, or null when authorization is disabled
   * @throws IllegalStateException if the service runs standalone
   */
  @Nullable
  OwnerDispatcher internalOwnerDispatcher();

  /**
   * Creates the context of a standalone server, which builds only the base services. Every other
   * method of the context throws {@link IllegalStateException}.
   *
   * @param config the configuration of the standalone server
   * @param eventBus the event bus
   * @param metricsSystem the metrics system
   * @param secretManager the secret manager
   * @param kmsClientRegistry the KMS client registry
   * @return a context that returns the given services
   */
  static AuxiliaryContext standalone(
      Config config,
      EventBus eventBus,
      MetricsSystem metricsSystem,
      SecretManager secretManager,
      KmsClientRegistry kmsClientRegistry) {
    return new StandaloneAuxiliaryContext(
        config, eventBus, metricsSystem, secretManager, kmsClientRegistry);
  }
}
