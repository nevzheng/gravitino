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

package org.apache.gravitino.iceberg.service.deletion.mapper.provider.base;

import static org.apache.gravitino.iceberg.service.deletion.mapper.IcebergDeletionContextMapper.TABLE_NAME;

import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.ibatis.annotations.Param;

/** Portable SQL for the immutable {@code iceberg_deletion_context} record. */
public class IcebergDeletionContextBaseSQLProvider {

  /**
   * @param context context row to insert
   * @return parameterized insert statement
   */
  public String insertDeletionContext(@Param("context") IcebergDeletionContextPO context) {
    return "INSERT INTO "
        + TABLE_NAME
        + " (deletion_id, iceberg_namespace, iceberg_table_name, iceberg_table_uuid,"
        + " metadata_location, file_io_impl, protected_file_io_ref, context_digest, created_at,"
        + " updated_at) VALUES (#{context.deletionId}, #{context.icebergNamespace},"
        + " #{context.icebergTableName}, #{context.icebergTableUuid},"
        + " #{context.metadataLocation}, #{context.fileIoImpl},"
        + " #{context.protectedFileIoRef}, #{context.contextDigest}, #{context.createdAt},"
        + " #{context.updatedAt})";
  }

  /**
   * @param deletionId opaque deletion-generation identifier
   * @return exact-generation select statement
   */
  public String selectDeletionContext(@Param("deletionId") String deletionId) {
    return "SELECT deletion_id AS deletionId, iceberg_namespace AS icebergNamespace,"
        + " iceberg_table_name AS icebergTableName, iceberg_table_uuid AS icebergTableUuid,"
        + " metadata_location AS metadataLocation, file_io_impl AS fileIoImpl,"
        + " protected_file_io_ref AS protectedFileIoRef, context_digest AS contextDigest,"
        + " created_at AS createdAt, updated_at AS updatedAt FROM "
        + TABLE_NAME
        + " WHERE deletion_id = #{deletionId}";
  }
}
