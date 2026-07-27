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

import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** MyBatis mapper for immutable Iceberg deletion-generation context. */
public interface IcebergDeletionContextMapper {

  /** Iceberg deletion-context table name. */
  String TABLE_NAME = "iceberg_deletion_context";

  /**
   * Inserts the immutable context for one deletion generation.
   *
   * @param context context to insert
   */
  @InsertProvider(
      type = IcebergDeletionContextSQLProviderFactory.class,
      method = "insertDeletionContext")
  void insertDeletionContext(@Param("context") IcebergDeletionContextPO context);

  /**
   * Selects the context for one exact deletion generation.
   *
   * @param deletionId opaque deletion-generation identifier
   * @return context, or {@code null} when absent
   */
  @Nullable
  @SelectProvider(
      type = IcebergDeletionContextSQLProviderFactory.class,
      method = "selectDeletionContext")
  IcebergDeletionContextPO selectDeletionContext(@Param("deletionId") String deletionId);
}
