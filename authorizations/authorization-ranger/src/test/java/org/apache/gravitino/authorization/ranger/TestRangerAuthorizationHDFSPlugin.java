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
package org.apache.gravitino.authorization.ranger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationMetadataObject;
import org.apache.gravitino.authorization.common.PathBasedMetadataObject;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.connector.authorization.AuthorizationPluginContext;
import org.apache.gravitino.file.Fileset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestRangerAuthorizationHDFSPlugin {

  private final CatalogDispatcher catalogs = mock(CatalogDispatcher.class);
  private final FilesetDispatcher filesets = mock(FilesetDispatcher.class);
  private RangerAuthorizationHDFSPlugin plugin;

  @BeforeEach
  public void setUp() throws Exception {
    // The constructor connects to Ranger, so only the tested methods run for real.
    plugin = Mockito.mock(RangerAuthorizationHDFSPlugin.class, Mockito.CALLS_REAL_METHODS);
    AuthorizationPluginContext context =
        AuthorizationPluginContext.of(
            () -> true, () -> catalogs, () -> null, () -> null, () -> filesets);
    FieldUtils.writeField(plugin, "pluginContext", context, true);
    FieldUtils.writeField(plugin, "metalake", "metalake", true);
  }

  @Test
  public void testFilesetLocationIsReadThroughTheContext() {
    Fileset fileset = mock(Fileset.class);
    when(fileset.storageLocation()).thenReturn("hdfs://cluster/data/fileset");
    when(filesets.loadFileset(NameIdentifier.of("metalake", "catalog", "schema", "fileset")))
        .thenReturn(fileset);

    List<AuthorizationMetadataObject> objects =
        plugin.translateMetadataObject(
            MetadataObjects.parse("catalog.schema.fileset", MetadataObject.Type.FILESET));

    Assertions.assertEquals(1, objects.size());
    Assertions.assertEquals(
        "hdfs://cluster/data/fileset", ((PathBasedMetadataObject) objects.get(0)).path());
  }

  @Test
  public void testMetalakeCatalogsAreListedThroughTheContext() {
    NameIdentifier catalogIdent = NameIdentifier.of("metalake", "catalog");
    Catalog catalog = mock(Catalog.class);
    when(catalog.provider()).thenReturn("fileset");
    when(catalogs.listCatalogs(Namespace.of("metalake")))
        .thenReturn(new NameIdentifier[] {catalogIdent});
    when(catalogs.loadCatalog(any())).thenReturn(catalog);

    List<AuthorizationMetadataObject> objects =
        plugin.translateMetadataObject(
            MetadataObjects.of(null, "metalake", MetadataObject.Type.METALAKE));

    Assertions.assertTrue(objects.isEmpty());
    verify(catalogs).listCatalogs(Namespace.of("metalake"));
    verify(catalogs).loadCatalog(catalogIdent);
  }

  @Test
  public void testRangerAuthorizationNeedsAPluginContext() {
    RangerAuthorization authorization = new RangerAuthorization();

    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> authorization.newPlugin("metalake", "hive", ImmutableMap.of()));
  }
}
