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

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.base.FilesetMetaBaseSQLProvider;
import org.apache.gravitino.storage.relational.mapper.provider.base.FilesetVersionBaseSQLProvider;
import org.junit.jupiter.api.Test;

class TestFilesetVersionPostgreSQLProvider {

  @Test
  void testRetentionDeleteRechecksLiveUnrecordedRow() {
    String sql =
        new FilesetVersionPostgreSQLProvider()
            .softDeleteFilesetVersionsByRetentionLine(1L, 2L, 100);

    assertTrue(sql.contains("LIMIT #{limit}) AND deleted_at = 0 AND deletion_id IS NULL"), sql);
  }

  @Test
  void testLegacyDeletesExcludeRecordedDeletionGenerations() {
    List<String> sqlStatements =
        List.of(
            new FilesetMetaBaseSQLProvider().deleteFilesetMetasByLegacyTimeline(1L, 1),
            new FilesetVersionBaseSQLProvider().deleteFilesetVersionsByLegacyTimeline(1L, 1),
            new FilesetMetaPostgreSQLProvider().deleteFilesetMetasByLegacyTimeline(1L, 1),
            new FilesetVersionPostgreSQLProvider().deleteFilesetVersionsByLegacyTimeline(1L, 1));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("deletion_id IS NULL"),
          () -> "Legacy fileset deletion can select a recorded generation: " + sql);
    }

    for (String sql : sqlStatements.subList(2, 4)) {
      assertTrue(
          sql.indexOf("deletion_id IS NULL") != sql.lastIndexOf("deletion_id IS NULL"),
          () -> "PostgreSQL outer delete does not recheck the generation token: " + sql);
    }
  }
}
