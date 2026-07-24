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

/** The durable state of a recoverable entity deletion. */
public enum DeletionState {
  /** The entity is deleted and remains eligible for restore until its retention window expires. */
  DELETED,

  /** A restore operation has claimed the deletion generation. */
  RESTORING,

  /** The deletion generation was restored successfully. */
  RESTORED,

  /** Irreversible cleanup has claimed the deletion generation. */
  PURGING,

  /** Irreversible cleanup completed for the deletion generation. */
  PURGED;

  /**
   * Parses a deletion state without making callers depend on enum case.
   *
   * @param value wire or persisted value
   * @return parsed deletion state
   */
  @JsonCreator
  public static DeletionState fromValue(String value) {
    return DeletionState.valueOf(value.toUpperCase(Locale.ROOT));
  }

  /**
   * Returns the stable uppercase value used by the recovery state machine.
   *
   * @return uppercase deletion-state value
   */
  @JsonValue
  public String value() {
    return name();
  }
}
