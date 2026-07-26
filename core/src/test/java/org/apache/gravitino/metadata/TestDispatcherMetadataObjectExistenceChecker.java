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
package org.apache.gravitino.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Set;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Test;

public class TestDispatcherMetadataObjectExistenceChecker {

  private static final String METALAKE = "metalake";

  @Test
  void testRoutesEveryMetadataObjectTypeToItsOwningDispatcher() {
    Fixture fixture = new Fixture();
    Set<MetadataObject.Type> checkedTypes = EnumSet.noneOf(MetadataObject.Type.class);

    when(fixture.metalakeDispatcher.metalakeExists(NameIdentifier.of(METALAKE))).thenReturn(true);
    check(fixture, checkedTypes, null, METALAKE, MetadataObject.Type.METALAKE);
    verify(fixture.metalakeDispatcher).metalakeExists(NameIdentifier.of(METALAKE));

    when(fixture.catalogDispatcher.catalogExists(NameIdentifier.of(METALAKE, "catalog")))
        .thenReturn(true);
    check(fixture, checkedTypes, null, "catalog", MetadataObject.Type.CATALOG);
    verify(fixture.catalogDispatcher).catalogExists(NameIdentifier.of(METALAKE, "catalog"));

    when(fixture.schemaDispatcher.schemaExists(NameIdentifier.of(METALAKE, "catalog", "schema")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog", "schema", MetadataObject.Type.SCHEMA);
    verify(fixture.schemaDispatcher).schemaExists(NameIdentifier.of(METALAKE, "catalog", "schema"));

    when(fixture.filesetDispatcher.filesetExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "fileset")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "fileset", MetadataObject.Type.FILESET);
    verify(fixture.filesetDispatcher)
        .filesetExists(NameIdentifier.of(METALAKE, "catalog", "schema", "fileset"));

    when(fixture.tableDispatcher.tableExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "table")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "table", MetadataObject.Type.TABLE);
    verify(fixture.tableDispatcher)
        .tableExists(NameIdentifier.of(METALAKE, "catalog", "schema", "table"));

    when(fixture.viewDispatcher.viewExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "view")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "view", MetadataObject.Type.VIEW);
    verify(fixture.viewDispatcher)
        .viewExists(NameIdentifier.of(METALAKE, "catalog", "schema", "view"));

    when(fixture.topicDispatcher.topicExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "topic")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "topic", MetadataObject.Type.TOPIC);
    verify(fixture.topicDispatcher)
        .topicExists(NameIdentifier.of(METALAKE, "catalog", "schema", "topic"));

    check(fixture, checkedTypes, "catalog.schema.table", "column", MetadataObject.Type.COLUMN);
    verify(fixture.tableDispatcher, times(2))
        .tableExists(NameIdentifier.of(METALAKE, "catalog", "schema", "table"));

    check(fixture, checkedTypes, null, "role", MetadataObject.Type.ROLE);
    verify(fixture.accessControlDispatcher).getRole(METALAKE, "role");

    when(fixture.modelDispatcher.modelExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "model")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "model", MetadataObject.Type.MODEL);
    verify(fixture.modelDispatcher)
        .modelExists(NameIdentifier.of(METALAKE, "catalog", "schema", "model"));

    check(fixture, checkedTypes, null, "tag", MetadataObject.Type.TAG);
    verify(fixture.tagDispatcher).getTag(METALAKE, "tag");

    check(fixture, checkedTypes, null, "policy", MetadataObject.Type.POLICY);
    verify(fixture.policyDispatcher).getPolicy(METALAKE, "policy");

    check(fixture, checkedTypes, null, "job", MetadataObject.Type.JOB);
    verify(fixture.jobOperationDispatcher).getJob(METALAKE, "job");

    check(fixture, checkedTypes, null, "template", MetadataObject.Type.JOB_TEMPLATE);
    verify(fixture.jobOperationDispatcher).getJobTemplate(METALAKE, "template");

    when(fixture.functionDispatcher.functionExists(
            NameIdentifier.of(METALAKE, "catalog", "schema", "function")))
        .thenReturn(true);
    check(fixture, checkedTypes, "catalog.schema", "function", MetadataObject.Type.FUNCTION);
    verify(fixture.functionDispatcher)
        .functionExists(NameIdentifier.of(METALAKE, "catalog", "schema", "function"));

    assertEquals(EnumSet.allOf(MetadataObject.Type.class), checkedTypes);
  }

  @Test
  void testConvertsFalseExistenceResultToNoSuchMetadataObject() {
    Fixture fixture = new Fixture();
    MetadataObject catalog = MetadataObjects.of(null, "missing", MetadataObject.Type.CATALOG);

    NoSuchMetadataObjectException exception =
        assertThrows(
            NoSuchMetadataObjectException.class, () -> fixture.checker.check(METALAKE, catalog));

    assertEquals("Metadata object missing type CATALOG doesn't exist", exception.getMessage());
    verify(fixture.catalogDispatcher).catalogExists(NameIdentifier.of(METALAKE, "missing"));
    verifyNoInteractions(
        fixture.metalakeDispatcher,
        fixture.schemaDispatcher,
        fixture.tableDispatcher,
        fixture.filesetDispatcher,
        fixture.topicDispatcher,
        fixture.modelDispatcher,
        fixture.functionDispatcher,
        fixture.viewDispatcher,
        fixture.accessControlDispatcher,
        fixture.tagDispatcher,
        fixture.policyDispatcher,
        fixture.jobOperationDispatcher);
  }

  private static void check(
      Fixture fixture,
      Set<MetadataObject.Type> checkedTypes,
      String parent,
      String name,
      MetadataObject.Type type) {
    fixture.checker.check(METALAKE, MetadataObjects.of(parent, name, type));
    checkedTypes.add(type);
  }

  private static final class Fixture {
    private final MetalakeDispatcher metalakeDispatcher = mock(MetalakeDispatcher.class);
    private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
    private final SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);
    private final TableDispatcher tableDispatcher = mock(TableDispatcher.class);
    private final FilesetDispatcher filesetDispatcher = mock(FilesetDispatcher.class);
    private final TopicDispatcher topicDispatcher = mock(TopicDispatcher.class);
    private final ModelDispatcher modelDispatcher = mock(ModelDispatcher.class);
    private final FunctionDispatcher functionDispatcher = mock(FunctionDispatcher.class);
    private final ViewDispatcher viewDispatcher = mock(ViewDispatcher.class);
    private final AccessControlDispatcher accessControlDispatcher =
        mock(AccessControlDispatcher.class);
    private final TagDispatcher tagDispatcher = mock(TagDispatcher.class);
    private final PolicyDispatcher policyDispatcher = mock(PolicyDispatcher.class);
    private final JobOperationDispatcher jobOperationDispatcher =
        mock(JobOperationDispatcher.class);
    private final DispatcherMetadataObjectExistenceChecker checker =
        new DispatcherMetadataObjectExistenceChecker(
            metalakeDispatcher,
            catalogDispatcher,
            schemaDispatcher,
            tableDispatcher,
            filesetDispatcher,
            topicDispatcher,
            modelDispatcher,
            functionDispatcher,
            viewDispatcher,
            accessControlDispatcher,
            () -> tagDispatcher,
            () -> policyDispatcher,
            () -> jobOperationDispatcher);
  }
}
