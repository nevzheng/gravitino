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
package org.apache.gravitino;

import java.time.Instant;
import javax.annotation.Nullable;
import org.apache.gravitino.annotation.Evolving;

/**
 * Describes one retained deletion generation of a metadata entity.
 *
 * <p>A deleted entity is an immutable read precondition, not a lease. Clients should read the exact
 * generation immediately before attempting recovery and pass its opaque {@link #etag()} back as a
 * strong HTTP {@code If-Match} precondition. The server remains authoritative for whether the
 * generation can still be restored.
 */
@Evolving
public interface DeletedEntity {

  /**
   * Returns whether this representation describes deleted metadata.
   *
   * @return always {@code true} for a valid deleted-entity representation
   */
  boolean deleted();

  /**
   * Returns the immutable metadata entity ID.
   *
   * @return immutable entity ID
   */
  String id();

  /**
   * Returns the deletion-generation ID used for diagnostics.
   *
   * <p>Clients must select recovery targets by {@link #id()}, not by this value.
   *
   * @return deletion-generation ID
   */
  String deletionId();

  /**
   * Returns the entity name captured by this deletion generation.
   *
   * @return entity name
   */
  String name();

  /**
   * Returns the metadata entity type.
   *
   * @return recovery entity type
   */
  RecoveryEntityType type();

  /**
   * Returns the deletion time.
   *
   * @return deletion time
   */
  Instant deletedAt();

  /**
   * Returns the retention expiry time.
   *
   * @return retention expiry time
   */
  Instant expiresAt();

  /**
   * Returns the principal that deleted the entity, when recorded.
   *
   * @return deleting principal, or {@code null} when unavailable
   */
  @Nullable
  String deletedBy();

  /**
   * Returns the metadata version captured by the deletion, when the entity is versioned.
   *
   * @return captured metadata version, or {@code null} for an unversioned entity
   */
  @Nullable
  Long version();

  /**
   * Returns the opaque, unquoted strong ETag token for this representation.
   *
   * @return opaque ETag token
   */
  String etag();

  /**
   * Returns whether this is the newest retained deletion generation for its name.
   *
   * @return {@code true} when this is the newest generation for the name
   */
  boolean latestForName();

  /**
   * Returns whether the server currently considers this generation eligible for recovery.
   *
   * @return {@code true} when the generation is currently recoverable
   */
  boolean restorable();

  /**
   * Returns the stable reason this generation is not currently recoverable.
   *
   * @return non-recoverability reason, or {@code null} when {@link #restorable()} is {@code true}
   */
  @Nullable
  String reason();
}
