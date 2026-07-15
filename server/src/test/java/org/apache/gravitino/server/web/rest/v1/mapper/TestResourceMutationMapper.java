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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.rest.v1.model.CatalogResource;
import org.apache.gravitino.rest.v1.model.CatalogType;
import org.apache.gravitino.rest.v1.model.CatalogUpdateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.rest.v1.model.MetalakeUpdateRequest;
import org.apache.gravitino.rest.v1.model.SchemaResource;
import org.apache.gravitino.rest.v1.model.SchemaUpdateRequest;
import org.junit.jupiter.api.Test;

/** Tests for mapping V1 complete desired state to existing parent mutation primitives. */
public class TestResourceMutationMapper {

  @Test
  public void testMetalakeDesiredStateProducesDeterministicCompleteMutation() {
    MetalakeResource current =
        new MetalakeResource(
            "metalakes/demo",
            "demo",
            "old comment",
            Map.of("change", "old", "remove", "old", "same", "same"),
            null);
    MetalakeUpdateRequest desired =
        new MetalakeUpdateRequest(
            "new comment", Map.of("add", "new", "change", "new", "same", "same"));

    assertArrayEquals(
        new MetalakeChange[] {
          MetalakeChange.updateComment("new comment"),
          MetalakeChange.setProperty("add", "new"),
          MetalakeChange.setProperty("change", "new"),
          MetalakeChange.removeProperty("remove")
        },
        ResourceMutationMapper.toChanges(current, desired));
  }

  @Test
  public void testCatalogDesiredStateProducesDeterministicCompleteMutation() {
    CatalogResource current =
        new CatalogResource(
            "metalakes/demo/catalogs/lakehouse",
            "lakehouse",
            CatalogType.RELATIONAL,
            "hive",
            "old comment",
            Map.of("change", "old", "remove", "old", "same", "same"),
            null);
    CatalogUpdateRequest desired =
        new CatalogUpdateRequest(
            "new comment", Map.of("add", "new", "change", "new", "same", "same"));

    assertArrayEquals(
        new CatalogChange[] {
          CatalogChange.updateComment("new comment"),
          CatalogChange.setProperty("add", "new"),
          CatalogChange.setProperty("change", "new"),
          CatalogChange.removeProperty("remove")
        },
        ResourceMutationMapper.toChanges(current, desired));
  }

  @Test
  public void testSchemaDesiredStateProducesTheSupportedPropertyMutation() {
    SchemaResource current =
        new SchemaResource(
            "metalakes/demo/catalogs/lakehouse/schemas/sales",
            "sales",
            "comment",
            Map.of("change", "old", "remove", "old", "same", "same"),
            null);
    SchemaUpdateRequest desired =
        new SchemaUpdateRequest("comment", Map.of("add", "new", "change", "new", "same", "same"));

    assertArrayEquals(
        new SchemaChange[] {
          SchemaChange.setProperty("add", "new"),
          SchemaChange.setProperty("change", "new"),
          SchemaChange.removeProperty("remove")
        },
        ResourceMutationMapper.toChanges(current, desired));
  }
}
