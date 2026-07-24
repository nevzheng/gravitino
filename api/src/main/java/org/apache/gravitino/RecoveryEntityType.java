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

/** Identifies a top-level API resource represented by the recoverable-deletion protocol. */
public enum RecoveryEntityType {
  /** A metalake resource. */
  METALAKE,

  /** A catalog resource. */
  CATALOG,

  /** A schema resource. */
  SCHEMA,

  /** A table resource. */
  TABLE,

  /** A view resource. */
  VIEW,

  /** A function resource. */
  FUNCTION,

  /** A fileset resource. */
  FILESET,

  /** A topic resource. */
  TOPIC,

  /** A model resource. */
  MODEL,

  /** A tag resource. */
  TAG,

  /** A policy resource. */
  POLICY,

  /** A user resource. */
  USER,

  /** A group resource. */
  GROUP,

  /** A role resource. */
  ROLE,

  /** A job-template resource. */
  JOB_TEMPLATE;

  /**
   * Parses a recovery entity type without making callers depend on enum case.
   *
   * @param value wire value
   * @return parsed recovery entity type
   */
  @JsonCreator
  public static RecoveryEntityType fromValue(String value) {
    return RecoveryEntityType.valueOf(value.toUpperCase(Locale.ROOT));
  }

  /**
   * Returns the stable lowercase wire value used by recovery representations.
   *
   * @return lowercase recovery entity type
   */
  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
