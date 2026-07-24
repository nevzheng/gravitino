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
package org.apache.gravitino.storage.relational.mapper;

import java.util.List;
import org.apache.gravitino.storage.relational.po.TopicPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for exact, generation-scoped topic deletion and recovery. */
public interface TopicRecoveryMapper {

  /** Result map for topic base rows used by recovery. */
  @Results(
      id = "topicRecoveryResultMap",
      value = {
        @Result(property = "topicId", column = "topic_id", id = true),
        @Result(property = "topicName", column = "topic_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "catalogId", column = "catalog_id"),
        @Result(property = "schemaId", column = "schema_id"),
        @Result(property = "comment", column = "comment"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  TopicPO topicRecoveryResultMap();

  /** Locks the live parent schema to serialize child mutations and recovery. */
  @Select({
    "SELECT schema_id FROM schema_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveSchema(@Param("schemaId") long schemaId);

  /** Locks and returns the current live topic under the already-locked schema. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE schema_id = #{schemaId} AND topic_name = #{topicName}",
    "AND deleted_at = 0 FOR UPDATE"
  })
  TopicPO selectLiveTopicForUpdate(
      @Param("schemaId") long schemaId, @Param("topicName") String topicName);

  /** Locks a recoverable tombstone whose immutable ID would otherwise be overwritten. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE topic_id = #{topicId} AND deleted_at > 0",
    "AND deletion_id IS NOT NULL FOR UPDATE"
  })
  TopicPO selectRecordedDeletedTopicForUpdate(@Param("topicId") long topicId);

  /** Returns the newest generation timestamp for either the ID or current parent/name. */
  @Select({
    "SELECT MAX(deleted_at) FROM entity_deletion WHERE entity_type = 'TOPIC'",
    "AND (entity_id = #{topicId}",
    "OR (parent_id = #{schemaId} AND entity_name = #{topicName}))"
  })
  Long selectNewestTopicDeletedAt(
      @Param("topicId") long topicId,
      @Param("schemaId") long schemaId,
      @Param("topicName") String topicName);

  /** Lists deleted topic base rows below one live schema, newest first. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at > 0",
    "ORDER BY deleted_at DESC, topic_id DESC"
  })
  List<TopicPO> listDeletedTopics(@Param("schemaId") long schemaId);

  /** Lists live topic identities below one schema. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE schema_id = #{schemaId} AND deleted_at = 0"
  })
  List<TopicPO> listLiveTopics(@Param("schemaId") long schemaId);

  /** Lists globally live rows for candidate immutable IDs. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "<script>",
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE deleted_at = 0 AND topic_id IN",
    "<foreach collection='topicIds' item='topicId' open='(' separator=',' close=')'>",
    "#{topicId}",
    "</foreach>",
    "</script>"
  })
  List<TopicPO> listLiveTopicsByIds(@Param("topicIds") List<Long> topicIds);

  /** Selects the topic base row for one exact deletion generation. */
  @ResultMap("topicRecoveryResultMap")
  @Select({
    "SELECT topic_id, topic_name, metalake_id, catalog_id, schema_id, comment, properties,",
    "audit_info, current_version, last_version, deleted_at, deletion_id FROM topic_meta",
    "WHERE topic_id = #{topicId} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  TopicPO selectTopicGeneration(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes the exact live topic base row. */
  @Update({
    "UPDATE topic_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE topic_id = #{topicId} AND schema_id = #{schemaId}",
    "AND topic_name = #{topicName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = 0 AND deletion_id IS NULL"
  })
  int softDeleteTopicMeta(
      @Param("topicId") long topicId,
      @Param("schemaId") long schemaId,
      @Param("topicName") String topicName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live owner relations for the topic. */
  @Update({
    "UPDATE owner_meta SET deleted_at = #{deletedAt}, updated_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{topicId}",
    "AND metadata_object_type = 'TOPIC' AND deleted_at = 0"
  })
  int softDeleteOwnerRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live role grants whose securable object is the topic. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = #{deletedAt},",
    "deletion_id = #{deletionId} WHERE metadata_object_id = #{topicId}",
    "AND type = 'TOPIC' AND deleted_at = 0"
  })
  int softDeleteSecurableObjects(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live tag relations for the topic. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = 0"
  })
  int softDeleteTagRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live statistics for the topic. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = 0"
  })
  int softDeleteStatistics(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Soft-deletes live policy relations for the topic. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = 0"
  })
  int softDeletePolicyRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores owner relations in one exact topic deletion generation. */
  @Update({
    "UPDATE owner_meta SET deleted_at = 0, updated_at = #{restoredAt}, deletion_id = NULL",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreOwnerRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores role grants in one exact topic deletion generation. */
  @Update({
    "UPDATE role_meta_securable_object SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{topicId} AND type = 'TOPIC'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreSecurableObjects(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores tag relations in one exact topic deletion generation. */
  @Update({
    "UPDATE tag_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreTagRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores statistics in one exact topic deletion generation. */
  @Update({
    "UPDATE statistic_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restoreStatistics(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores policy relations in one exact topic deletion generation. */
  @Update({
    "UPDATE policy_relation_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE metadata_object_id = #{topicId} AND metadata_object_type = 'TOPIC'",
    "AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int restorePolicyRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact topic base row after its aggregate has been verified. */
  @Update({
    "UPDATE topic_meta SET deleted_at = 0, deletion_id = NULL",
    "WHERE topic_id = #{topicId} AND schema_id = #{schemaId}",
    "AND topic_name = #{topicName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int restoreTopicMeta(
      @Param("topicId") long topicId,
      @Param("schemaId") long schemaId,
      @Param("topicName") String topicName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes owner relations from one exact generation. */
  @Delete({
    "DELETE FROM owner_meta WHERE metadata_object_id = #{topicId}",
    "AND metadata_object_type = 'TOPIC' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteOwnerRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes role grants from one exact generation. */
  @Delete({
    "DELETE FROM role_meta_securable_object WHERE metadata_object_id = #{topicId}",
    "AND type = 'TOPIC' AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}"
  })
  int hardDeleteSecurableObjects(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes tag relations from one exact generation. */
  @Delete({
    "DELETE FROM tag_relation_meta WHERE metadata_object_id = #{topicId}",
    "AND metadata_object_type = 'TOPIC' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTagRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes statistics from one exact generation. */
  @Delete({
    "DELETE FROM statistic_meta WHERE metadata_object_id = #{topicId}",
    "AND metadata_object_type = 'TOPIC' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteStatistics(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes policy relations from one exact generation. */
  @Delete({
    "DELETE FROM policy_relation_meta WHERE metadata_object_id = #{topicId}",
    "AND metadata_object_type = 'TOPIC' AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeletePolicyRelations(
      @Param("topicId") long topicId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the topic base row from one exact generation. */
  @Delete({
    "DELETE FROM topic_meta WHERE topic_id = #{topicId} AND schema_id = #{schemaId}",
    "AND topic_name = #{topicName} AND current_version = #{currentVersion}",
    "AND last_version = #{lastVersion} AND deleted_at = #{deletedAt}",
    "AND deletion_id = #{deletionId}"
  })
  int hardDeleteTopicMeta(
      @Param("topicId") long topicId,
      @Param("schemaId") long schemaId,
      @Param("topicName") String topicName,
      @Param("currentVersion") long currentVersion,
      @Param("lastVersion") long lastVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
