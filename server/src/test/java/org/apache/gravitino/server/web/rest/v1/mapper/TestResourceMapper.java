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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.Schema;
import org.apache.gravitino.rest.v1.model.CatalogResource;
import org.apache.gravitino.rest.v1.model.CatalogType;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.rest.v1.model.SchemaResource;
import org.junit.jupiter.api.Test;

public class TestResourceMapper {

  @Test
  public void testMapsMetalakeWithCanonicalNamePropertiesAndAudit() {
    Metalake metalake = mock(Metalake.class);
    when(metalake.name()).thenReturn("demo");
    when(metalake.comment()).thenReturn("Demo metalake");
    when(metalake.properties()).thenReturn(properties());
    Audit audit = audit();
    when(metalake.auditInfo()).thenReturn(audit);

    MetalakeResource resource = ResourceMapper.toResource(metalake);

    assertEquals("metalakes/demo", resource.getResourceName());
    assertEquals("demo", resource.getName());
    assertEquals("Demo metalake", resource.getComment());
    assertEquals("1", resource.getProperties().get("a"));
    assertEquals("2", resource.getProperties().get("b"));
    assertEquals(2, resource.getProperties().size());
    assertEquals("creator", resource.getAudit().getCreator());
    assertEquals(Instant.parse("2026-07-14T18:45:00Z"), resource.getAudit().getLastModifiedTime());
  }

  @Test
  public void testMapsCatalogAndSchemaWithCanonicalNames() {
    Catalog catalog = mock(Catalog.class);
    when(catalog.name()).thenReturn("lakehouse");
    when(catalog.type()).thenReturn(Catalog.Type.RELATIONAL);
    when(catalog.provider()).thenReturn("iceberg");
    when(catalog.comment()).thenReturn(null);
    when(catalog.properties()).thenReturn(null);
    when(catalog.auditInfo()).thenReturn(null);
    Schema schema = mock(Schema.class);
    when(schema.name()).thenReturn("sales");
    when(schema.comment()).thenReturn("Sales data");
    when(schema.properties()).thenReturn(null);
    when(schema.auditInfo()).thenReturn(null);

    CatalogResource catalogResource = ResourceMapper.toResource("demo", catalog);
    SchemaResource schemaResource = ResourceMapper.toResource("demo", "lakehouse", schema);

    assertEquals("metalakes/demo/catalogs/lakehouse", catalogResource.getResourceName());
    assertEquals(CatalogType.RELATIONAL, catalogResource.getType());
    assertEquals("iceberg", catalogResource.getProvider());
    assertTrue(catalogResource.getProperties().isEmpty());
    assertNull(catalogResource.getAudit());
    assertEquals(
        "metalakes/demo/catalogs/lakehouse/schemas/sales", schemaResource.getResourceName());
    assertEquals("Sales data", schemaResource.getComment());
    assertTrue(schemaResource.getProperties().isEmpty());
  }

  @Test
  public void testRejectsInternalCatalogTypesOutsideTheV1Contract() {
    Catalog catalog = mock(Catalog.class);
    when(catalog.name()).thenReturn("unsupported");
    when(catalog.type()).thenReturn(Catalog.Type.UNSUPPORTED);

    assertThrows(IllegalArgumentException.class, () -> ResourceMapper.toResource("demo", catalog));
  }

  private static Map<String, String> properties() {
    Map<String, String> properties = new HashMap<>();
    properties.put("b", "2");
    properties.put("a", "1");
    properties.put(null, "discarded");
    properties.put("discarded", null);
    return properties;
  }

  private static Audit audit() {
    Audit audit = mock(Audit.class);
    when(audit.creator()).thenReturn("creator");
    when(audit.createTime()).thenReturn(Instant.parse("2026-07-14T17:30:00Z"));
    when(audit.lastModifier()).thenReturn("modifier");
    when(audit.lastModifiedTime()).thenReturn(Instant.parse("2026-07-14T18:45:00Z"));
    return audit;
  }
}
