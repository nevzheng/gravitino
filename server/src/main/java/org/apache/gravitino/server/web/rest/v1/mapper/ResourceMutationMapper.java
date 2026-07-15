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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.rest.v1.model.CatalogResource;
import org.apache.gravitino.rest.v1.model.CatalogUpdateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.rest.v1.model.MetalakeUpdateRequest;
import org.apache.gravitino.rest.v1.model.SchemaResource;
import org.apache.gravitino.rest.v1.model.SchemaUpdateRequest;

/**
 * Maps V1 full desired-state parent-resource requests into current internal mutation primitives.
 */
public final class ResourceMutationMapper {

  /**
   * Builds the internal changes necessary to replace a metalake's V1 mutable state.
   *
   * @param current current public metalake representation.
   * @param desired complete desired V1 mutable state.
   * @return ordered internal changes, potentially empty for an unchanged resource.
   */
  public static MetalakeChange[] toChanges(
      MetalakeResource current, MetalakeUpdateRequest desired) {
    Objects.requireNonNull(current, "current cannot be null");
    Objects.requireNonNull(desired, "desired cannot be null");
    List<MetalakeChange> changes = new ArrayList<>();
    if (!Objects.equals(current.getComment(), desired.getComment())) {
      changes.add(MetalakeChange.updateComment(desired.getComment()));
    }
    addMetalakePropertyChanges(changes, current.getProperties(), desired.getProperties());
    return changes.toArray(new MetalakeChange[0]);
  }

  /**
   * Builds the internal changes necessary to replace a catalog's V1 mutable state.
   *
   * @param current current public catalog representation.
   * @param desired complete desired V1 mutable state.
   * @return ordered internal changes, potentially empty for an unchanged resource.
   */
  public static CatalogChange[] toChanges(CatalogResource current, CatalogUpdateRequest desired) {
    Objects.requireNonNull(current, "current cannot be null");
    Objects.requireNonNull(desired, "desired cannot be null");
    List<CatalogChange> changes = new ArrayList<>();
    if (!Objects.equals(current.getComment(), desired.getComment())) {
      changes.add(CatalogChange.updateComment(desired.getComment()));
    }
    addCatalogPropertyChanges(changes, current.getProperties(), desired.getProperties());
    return changes.toArray(new CatalogChange[0]);
  }

  /**
   * Builds the internal property changes necessary to replace a schema's V1 mutable state.
   *
   * <p>The current internal {@link SchemaChange} API does not provide a schema-comment mutation.
   * Callers must reject a differing desired comment before using this method.
   *
   * @param current current public schema representation.
   * @param desired complete desired V1 mutable state with the current comment.
   * @return ordered internal changes, potentially empty for an unchanged resource.
   */
  public static SchemaChange[] toChanges(SchemaResource current, SchemaUpdateRequest desired) {
    Objects.requireNonNull(current, "current cannot be null");
    Objects.requireNonNull(desired, "desired cannot be null");
    List<SchemaChange> changes = new ArrayList<>();
    addSchemaPropertyChanges(changes, current.getProperties(), desired.getProperties());
    return changes.toArray(new SchemaChange[0]);
  }

  private static void addMetalakePropertyChanges(
      List<MetalakeChange> changes, Map<String, String> current, Map<String, String> desired) {
    for (String key : changedOrAddedPropertyNames(current, desired)) {
      changes.add(MetalakeChange.setProperty(key, desired.get(key)));
    }
    for (String key : removedPropertyNames(current, desired)) {
      changes.add(MetalakeChange.removeProperty(key));
    }
  }

  private static void addCatalogPropertyChanges(
      List<CatalogChange> changes, Map<String, String> current, Map<String, String> desired) {
    for (String key : changedOrAddedPropertyNames(current, desired)) {
      changes.add(CatalogChange.setProperty(key, desired.get(key)));
    }
    for (String key : removedPropertyNames(current, desired)) {
      changes.add(CatalogChange.removeProperty(key));
    }
  }

  private static void addSchemaPropertyChanges(
      List<SchemaChange> changes, Map<String, String> current, Map<String, String> desired) {
    for (String key : changedOrAddedPropertyNames(current, desired)) {
      changes.add(SchemaChange.setProperty(key, desired.get(key)));
    }
    for (String key : removedPropertyNames(current, desired)) {
      changes.add(SchemaChange.removeProperty(key));
    }
  }

  private static TreeSet<String> changedOrAddedPropertyNames(
      Map<String, String> current, Map<String, String> desired) {
    TreeSet<String> result = new TreeSet<>();
    desired.forEach(
        (key, value) -> {
          if (!Objects.equals(current.get(key), value)) {
            result.add(key);
          }
        });
    return result;
  }

  private static TreeSet<String> removedPropertyNames(
      Map<String, String> current, Map<String, String> desired) {
    TreeSet<String> result = new TreeSet<>();
    current
        .keySet()
        .forEach(
            key -> {
              if (!desired.containsKey(key)) {
                result.add(key);
              }
            });
    return result;
  }

  private ResourceMutationMapper() {}
}
