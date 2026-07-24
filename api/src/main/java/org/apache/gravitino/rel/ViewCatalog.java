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
package org.apache.gravitino.rel;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.annotation.Unstable;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchViewException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.exceptions.ViewAlreadyExistsException;

/**
 * The ViewCatalog interface defines the public API for managing views in a schema. If the catalog
 * implementation supports views, it must implement this interface.
 */
@Unstable
public interface ViewCatalog {

  /**
   * List the views in a namespace from the catalog.
   *
   * <p>This is a default method that throws {@link UnsupportedOperationException}. Catalog
   * implementations that support view management should override this method.
   *
   * @param namespace A namespace.
   * @return An array of view identifiers in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  default NameIdentifier[] listViews(Namespace namespace) throws NoSuchSchemaException {
    throw new UnsupportedOperationException("listViews is not supported");
  }

  /**
   * Lists retained deletion generations for views in a namespace.
   *
   * <p>The optional filters select an exact view name, immutable view ID, or both. A returned
   * generation describes Gravitino metadata only; it does not assert that downstream objects
   * referenced by the view still exist.
   *
   * <p>Implementations that support recoverable view deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param namespace A view namespace.
   * @param name An exact view-name filter, or {@code null} to include every name.
   * @param id An exact immutable view-ID filter, or {@code null} to include every ID.
   * @return The retained view deletion generations matching the filters.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws IllegalArgumentException If the namespace or a supplied filter is invalid.
   * @throws UnsupportedOperationException If the catalog does not support recoverable view
   *     deletion.
   */
  default DeletedEntity[] listDeletedViews(
      Namespace namespace, @Nullable String name, @Nullable String id)
      throws NoSuchSchemaException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedViews not supported.");
  }

  /**
   * Load view metadata by {@link NameIdentifier} from the catalog.
   *
   * @param ident A view identifier.
   * @return The view metadata.
   * @throws NoSuchViewException If the view does not exist.
   */
  View loadView(NameIdentifier ident) throws NoSuchViewException;

  /**
   * Loads one exact retained deletion generation for a view.
   *
   * <p>The view name in {@code ident} and immutable {@code id} must identify the same deleted view.
   * The returned generation supplies the strong optimistic precondition required by {@link
   * #restoreView(NameIdentifier, DeletedEntity)}.
   *
   * <p>Implementations that support recoverable view deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A view identifier.
   * @param id The immutable view ID.
   * @return The exact retained view deletion generation.
   * @throws NoSuchSchemaException If the parent schema does not exist.
   * @throws IllegalArgumentException If the identifier or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested view path.
   * @throws UnsupportedOperationException If the catalog does not support recoverable view
   *     deletion.
   */
  default DeletedEntity loadDeletedView(NameIdentifier ident, String id)
      throws NoSuchSchemaException, TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedView not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino view metadata.
   *
   * <p>This operation is metadata-only. It neither validates nor recreates downstream objects
   * referenced by the view. The generation must come from an exact deleted-view read and must match
   * the identifier's name and resource type. Replaying a previously accepted generation is
   * idempotent while the server can still prove that exact restore.
   *
   * <p>If a request fails with {@link TombstoneChangedException}, callers must read the same view
   * path and immutable ID again before deciding whether to retry; clients must never silently
   * substitute a different ID or generation. An unknown transport outcome may be replayed with the
   * same generation. A recovery conflict or expired generation is not retryable as-is.
   *
   * <p>Implementations that support recoverable view deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A view identifier.
   * @param generation The exact retained deletion generation to restore.
   * @return The restored view metadata.
   * @throws IllegalArgumentException If the identifier, generation type, name, ID, or ETag is
   *     invalid or inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested view path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If the catalog does not support recoverable view
   *     deletion.
   */
  default View restoreView(NameIdentifier ident, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreView not supported.");
  }

  /**
   * Check if a view exists using its identifier.
   *
   * @param ident A view identifier.
   * @return true If the view exists, false otherwise.
   */
  default boolean viewExists(NameIdentifier ident) {
    try {
      return loadView(ident) != null;
    } catch (NoSuchViewException e) {
      return false;
    }
  }

  /**
   * Create a view in the catalog.
   *
   * <p>This is a default method that throws {@link UnsupportedOperationException}. Catalog
   * implementations that support view management should override this method.
   *
   * @param ident A view identifier.
   * @param comment The view comment, may be {@code null}.
   * @param columns The output columns of the view.
   * @param representations The representations of the view. At least one representation is
   *     expected.
   * @param defaultCatalog The default catalog used to resolve unqualified identifiers referenced by
   *     the view definition, or {@code null} if not set.
   * @param defaultSchema The default schema used to resolve unqualified identifiers referenced by
   *     the view definition, or {@code null} if not set.
   * @param properties The view properties.
   * @return The created view metadata.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws ViewAlreadyExistsException If the view already exists.
   */
  default View createView(
      NameIdentifier ident,
      @Nullable String comment,
      Column[] columns,
      Representation[] representations,
      @Nullable String defaultCatalog,
      @Nullable String defaultSchema,
      Map<String, String> properties)
      throws NoSuchSchemaException, ViewAlreadyExistsException {
    throw new UnsupportedOperationException("createView is not supported");
  }

  /**
   * Apply the {@link ViewChange changes} to a view in the catalog.
   *
   * <p>Implementations may reject the change. If any change is rejected, no changes should be
   * applied to the view.
   *
   * <p>This is a default method that throws {@link UnsupportedOperationException}. Catalog
   * implementations that support view management should override this method.
   *
   * @param ident A view identifier.
   * @param changes View changes to apply to the view.
   * @return The updated view metadata.
   * @throws NoSuchViewException If the view does not exist.
   * @throws IllegalArgumentException If the change is rejected by the implementation.
   */
  default View alterView(NameIdentifier ident, ViewChange... changes)
      throws NoSuchViewException, IllegalArgumentException {
    throw new UnsupportedOperationException("alterView is not supported");
  }

  /**
   * Drop a view from the catalog.
   *
   * <p>This is a default method that throws {@link UnsupportedOperationException}. Catalog
   * implementations that support view management should override this method.
   *
   * @param ident A view identifier.
   * @return True if the view is dropped, false if the view does not exist.
   */
  default boolean dropView(NameIdentifier ident) {
    throw new UnsupportedOperationException("dropView is not supported");
  }
}
