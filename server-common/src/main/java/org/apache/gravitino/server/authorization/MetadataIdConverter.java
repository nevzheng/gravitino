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

package org.apache.gravitino.server.authorization;

import java.util.Optional;
import java.util.Set;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.annotation.LegacyEnvAccess;

/**
 * Static ID lookups over the process environment.
 *
 * <p>Each method builds a {@link MetadataIdResolver} over the catalog manager and entity store of
 * {@code GravitinoEnv.getInstance()} and delegates to it.
 */
public class MetadataIdConverter {

  private MetadataIdConverter() {}

  /**
   * Converts the given metadata object to metadata id.
   *
   * @param metadataObject The metadata object to convert.
   * @param metalake The metalake name.
   * @return The metadata id, empty if metadata does not exist.
   */
  public static Optional<Long> getID(MetadataObject metadataObject, String metalake) {
    return processResolver().getID(metadataObject, metalake);
  }

  /**
   * Loads an ID using an object already normalized by {@link #normalizeMetadataObject}.
   *
   * @param metadataObject the previously normalized metadata object
   * @param metalake the metalake name
   * @return the metadata ID, or empty if the entity does not exist
   */
  public static Optional<Long> getIdForNormalizedObject(
      MetadataObject metadataObject, String metalake) {
    return processResolver().getIdForNormalizedObject(metadataObject, metalake);
  }

  /**
   * Normalizes a metadata object's name using the same catalog rules as ID resolution.
   *
   * @param metadataObject the object whose name will be normalized
   * @param metalake the metalake name
   * @return the normalized object, suitable for name-to-ID cache keys
   */
  public static MetadataObject normalizeMetadataObject(
      MetadataObject metadataObject, String metalake) {
    return processResolver().normalizeMetadataObject(metadataObject, metalake);
  }

  /**
   * Returns the immutable set of metadata types with catalog-scoped normalization rules.
   *
   * @return the types covered by the production capability mapping
   */
  public static Set<MetadataObject.Type> catalogScopedTypes() {
    return MetadataIdResolver.catalogScopedTypes();
  }

  @LegacyEnvAccess
  private static MetadataIdResolver processResolver() {
    GravitinoEnv env = GravitinoEnv.getInstance();
    return new MetadataIdResolver(env::catalogManager, env::entityStore);
  }
}
