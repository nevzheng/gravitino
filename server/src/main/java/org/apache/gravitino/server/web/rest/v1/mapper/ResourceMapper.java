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
package org.apache.gravitino.server.web.rest.v1.mapper;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.Schema;
import org.apache.gravitino.rest.v1.model.CatalogResource;
import org.apache.gravitino.rest.v1.model.CatalogType;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.rest.v1.model.SchemaResource;

/** Maps internal parent metadata objects to the public Gravitino V1 wire contract. */
public final class ResourceMapper {

  /**
   * Maps an internal metalake to its V1 public resource representation.
   *
   * @param metalake internal metalake.
   * @return immutable V1 metalake resource.
   */
  public static MetalakeResource toResource(Metalake metalake) {
    Objects.requireNonNull(metalake, "metalake cannot be null");
    String name = metalake.name();
    return new MetalakeResource(
        String.format("metalakes/%s", name),
        name,
        metalake.comment(),
        publicProperties(metalake.properties()),
        mapAudit(metalake.auditInfo()));
  }

  /**
   * Maps an internal catalog to its V1 public resource representation.
   *
   * @param metalake parent metalake name.
   * @param catalog internal catalog.
   * @return immutable V1 catalog resource.
   */
  public static CatalogResource toResource(String metalake, Catalog catalog) {
    Objects.requireNonNull(metalake, "metalake cannot be null");
    Objects.requireNonNull(catalog, "catalog cannot be null");
    String name = catalog.name();
    return new CatalogResource(
        String.format("metalakes/%s/catalogs/%s", metalake, name),
        name,
        mapCatalogType(catalog.type()),
        catalog.provider(),
        catalog.comment(),
        publicProperties(catalog.properties()),
        mapAudit(catalog.auditInfo()));
  }

  /**
   * Maps an internal schema to its V1 public resource representation.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param schema internal schema.
   * @return immutable V1 schema resource.
   */
  public static SchemaResource toResource(String metalake, String catalog, Schema schema) {
    Objects.requireNonNull(metalake, "metalake cannot be null");
    Objects.requireNonNull(catalog, "catalog cannot be null");
    Objects.requireNonNull(schema, "schema cannot be null");
    String name = schema.name();
    return new SchemaResource(
        String.format("metalakes/%s/catalogs/%s/schemas/%s", metalake, catalog, name),
        name,
        schema.comment(),
        publicProperties(schema.properties()),
        mapAudit(schema.auditInfo()));
  }

  @Nullable
  private static org.apache.gravitino.rest.v1.model.Audit mapAudit(@Nullable Audit audit) {
    if (audit == null) {
      return null;
    }
    String creator = audit.creator();
    Instant createTime = audit.createTime();
    String lastModifier = audit.lastModifier();
    Instant lastModifiedTime = audit.lastModifiedTime();
    if (creator == null && createTime == null && lastModifier == null && lastModifiedTime == null) {
      return null;
    }
    return new org.apache.gravitino.rest.v1.model.Audit(
        creator, createTime, lastModifier, lastModifiedTime);
  }

  private static CatalogType mapCatalogType(Catalog.Type type) {
    Objects.requireNonNull(type, "catalog type cannot be null");
    try {
      return CatalogType.valueOf(type.name());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported V1 catalog type: " + type, exception);
    }
  }

  private static Map<String, String> publicProperties(@Nullable Map<String, String> properties) {
    if (properties == null || properties.isEmpty()) {
      return Collections.emptyMap();
    }
    TreeMap<String, String> result = new TreeMap<>();
    properties.forEach(
        (key, value) -> {
          if (key != null && value != null) {
            result.put(key, value);
          }
        });
    return Collections.unmodifiableMap(result);
  }

  private ResourceMapper() {}
}
