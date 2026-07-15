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
package org.apache.gravitino.server.web.rest.v1.error;

import java.util.Objects;
import javax.annotation.Nullable;

/** Request-specific public context used when translating an internal V1 API failure. */
public final class V1ErrorContext {

  private static final V1ErrorContext EMPTY = new V1ErrorContext(null, null, null, null, false);

  @Nullable private final String metalakeResourceName;
  @Nullable private final String catalogResourceName;
  @Nullable private final String schemaResourceName;
  @Nullable private final String tableResourceName;
  private final boolean safeToRetry;

  private V1ErrorContext(
      @Nullable String metalakeResourceName,
      @Nullable String catalogResourceName,
      @Nullable String schemaResourceName,
      @Nullable String tableResourceName,
      boolean safeToRetry) {
    this.metalakeResourceName = metalakeResourceName;
    this.catalogResourceName = catalogResourceName;
    this.schemaResourceName = schemaResourceName;
    this.tableResourceName = tableResourceName;
    this.safeToRetry = safeToRetry;
  }

  /**
   * Returns context with no public resource detail.
   *
   * @return empty error context.
   */
  public static V1ErrorContext empty() {
    return EMPTY;
  }

  /**
   * Returns context for a safe, idempotent table-read request.
   *
   * @param resourceName the canonical table resource name.
   * @return the table-read error context.
   */
  public static V1ErrorContext tableRead(String resourceName) {
    String name = Objects.requireNonNull(resourceName, "resourceName");
    return new V1ErrorContext(name, name, name, name, true);
  }

  /**
   * Returns table-read context with canonical resource names at every hierarchy level.
   *
   * @param metalake the requested metalake.
   * @param catalog the requested catalog.
   * @param schema the requested schema.
   * @param table the requested table.
   * @return the table-read error context.
   */
  public static V1ErrorContext tableRead(
      String metalake, String catalog, String schema, String table) {
    String checkedMetalake = Objects.requireNonNull(metalake, "metalake");
    String checkedCatalog = Objects.requireNonNull(catalog, "catalog");
    String checkedSchema = Objects.requireNonNull(schema, "schema");
    String checkedTable = Objects.requireNonNull(table, "table");
    String metalakeResource = "metalakes/" + checkedMetalake;
    String catalogResource = metalakeResource + "/catalogs/" + checkedCatalog;
    String schemaResource = catalogResource + "/schemas/" + checkedSchema;
    return new V1ErrorContext(
        metalakeResource,
        catalogResource,
        schemaResource,
        schemaResource + "/tables/" + checkedTable,
        true);
  }

  /**
   * Returns the canonical public resource name at the supplied hierarchy level.
   *
   * @param resourceType upper-case public resource type.
   * @return the resource name, or {@code null} when no resource is safe to disclose.
   */
  @Nullable
  public String resourceName(String resourceType) {
    switch (Objects.requireNonNull(resourceType, "resourceType")) {
      case "METALAKE":
        return metalakeResourceName;
      case "CATALOG":
        return catalogResourceName;
      case "SCHEMA":
        return schemaResourceName;
      case "TABLE":
        return tableResourceName;
      default:
        throw new IllegalArgumentException("Unknown V1 resource type: " + resourceType);
    }
  }

  /**
   * Returns whether the originating request is safe to repeat without an idempotency key.
   *
   * @return whether an unchanged request is safe to retry.
   */
  public boolean safeToRetry() {
    return safeToRetry;
  }
}
