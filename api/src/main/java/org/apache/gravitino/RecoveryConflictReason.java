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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Identifies the stable reason that a recoverable-deletion operation conflicts with state. */
public enum RecoveryConflictReason {
  /** A newer deletion generation exists for the same name. */
  NOT_LATEST_TOMBSTONE,

  /** The immutable entity ID is active or identifies different logical state. */
  ENTITY_ID_REUSED,

  /** A different live entity occupies the deleted entity's original name. */
  NAME_OCCUPIED,

  /** A different live entity occupies a retained external identity or execution identifier. */
  EXTERNAL_ID_OCCUPIED,

  /** An immutable parent is missing, deleted, or replaced. */
  PARENT_CHANGED,

  /** Irreversible cleanup has already claimed the deletion generation. */
  PURGE_IN_PROGRESS,

  /** The tombstone predates complete deletion records and cannot be restored safely. */
  LEGACY_TOMBSTONE,

  /** The recorded deletion generation is incomplete or internally inconsistent. */
  INCOMPLETE_GENERATION;

  /**
   * Parses a recovery conflict reason without making callers depend on enum case.
   *
   * @param value The wire or persisted value.
   * @return The parsed recovery conflict reason.
   */
  @JsonCreator
  public static RecoveryConflictReason fromValue(String value) {
    return RecoveryConflictReason.valueOf(value.toUpperCase(Locale.ROOT));
  }

  /**
   * Returns the stable uppercase wire value used by the recovery API.
   *
   * @return The uppercase recovery-conflict reason.
   */
  @JsonValue
  public String value() {
    return name();
  }
}
