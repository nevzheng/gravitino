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
package org.apache.gravitino.file;

import static org.apache.gravitino.file.Fileset.LOCATION_NAME_UNKNOWN;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.FilesetAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchFilesetException;
import org.apache.gravitino.exceptions.NoSuchLocationNameException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.file.FilesetChange.RenameFileset;

/**
 * The FilesetCatalog interface defines the public API for managing fileset objects in a schema. If
 * the catalog implementation supports fileset objects, it should implement this interface.
 */
@Evolving
public interface FilesetCatalog {

  /**
   * List the filesets in a schema namespace from the catalog.
   *
   * @param namespace A schema namespace.
   * @return An array of fileset identifiers in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  NameIdentifier[] listFilesets(Namespace namespace) throws NoSuchSchemaException;

  /**
   * Lists retained deletion generations for filesets in a namespace.
   *
   * <p>The optional filters select an exact fileset name, immutable fileset ID, or both. A returned
   * generation describes Gravitino metadata only; it does not assert that a storage location or any
   * files still exist.
   *
   * <p>Implementations that support recoverable fileset deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param namespace A fileset namespace.
   * @param name An exact fileset-name filter, or {@code null} to include every name.
   * @param id An exact immutable fileset-ID filter, or {@code null} to include every ID.
   * @return The retained fileset deletion generations matching the filters.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws IllegalArgumentException If the namespace or a supplied filter is invalid.
   * @throws UnsupportedOperationException If the catalog does not support recoverable fileset
   *     deletion.
   */
  default DeletedEntity[] listDeletedFilesets(
      Namespace namespace, @Nullable String name, @Nullable String id)
      throws NoSuchSchemaException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedFilesets not supported.");
  }

  /**
   * Load fileset metadata by {@link NameIdentifier} from the catalog.
   *
   * @param ident A fileset identifier.
   * @return The fileset metadata.
   * @throws NoSuchFilesetException If the fileset does not exist.
   */
  Fileset loadFileset(NameIdentifier ident) throws NoSuchFilesetException;

  /**
   * Loads one exact retained deletion generation for a fileset.
   *
   * <p>The fileset name in {@code ident} and immutable {@code id} must identify the same deleted
   * fileset. The returned generation supplies the strong optimistic precondition required by {@link
   * #restoreFileset(NameIdentifier, DeletedEntity)}.
   *
   * <p>Implementations that support recoverable fileset deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A fileset identifier.
   * @param id The immutable fileset ID.
   * @return The exact retained fileset deletion generation.
   * @throws NoSuchSchemaException If the parent schema does not exist.
   * @throws IllegalArgumentException If the identifier or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested fileset path.
   * @throws UnsupportedOperationException If the catalog does not support recoverable fileset
   *     deletion.
   */
  default DeletedEntity loadDeletedFileset(NameIdentifier ident, String id)
      throws NoSuchSchemaException, TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedFileset not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino fileset metadata.
   *
   * <p>This operation is metadata-only. It neither validates nor recreates storage locations, and
   * it does not restore files removed when a managed fileset was dropped. The generation must come
   * from an exact deleted-fileset read and must match the identifier's name and resource type.
   * Replaying a previously accepted generation is idempotent while the server can still prove that
   * exact restore.
   *
   * <p>If a request fails with {@link TombstoneChangedException}, callers must read the same
   * fileset path and immutable ID again before deciding whether to retry; clients must never
   * silently substitute a different ID or generation. An unknown transport outcome may be replayed
   * with the same generation. A recovery conflict or expired generation is not retryable as-is.
   *
   * <p>Implementations that support recoverable fileset deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param ident A fileset identifier.
   * @param generation The exact retained deletion generation to restore.
   * @return The restored fileset metadata.
   * @throws IllegalArgumentException If the identifier, generation type, name, ID, or ETag is
   *     invalid or inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested fileset path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If the catalog does not support recoverable fileset
   *     deletion.
   */
  default Fileset restoreFileset(NameIdentifier ident, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreFileset not supported.");
  }

  /**
   * Check if a fileset exists using an {@link NameIdentifier} from the catalog.
   *
   * @param ident A fileset identifier.
   * @return true If the fileset exists, false otherwise.
   */
  default boolean filesetExists(NameIdentifier ident) {
    try {
      loadFileset(ident);
      return true;
    } catch (NoSuchFilesetException e) {
      return false;
    }
  }

  /**
   * Create a fileset metadata with a default location in the catalog.
   *
   * <p>If the type of the fileset object is "MANAGED", the underlying storageLocation can be null,
   * and Gravitino will manage the storage location based on the location of the schema.
   *
   * <p>If the type of the fileset object is "EXTERNAL", the underlying storageLocation must be set.
   *
   * @param ident A fileset identifier.
   * @param comment The comment of the fileset.
   * @param type The type of the fileset.
   * @param storageLocation The storage location of the fileset.
   * @param properties The properties of the fileset.
   * @return The created fileset metadata
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws FilesetAlreadyExistsException If the fileset already exists.
   */
  default Fileset createFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      String storageLocation,
      Map<String, String> properties)
      throws NoSuchSchemaException, FilesetAlreadyExistsException {
    return createMultipleLocationFileset(
        ident,
        comment,
        type,
        storageLocation == null
            ? ImmutableMap.of()
            : ImmutableMap.of(LOCATION_NAME_UNKNOWN, storageLocation),
        properties);
  }

  /**
   * Create a fileset metadata with multiple storage locations in the catalog.
   *
   * @param ident A fileset identifier.
   * @param comment The comment of the fileset.
   * @param type The type of the fileset.
   * @param storageLocations The location names and storage locations of the fileset.
   * @param properties The properties of the fileset.
   * @return The created fileset metadata
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws FilesetAlreadyExistsException If the fileset already exists.
   */
  default Fileset createMultipleLocationFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      Map<String, String> storageLocations,
      Map<String, String> properties)
      throws NoSuchSchemaException, FilesetAlreadyExistsException {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * Apply the {@link FilesetChange change} to a fileset in the catalog.
   *
   * <p>Implementation may reject the change. If any change is rejected, no changes should be
   * applied to the fileset.
   *
   * <p>The {@link RenameFileset} change will only update the fileset name, the underlying storage
   * location for managed fileset will not be renamed.
   *
   * @param ident A fileset identifier.
   * @param changes The changes to apply to the fileset.
   * @return The altered fileset metadata.
   * @throws NoSuchFilesetException If the fileset does not exist.
   * @throws IllegalArgumentException If the change is rejected by the implementation.
   */
  Fileset alterFileset(NameIdentifier ident, FilesetChange... changes)
      throws NoSuchFilesetException, IllegalArgumentException;

  /**
   * Drop a fileset from the catalog.
   *
   * <p>The underlying files will be deleted if this fileset type is managed, otherwise, only the
   * metadata will be dropped.
   *
   * @param ident A fileset identifier.
   * @return true If the fileset is dropped, false the fileset did not exist.
   */
  boolean dropFileset(NameIdentifier ident);

  /**
   * Get the actual location of a file or directory based on the default storage location of Fileset
   * and the sub path.
   *
   * @param ident A fileset identifier.
   * @param subPath The sub path to the file or directory.
   * @return The actual location of the file or directory.
   * @throws NoSuchFilesetException If the fileset does not exist.
   */
  default String getFileLocation(NameIdentifier ident, String subPath)
      throws NoSuchFilesetException {
    return getFileLocation(ident, subPath, null);
  }

  /**
   * Get the actual location of a file or directory based on the storage location of Fileset and the
   * sub path by the location name.
   *
   * @param ident A fileset identifier.
   * @param subPath The sub path to the file or directory.
   * @param locationName The location name. If null, the default location will be used.
   * @return The actual location of the file or directory.
   * @throws NoSuchFilesetException If the fileset does not exist.
   * @throws NoSuchLocationNameException If the location name does not exist.
   */
  default String getFileLocation(NameIdentifier ident, String subPath, String locationName)
      throws NoSuchFilesetException, NoSuchLocationNameException {
    throw new UnsupportedOperationException("Not implemented");
  }
}
