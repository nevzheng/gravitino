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
import org.junit.jupiter.api.Test;

class TestPostgreSQLLegacyDeleteProviders {

  private static final String OUTER_TOMBSTONE_GUARD =
      ") AND deleted_at > 0 AND deleted_at < #{legacyTimeline}";

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

      assertTrue(
          sql.endsWith(OUTER_TOMBSTONE_GUARD),
          () -> providerClass.getSimpleName() + " does not recheck the tombstone cutoff: " + sql);
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
