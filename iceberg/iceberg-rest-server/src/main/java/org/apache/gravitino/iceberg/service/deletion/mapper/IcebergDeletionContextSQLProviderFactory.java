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

package org.apache.gravitino.iceberg.service.deletion.mapper;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.iceberg.service.deletion.mapper.provider.base.IcebergDeletionContextBaseSQLProvider;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** Dispatches Iceberg deletion-context SQL through the configured relational backend. */
public class IcebergDeletionContextSQLProviderFactory {

  private static final IcebergDeletionContextBaseSQLProvider BASE_PROVIDER =
      new IcebergDeletionContextBaseSQLProvider();

  private static final Map<JDBCBackendType, IcebergDeletionContextBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, BASE_PROVIDER,
          JDBCBackendType.H2, BASE_PROVIDER,
          JDBCBackendType.POSTGRESQL, BASE_PROVIDER);

  /**
   * @param context context row to insert
   * @return backend-specific insert statement
   */
  public static String insertDeletionContext(@Param("context") IcebergDeletionContextPO context) {
    return getProvider().insertDeletionContext(context);
  }

  /**
   * @param deletionId opaque deletion-generation identifier
   * @return backend-specific select statement
   */
  public static String selectDeletionContext(@Param("deletionId") String deletionId) {
    return getProvider().selectDeletionContext(deletionId);
  }

  private static IcebergDeletionContextBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }
}
