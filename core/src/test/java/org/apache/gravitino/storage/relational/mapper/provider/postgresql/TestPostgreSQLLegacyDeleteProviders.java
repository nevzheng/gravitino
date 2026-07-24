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
package org.apache.gravitino.storage.relational.mapper.provider.postgresql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.base.TableMetaBaseSQLProvider;
import org.apache.gravitino.storage.relational.mapper.provider.base.TableVersionBaseSQLProvider;
import org.apache.gravitino.storage.relational.mapper.provider.base.ViewMetaBaseSQLProvider;
import org.apache.gravitino.storage.relational.mapper.provider.base.ViewVersionInfoBaseSQLProvider;
import org.junit.jupiter.api.Test;

class TestPostgreSQLLegacyDeleteProviders {

  private static final List<Class<?>> PROVIDER_CLASSES =
      List.of(
          CatalogMetaPostgreSQLProvider.class,
          FilesetMetaPostgreSQLProvider.class,
          FilesetVersionPostgreSQLProvider.class,
          FunctionMetaPostgreSQLProvider.class,
          FunctionVersionMetaPostgreSQLProvider.class,
          GroupMetaPostgreSQLProvider.class,
          GroupRoleRelPostgreSQLProvider.class,
          JobMetaPostgreSQLProvider.class,
          JobTemplateMetaPostgreSQLProvider.class,
          MetalakeMetaPostgreSQLProvider.class,
          ModelMetaPostgreSQLProvider.class,
          ModelVersionAliasRelPostgreSQLProvider.class,
          ModelVersionMetaPostgreSQLProvider.class,
          OwnerMetaPostgreSQLProvider.class,
          PolicyMetaPostgreSQLProvider.class,
          PolicyMetadataObjectRelPostgreSQLProvider.class,
          PolicyVersionPostgreSQLProvider.class,
          RoleMetaPostgreSQLProvider.class,
          SchemaMetaPostgreSQLProvider.class,
          SecurableObjectPostgreSQLProvider.class,
          StatisticPostgresSQLProvider.class,
          TableColumnPostgreSQLProvider.class,
          TableMetaPostgreSQLProvider.class,
          TableVersionPostgreSQLProvider.class,
          TagMetaPostgreSQLProvider.class,
          TagMetadataObjectRelPostgreSQLProvider.class,
          TopicMetaPostgreSQLProvider.class,
          UserMetaPostgreSQLProvider.class,
          UserRoleRelPostgreSQLProvider.class,
          ViewMetaPostgreSQLProvider.class,
          ViewVersionInfoPostgreSQLProvider.class);

  @Test
  void testLegacyDeletesRecheckTombstoneAfterSelectingBatch() throws ReflectiveOperationException {
    for (Class<?> providerClass : PROVIDER_CLASSES) {
      Object provider = providerClass.getDeclaredConstructor().newInstance();
      Method legacyDeleteMethod = legacyDeleteMethod(providerClass);
      String sql = (String) legacyDeleteMethod.invoke(provider, 1L, 1);
      String outerDeletePredicate = sql.substring(sql.lastIndexOf(") AND "));

      assertTrue(
          outerDeletePredicate.contains("deleted_at > 0")
              && outerDeletePredicate.contains("deleted_at < #{legacyTimeline}"),
          () -> providerClass.getSimpleName() + " does not recheck the tombstone cutoff: " + sql);
    }
  }

  @Test
  void testLegacyTableDeletesExcludeRecordedDeletionGenerations() {
    List<String> sqlStatements =
        List.of(
            new TableMetaBaseSQLProvider().deleteTableMetasByLegacyTimeline(1L, 1),
            new TableVersionBaseSQLProvider().deleteTableVersionByLegacyTimeline(1L, 1),
            new TableMetaPostgreSQLProvider().deleteTableMetasByLegacyTimeline(1L, 1),
            new TableVersionPostgreSQLProvider().deleteTableVersionByLegacyTimeline(1L, 1));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("deletion_id IS NULL"),
          () -> "Legacy table deletion can select a recorded deletion generation: " + sql);
    }
  }

  @Test
  void testLegacyViewDeletesExcludeRecordedDeletionGenerations() {
    List<String> sqlStatements =
        List.of(
            new ViewMetaBaseSQLProvider().deleteViewMetasByLegacyTimeline(1L, 1),
            new ViewVersionInfoBaseSQLProvider().deleteViewVersionsByLegacyTimeline(1L, 1),
            new ViewMetaPostgreSQLProvider().deleteViewMetasByLegacyTimeline(1L, 1),
            new ViewVersionInfoPostgreSQLProvider().deleteViewVersionsByLegacyTimeline(1L, 1));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("deletion_id IS NULL"),
          () -> "Legacy view deletion can select a recorded deletion generation: " + sql);
    }

    for (String sql : sqlStatements.subList(2, 4)) {
      assertTrue(
          sql.indexOf("deletion_id IS NULL") != sql.lastIndexOf("deletion_id IS NULL"),
          () -> "PostgreSQL outer delete does not recheck the view generation token: " + sql);
    }
  }

  @Test
  void testTableVersionSoftDeleteCannotClearARecordedGenerationToken() {
    String sql =
        new TableVersionPostgreSQLProvider().softDeleteTableVersionByTableIdAndVersion(1L, 1L);

    assertTrue(sql.contains("deletion_id = NULL"));
    assertTrue(sql.endsWith("AND deleted_at = 0"));
  }

  @Test
  void testBaseTableSoftDeletesKeepMillisecondPrecisionBeforeClearingGenerationToken() {
    TableMetaBaseSQLProvider tableProvider = new TableMetaBaseSQLProvider();
    List<String> sqlStatements =
        List.of(
            tableProvider.softDeleteTableMetasByTableId(1L),
            tableProvider.softDeleteTableMetasByMetalakeId(1L),
            tableProvider.softDeleteTableMetasByCatalogId(1L),
            tableProvider.softDeleteTableMetasBySchemaIds(List.of(1L)),
            new TableVersionBaseSQLProvider().softDeleteTableVersionByTableIdAndVersion(1L, 1L));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("/ 1000, deletion_id = NULL"),
          () -> "Deletion token was inserted into the timestamp expression: " + sql);
    }
  }

  private static Method legacyDeleteMethod(Class<?> providerClass) {
    return Arrays.stream(providerClass.getDeclaredMethods())
        .filter(method -> method.getName().startsWith("delete"))
        .filter(method -> method.getName().endsWith("ByLegacyTimeline"))
        .filter(
            method ->
                Arrays.equals(method.getParameterTypes(), new Class<?>[] {Long.class, int.class}))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "No legacy delete method found on " + providerClass.getSimpleName()));
  }
}
