/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Referred from Apache Spark's connector/catalog implementation
// sql/catalyst/src/main/java/org/apache/spark/sql/connector/catalog/SupportNamespaces.java

package org.apache.gravitino;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;

/**
 * The client interface to support schema operations. The server side should use the other one with
 * the same name in the core module.
 */
@Evolving
public interface SupportsSchemas {

  /**
   * List schemas under the entity.
   *
   * <p>If an entity such as a table, view exists, its parent schemas must also exist and must be
   * returned by this discovery method. For example, if table a.b.t exists, this method invoked as
   * listSchemas(a) must return [b] in the result array
   *
   * @return An array of schema names under the namespace.
   * @throws NoSuchCatalogException If the catalog does not exist.
   */
  String[] listSchemas() throws NoSuchCatalogException;

  /**
   * List the schemas directly under the given parent schema.
   *
   * <p>This is only meaningful for catalogs that support hierarchical (multi-level) schemas, such
   * as an Iceberg catalog accessed through the Gravitino REST server with a configured schema
   * separator. For example, when the schemas {@code a}, {@code a:b} and {@code a:b:c} exist, this
   * method invoked with parent {@code a:b} returns {@code [a:b:c]}. For a flat catalog, or a parent
   * schema that has no children, an empty array is returned.
   *
   * @param parentSchema The parent (possibly hierarchical) schema name whose direct children are
   *     listed, e.g. {@code "a"} or {@code "a:b"}. Must not be null or blank.
   * @return An array of schema names directly under the given parent schema.
   * @throws IllegalArgumentException If {@code parentSchema} is null or blank.
   * @throws NoSuchCatalogException If the catalog does not exist.
   * @throws NoSuchSchemaException If the parent schema does not exist.
   */
  default String[] listSchemas(String parentSchema)
      throws NoSuchCatalogException, NoSuchSchemaException {
    throw new UnsupportedOperationException(
        "Listing schemas under a parent schema is not supported by this catalog");
  }

  /**
   * Lists retained top-level schema deletion generations.
   *
   * <p>The optional filters select an exact full logical schema name, immutable schema ID, or both.
   * Returned generations describe Gravitino metadata only and make no assertion about a downstream
   * schema.
   *
   * @param name An exact full logical schema-name filter, or {@code null} for every name.
   * @param id An exact immutable schema-ID filter, or {@code null} for every ID.
   * @return The retained top-level schema deletion generations matching the filters.
   * @throws NoSuchCatalogException If the catalog does not exist.
   * @throws UnsupportedOperationException If recoverable schema deletion is not supported.
   */
  default DeletedEntity[] listDeletedSchemas(@Nullable String name, @Nullable String id)
      throws NoSuchCatalogException, UnsupportedOperationException {
    return listDeletedSchemas(null, name, id);
  }

  /**
   * Lists retained schema deletion generations directly under a hierarchy scope.
   *
   * <p>Only independently deleted roots are returned. Descendants removed by an ancestor cascade
   * return with that ancestor rather than as separate recovery targets.
   *
   * @param parentSchema The full logical parent schema name, or {@code null} for top-level roots.
   * @param name An exact full logical schema-name filter, or {@code null} for every name.
   * @param id An exact immutable schema-ID filter, or {@code null} for every ID.
   * @return The retained schema deletion generations matching the scope and filters.
   * @throws NoSuchCatalogException If the catalog does not exist.
   * @throws NoSuchSchemaException If the requested parent schema does not exist.
   * @throws IllegalArgumentException If a supplied parent or filter is invalid.
   * @throws UnsupportedOperationException If recoverable schema deletion is not supported.
   */
  default DeletedEntity[] listDeletedSchemas(
      @Nullable String parentSchema, @Nullable String name, @Nullable String id)
      throws NoSuchCatalogException, NoSuchSchemaException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedSchemas not supported.");
  }

  /**
   * Check if a schema exists.
   *
   * <p>If an entity such as a table, view exists, its parent namespaces must also exist. For
   * example, if table a.b.t exists, this method invoked as schemaExists(a.b) must return true.
   *
   * @param schemaName The name of the schema.
   * @return True if the schema exists, false otherwise.
   */
  default boolean schemaExists(String schemaName) {
    try {
      loadSchema(schemaName);
      return true;
    } catch (NoSuchSchemaException e) {
      return false;
    }
  }

  /**
   * Creates a schema in the catalog based on the provided details.
   *
   * <p>This method returns the schema as defined by the user without applying all defaults. If you
   * need the schema with default values applied, use the {@link #loadSchema(String)} method after
   * creation.
   *
   * @param schemaName The name of the schema.
   * @param comment The comment of the schema.
   * @param properties The properties of the schema.
   * @return The schema as defined by the caller, without all default values.
   * @throws NoSuchCatalogException If the catalog does not exist.
   * @throws SchemaAlreadyExistsException If the schema already exists.
   */
  Schema createSchema(String schemaName, String comment, Map<String, String> properties)
      throws NoSuchCatalogException, SchemaAlreadyExistsException;

  /**
   * Load metadata properties for a schema.
   *
   * @param schemaName The name of the schema.
   * @return A schema.
   * @throws NoSuchSchemaException If the schema does not exist (optional).
   */
  Schema loadSchema(String schemaName) throws NoSuchSchemaException;

  /**
   * Loads one exact retained deletion generation for a schema.
   *
   * <p>The full logical schema name and immutable ID must identify the same independently deleted
   * root. The returned generation supplies the optimistic precondition required by {@link
   * #restoreSchema(String, DeletedEntity)}.
   *
   * @param schemaName The full logical schema name.
   * @param id The immutable schema ID.
   * @return The exact retained schema deletion generation.
   * @throws IllegalArgumentException If the name or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws UnsupportedOperationException If recoverable schema deletion is not supported.
   */
  default DeletedEntity loadDeletedSchema(String schemaName, String id)
      throws TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedSchema not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino schema metadata.
   *
   * <p>This operation is metadata-only and does not validate or recreate downstream objects. If the
   * selected schema deletion cascaded, its recorded metadata tree is restored atomically. Replaying
   * an accepted generation is idempotent while the server can still prove that exact restore.
   *
   * <p>After {@link TombstoneChangedException}, callers must reread the same logical path and
   * immutable ID before deciding whether to retry; clients must never substitute a different ID or
   * generation. An unknown transport outcome may replay the same generation. A recovery conflict or
   * expired generation is not retryable as-is.
   *
   * @param schemaName The full logical schema name.
   * @param generation The exact retained deletion generation and optimistic precondition.
   * @return The restored schema metadata.
   * @throws IllegalArgumentException If the schema name, generation type, ID, or ETag is invalid or
   *     inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If recoverable schema deletion is not supported.
   */
  default Schema restoreSchema(String schemaName, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreSchema not supported.");
  }

  /**
   * Apply the metadata change to a schema in the catalog.
   *
   * @param schemaName The name of the schema.
   * @param changes The metadata changes to apply.
   * @return The altered schema.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  Schema alterSchema(String schemaName, SchemaChange... changes) throws NoSuchSchemaException;

  /**
   * Drop a schema from the catalog. If cascade option is true, recursively drop all objects within
   * the schema.
   *
   * <p>If the catalog implementation does not support this operation, it may throw {@link
   * UnsupportedOperationException}.
   *
   * @param schemaName The name of the schema.
   * @param cascade If true, recursively drop all objects within the schema.
   * @return True if the schema exists and is dropped successfully, false if the schema doesn't
   *     exist.
   * @throws NonEmptySchemaException If the schema is not empty and cascade is false.
   */
  boolean dropSchema(String schemaName, boolean cascade) throws NonEmptySchemaException;
}
