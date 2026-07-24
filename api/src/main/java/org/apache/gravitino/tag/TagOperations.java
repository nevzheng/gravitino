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

package org.apache.gravitino.tag;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchTagException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TagAlreadyExistsException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;

/**
 * Interface for supporting global tag operations. This interface will provide tag listing, getting,
 * creating, and other tag operations under a metalake. This interface will be mixed with
 * GravitinoMetalake or GravitinoClient to provide tag operations.
 */
@Evolving
public interface TagOperations {

  /**
   * List all the tag names under a metalake.
   *
   * @return The list of tag names.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  String[] listTags() throws NoSuchMetalakeException;

  /**
   * List all the tags with detailed information under a metalake.
   *
   * @return The list of tags.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  Tag[] listTagsInfo() throws NoSuchMetalakeException;

  /**
   * Lists retained deletion generations for tags in this metalake.
   *
   * <p>The optional filters select an exact tag name, immutable tag ID, or both. A returned
   * generation describes Gravitino metadata only; it does not assert that tag state in connectors
   * or external authorization systems can be recovered.
   *
   * <p>Implementations that support recoverable tag deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name An exact tag-name filter, or {@code null} to include every name.
   * @param id An exact immutable tag-ID filter, or {@code null} to include every ID.
   * @return The retained tag deletion generations matching the filters.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   * @throws IllegalArgumentException If a supplied filter is invalid.
   * @throws UnsupportedOperationException If recoverable tag deletion is not supported.
   */
  default DeletedEntity[] listDeletedTags(@Nullable String name, @Nullable String id)
      throws NoSuchMetalakeException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedTags not supported.");
  }

  /**
   * Get a tag by its name under a metalake.
   *
   * @param name The name of the tag.
   * @return The tag.
   * @throws NoSuchTagException If the tag does not exist.
   */
  Tag getTag(String name) throws NoSuchTagException;

  /**
   * Loads one exact retained deletion generation for a tag.
   *
   * <p>The tag name and immutable {@code id} must identify the same deleted tag. The returned
   * generation supplies the strong optimistic precondition required by {@link #restoreTag(String,
   * DeletedEntity)}.
   *
   * <p>Implementations that support recoverable tag deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name The tag name.
   * @param id The immutable tag ID.
   * @return The exact retained tag deletion generation.
   * @throws IllegalArgumentException If the name or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested tag path.
   * @throws UnsupportedOperationException If recoverable tag deletion is not supported.
   */
  default DeletedEntity loadDeletedTag(String name, String id)
      throws TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedTag not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino tag metadata.
   *
   * <p>This operation restores retained Gravitino tag metadata and internal associations only. It
   * does not validate, recreate, or reconcile state in connectors or external authorization
   * systems. The generation must come from an exact deleted-tag read and must match the requested
   * name and resource type. Replaying a previously accepted generation is idempotent while the
   * server can still prove that exact restore.
   *
   * <p>If a request fails with {@link TombstoneChangedException}, callers must read the same tag
   * path and immutable ID again before deciding whether to retry; clients must never silently
   * substitute a different ID or generation. An unknown transport outcome may be replayed with the
   * same generation. A recovery conflict or expired generation is not retryable as-is.
   *
   * <p>Implementations that support recoverable tag deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name The tag name.
   * @param generation The exact retained deletion generation to restore.
   * @return The restored tag metadata.
   * @throws IllegalArgumentException If the name, generation type, ID, or ETag is invalid or
   *     inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested tag path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If recoverable tag deletion is not supported.
   */
  default Tag restoreTag(String name, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreTag not supported.");
  }

  /**
   * Create a tag under a metalake.
   *
   * @param name The name of the tag.
   * @param comment The comment of the tag.
   * @param properties The properties of the tag.
   * @return The created tag.
   * @throws TagAlreadyExistsException If the tag already exists.
   */
  Tag createTag(String name, String comment, Map<String, String> properties)
      throws TagAlreadyExistsException;

  /**
   * Alter a tag under a metalake.
   *
   * @param name The name of the tag.
   * @param changes The changes to apply to the tag.
   * @return The altered tag.
   * @throws NoSuchTagException If the tag does not exist.
   * @throws IllegalArgumentException If the changes cannot be applied to the tag.
   * @throws TagAlreadyExistsException If a tag with the new name already exists.
   */
  Tag alterTag(String name, TagChange... changes)
      throws NoSuchTagException, IllegalArgumentException;

  /**
   * Delete a tag under a metalake.
   *
   * @param name The name of the tag.
   * @return True if the tag is deleted, false if the tag does not exist.
   */
  boolean deleteTag(String name);
}
