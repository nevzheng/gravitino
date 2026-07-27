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

package org.apache.gravitino.iceberg.service.deletion.purge.mapper;

import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Exact source-table-id plus deletion-generation hard-delete operations. */
public interface IcebergPurgeMetadataMapper {

  /** Counts the exact tombstoned parent generation. */
  @SelectProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "countExactTable")
  long countExactTable(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation column rows. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteColumns")
  int deleteColumns(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation version rows. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteVersions")
  int deleteVersions(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation owner rows. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteOwners")
  int deleteOwners(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation privilege rows. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteSecurableObjects")
  int deleteSecurableObjects(
      @Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation tag relations. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteTagRelations")
  int deleteTagRelations(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation policy relations. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deletePolicyRelations")
  int deletePolicyRelations(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes exact-generation statistics. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteStatistics")
  int deleteStatistics(@Param("tableId") long tableId, @Param("deletionId") String deletionId);

  /** Deletes the exact tombstoned table parent last. */
  @DeleteProvider(type = IcebergPurgeMetadataSQLProvider.class, method = "deleteTable")
  int deleteTable(@Param("tableId") long tableId, @Param("deletionId") String deletionId);
}
