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
package org.apache.gravitino.server.web.rest;

import javax.annotation.Nullable;
import javax.ws.rs.core.EntityTag;
import org.apache.gravitino.exceptions.PreconditionRequiredException;

/** Shared parsing for recoverable-deletion REST request preconditions. */
final class RecoveryRequestUtils {

  private RecoveryRequestUtils() {}

  /** Parses an optional positive decimal entity identifier. */
  @Nullable
  static Long parsePositiveEntityId(@Nullable String id) {
    if (id == null) {
      return null;
    }
    try {
      long parsed = Long.parseLong(id);
      if (parsed <= 0) {
        throw new IllegalArgumentException("id must be a positive decimal string");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("id must be a positive decimal string", e);
    }
  }

  /** Parses exactly one strong {@code If-Match} entity tag. */
  static String parseStrongIfMatch(@Nullable String ifMatch, String entityType) {
    if (ifMatch == null) {
      throw new PreconditionRequiredException(
          "If-Match is required when restoring a deleted %s", entityType);
    }

    String value = ifMatch.trim();
    if (value.length() < 2
        || value.charAt(0) != '"'
        || value.charAt(value.length() - 1) != '"'
        || value.substring(1, value.length() - 1).indexOf('"') >= 0) {
      throw new IllegalArgumentException("If-Match must contain exactly one strong entity tag");
    }

    try {
      EntityTag entityTag = EntityTag.valueOf(value);
      if (entityTag.isWeak()) {
        throw new IllegalArgumentException("If-Match must contain a strong entity tag");
      }
      return entityTag.getValue();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("If-Match must contain exactly one strong entity tag", e);
    }
  }
}
