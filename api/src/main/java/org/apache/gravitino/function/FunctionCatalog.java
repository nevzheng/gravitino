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
package org.apache.gravitino.function;

import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.FunctionAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchFunctionException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;

/** The FunctionCatalog interface defines the public API for managing functions in a schema. */
@Evolving
public interface FunctionCatalog {

  /**
   * List the functions in a namespace from the catalog.
   *
   * @param namespace A namespace.
   * @return An array of function identifiers in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  NameIdentifier[] listFunctions(Namespace namespace) throws NoSuchSchemaException;

  /**
   * Lists retained deletion generations for functions in a namespace.
   *
   * <p>The optional filters select an exact function name, immutable function ID, or both. A
   * returned generation describes Gravitino metadata only; it does not assert that referenced
   * downstream implementations still exist.
   *
   * <p>Implementations that support recoverable function deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param namespace A function namespace.
   * @param name An exact function-name filter, or {@code null} to include every name.
   * @param id An exact immutable function-ID filter, or {@code null} to include every ID.
   * @return The retained function deletion generations matching the filters.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws IllegalArgumentException If the namespace or a supplied filter is invalid.
   * @throws UnsupportedOperationException If the catalog does not support recoverable function
   *     deletion.
   */
  default DeletedEntity[] listDeletedFunctions(
      Namespace namespace, @Nullable String name, @Nullable String id)
      throws NoSuchSchemaException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedFunctions not supported.");
  }

  /**
   * List the functions with details in a namespace from the catalog.
   *
   * @param namespace A namespace.
   * @return An array of functions in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  Function[] listFunctionInfos(Namespace namespace) throws NoSuchSchemaException;

  /**
   * Get a function by {@link NameIdentifier} from the catalog. The identifier only contains the
   * schema and function name. A function may include multiple definitions (overloads) in the
   * result.
   *
   * @param ident A function identifier.
   * @return The function with the given name.
   * @throws NoSuchFunctionException If the function does not exist.
   */
  Function getFunction(NameIdentifier ident) throws NoSuchFunctionException;

  /**
   * Loads one exact retained deletion generation for a function.
   *
   * <p>The function name in {@code ident} and immutable {@code id} must identify the same deleted
   * function. The returned generation supplies the strong optimistic precondition required by
   * {@link #restoreFunction(NameIdentifier, DeletedEntity)}.
   *
   * <p>Implementations that support recoverable function deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A function identifier.
   * @param id The immutable function ID.
   * @return The exact retained function deletion generation.
   * @throws NoSuchSchemaException If the parent schema does not exist.
   * @throws IllegalArgumentException If the identifier or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested function path.
   * @throws UnsupportedOperationException If the catalog does not support recoverable function
   *     deletion.
   */
  default DeletedEntity loadDeletedFunction(NameIdentifier ident, String id)
      throws NoSuchSchemaException, TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedFunction not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino function metadata.
   *
   * <p>This operation is metadata-only. It neither validates nor recreates downstream function
   * implementations. The generation must come from an exact deleted-function read and must match
   * the identifier's name and resource type. Replaying a previously accepted generation is
   * idempotent while the server can still prove that exact restore.
   *
   * <p>If a request fails with {@link TombstoneChangedException}, callers must read the same
   * function path and immutable ID again before deciding whether to retry; clients must never
   * silently substitute a different ID or generation. An unknown transport outcome may be replayed
   * with the same generation. A recovery conflict or expired generation is not retryable as-is.
   *
   * <p>Implementations that support recoverable function deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A function identifier.
   * @param generation The exact retained deletion generation to restore.
   * @return The restored function metadata.
   * @throws IllegalArgumentException If the identifier, generation type, name, ID, or ETag is
   *     invalid or inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested function path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If the catalog does not support recoverable function
   *     deletion.
   */
  default Function restoreFunction(NameIdentifier ident, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreFunction not supported.");
  }

  /**
   * Check if a function with the given name exists in the catalog.
   *
   * @param ident The function identifier.
   * @return True if the function exists, false otherwise.
   */
  default boolean functionExists(NameIdentifier ident) {
    try {
      getFunction(ident);
      return true;
    } catch (NoSuchFunctionException e) {
      return false;
    }
  }

  /**
   * Register a function with one or more definitions (overloads). Each definition contains its own
   * return type (for scalar/aggregate functions) or return columns (for table-valued functions).
   *
   * @param ident The function identifier.
   * @param comment The optional function comment.
   * @param functionType The function type (SCALAR, AGGREGATE, or TABLE).
   * @param deterministic Whether the function is deterministic.
   * @param definitions The function definitions, each containing parameters, return type/columns,
   *     and implementations.
   * @return The registered function.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws FunctionAlreadyExistsException If the function already exists.
   */
  Function registerFunction(
      NameIdentifier ident,
      String comment,
      FunctionType functionType,
      boolean deterministic,
      FunctionDefinition[] definitions)
      throws NoSuchSchemaException, FunctionAlreadyExistsException;

  /**
   * Applies {@link FunctionChange changes} to a function in the catalog.
   *
   * <p>Implementations may reject the changes. If any change is rejected, no changes should be
   * applied to the function.
   *
   * @param ident the {@link NameIdentifier} instance of the function to alter.
   * @param changes the several {@link FunctionChange} instances to apply to the function.
   * @return the updated {@link Function} instance.
   * @throws NoSuchFunctionException If the function does not exist.
   * @throws IllegalArgumentException If the change is rejected by the implementation.
   */
  Function alterFunction(NameIdentifier ident, FunctionChange... changes)
      throws NoSuchFunctionException, IllegalArgumentException;

  /**
   * Drop a function by name.
   *
   * @param ident The name identifier of the function.
   * @return True if the function is deleted, false if the function does not exist.
   */
  boolean dropFunction(NameIdentifier ident);
}
