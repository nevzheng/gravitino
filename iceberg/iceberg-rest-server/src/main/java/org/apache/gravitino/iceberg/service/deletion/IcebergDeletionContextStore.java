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

package org.apache.gravitino.iceberg.service.deletion;

import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.service.cleanup.mapper.provider.IcebergCleanupMapperPackageProvider;
import org.apache.gravitino.iceberg.service.deletion.mapper.IcebergDeletionContextMapper;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.ibatis.session.Configuration;

/** Persistence for the immutable Iceberg-specific input of a metadata deletion generation. */
public class IcebergDeletionContextStore {

  /** Creates a store backed by Gravitino's shared relational metadata database. */
  public IcebergDeletionContextStore() {
    registerMappers();
  }

  /**
   * Inserts the context for one deletion generation.
   *
   * <p>The call joins an existing relational session, allowing DELETE to persist the table
   * tombstone, deletion action, and Iceberg context in one transaction.
   *
   * @param context immutable purge input
   */
  public void insert(IcebergDeletionContextPO context) {
    Objects.requireNonNull(context, "context must not be null");
    SessionUtils.doWithCommit(
        IcebergDeletionContextMapper.class, mapper -> mapper.insertDeletionContext(context));
  }

  /**
   * Loads the context for one exact deletion generation.
   *
   * @param deletionId opaque deletion-generation identifier
   * @return context, or {@code null} when absent
   */
  @Nullable
  public IcebergDeletionContextPO get(String deletionId) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    return SessionUtils.doWithCommitAndFetchResult(
        IcebergDeletionContextMapper.class, mapper -> mapper.selectDeletionContext(deletionId));
  }

  private static void registerMappers() {
    Configuration configuration =
        SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().getConfiguration();
    for (Class<?> mapper : new IcebergCleanupMapperPackageProvider().getMapperClasses()) {
      synchronized (configuration) {
        if (!configuration.hasMapper(mapper)) {
          configuration.addMapper(mapper);
        }
      }
    }
  }
}
