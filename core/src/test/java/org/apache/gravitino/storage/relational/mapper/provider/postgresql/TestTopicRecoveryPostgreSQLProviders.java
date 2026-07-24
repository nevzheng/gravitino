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
import org.apache.gravitino.storage.relational.mapper.provider.base.TopicMetaBaseSQLProvider;
import org.junit.jupiter.api.Test;

class TestTopicRecoveryPostgreSQLProviders {

  @Test
  void testLegacyTopicDeletesExcludeRecordedDeletionGenerations() {
    List<String> sqlStatements =
        List.of(
            new TopicMetaBaseSQLProvider().deleteTopicMetasByLegacyTimeline(1L, 1),
            new TopicMetaPostgreSQLProvider().deleteTopicMetasByLegacyTimeline(1L, 1));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("deletion_id IS NULL"),
          () -> "Legacy topic deletion can select a recorded deletion generation: " + sql);
    }
    assertTrue(
        countOccurrences(sqlStatements.get(1), "deletion_id IS NULL") == 2,
        () ->
            "PostgreSQL legacy deletion must guard both selection stages: " + sqlStatements.get(1));
  }

  @Test
  void testBaseTopicSoftDeletesKeepMillisecondPrecisionBeforeClearingGenerationToken() {
    TopicMetaBaseSQLProvider topicProvider = new TopicMetaBaseSQLProvider();
    List<String> sqlStatements =
        List.of(
            topicProvider.softDeleteTopicMetasByTopicId(1L),
            topicProvider.softDeleteTopicMetasByMetalakeId(1L),
            topicProvider.softDeleteTopicMetasByCatalogId(1L),
            topicProvider.softDeleteTopicMetasBySchemaIds(List.of(1L)));

    for (String sql : sqlStatements) {
      assertTrue(
          sql.contains("/ 1000, deletion_id = NULL"),
          () -> "Deletion token was inserted into the topic timestamp expression: " + sql);
    }
  }

  private static int countOccurrences(String value, String needle) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }
}
