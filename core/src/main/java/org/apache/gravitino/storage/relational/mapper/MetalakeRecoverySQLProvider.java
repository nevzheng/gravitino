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

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** SQL provider for generation-scoped metalake-tree deletion, restore, and purge. */
public class MetalakeRecoverySQLProvider {

  /** Builds the update that stamps live rows belonging to one metalake tree. */
  public String softDeleteAggregateRows(Map<String, Object> parameters) {
    MetalakeAggregateTable aggregateTable = aggregateTable(parameters);
    return "UPDATE "
        + tableName(aggregateTable)
        + " SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + (aggregateTable == MetalakeAggregateTable.OWNER ? ", updated_at = #{deletedAt}" : "")
        + " WHERE deleted_at = 0 AND deletion_id IS NULL AND ("
        + membershipPredicate(aggregateTable)
        + ")";
  }

  /** Builds a count query for rows in one exact metalake deletion generation. */
  public String countGenerationRows(Map<String, Object> parameters) {
    MetalakeAggregateTable aggregateTable = aggregateTable(parameters);
    return "SELECT COUNT(*) FROM "
        + tableName(aggregateTable)
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the update that restores every row in one exact metalake deletion generation. */
  public String restoreGenerationRows(Map<String, Object> parameters) {
    MetalakeAggregateTable aggregateTable = aggregateTable(parameters);
    return "UPDATE "
        + tableName(aggregateTable)
        + " SET deleted_at = 0, deletion_id = NULL"
        + (aggregateTable == MetalakeAggregateTable.OWNER ? ", updated_at = #{restoredAt}" : "")
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the hard delete for every row in one exact metalake deletion generation. */
  public String hardDeleteGenerationRows(Map<String, Object> parameters) {
    MetalakeAggregateTable aggregateTable = aggregateTable(parameters);
    return "DELETE FROM "
        + tableName(aggregateTable)
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds a query for the newest prior timestamp among rows owned by one metalake tree. */
  public String selectNewestAggregateDeletedAt() {
    String unions =
        Arrays.stream(MetalakeAggregateTable.values())
            .map(
                aggregateTable ->
                    "SELECT MAX(deleted_at) AS deleted_at FROM "
                        + tableName(aggregateTable)
                        + " WHERE "
                        + timestampScope(aggregateTable))
            .collect(Collectors.joining(" UNION ALL "));
    unions +=
        " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
            + " WHERE entity_type = 'METALAKE'";
    return "SELECT MAX(deleted_at) FROM (" + unions + ") metalake_deletions";
  }

  /** Builds a query that counts rows disconnected from their exact-generation parent. */
  public String countBrokenGenerationReferences() {
    String unions =
        String.join(
            " UNION ALL ",
            exactRoot(),
            brokenReference(
                "catalog_meta",
                "child",
                "metalake_meta",
                "parent",
                "parent.metalake_id = child.metalake_id"),
            brokenReference(
                "schema_meta",
                "child",
                "catalog_meta",
                "parent",
                "parent.catalog_id = child.catalog_id"
                    + " AND parent.metalake_id = child.metalake_id"),
            brokenLeaf("table_meta", "child"),
            brokenLeaf("fileset_meta", "child"),
            brokenLeaf("topic_meta", "child"),
            brokenLeaf("function_meta", "child"),
            brokenLeaf("model_meta", "child"),
            brokenLeaf("view_meta", "child"),
            brokenReference(
                "table_column_version_info",
                "child",
                "table_version_info",
                "parent",
                "parent.table_id = child.table_id AND parent.version = child.table_version"),
            brokenReference(
                "table_version_info",
                "child",
                "table_meta",
                "parent",
                "parent.table_id = child.table_id"),
            brokenReference(
                "fileset_version_info",
                "child",
                "fileset_meta",
                "parent",
                "parent.fileset_id = child.fileset_id"),
            brokenReference(
                "function_version_info",
                "child",
                "function_meta",
                "parent",
                "parent.function_id = child.function_id"),
            brokenReference(
                "model_version_info",
                "child",
                "model_meta",
                "parent",
                "parent.model_id = child.model_id"),
            brokenReference(
                "model_version_alias_rel",
                "child",
                "model_version_info",
                "parent",
                "parent.model_id = child.model_id AND parent.version = child.model_version"),
            brokenReference(
                "view_version_info",
                "child",
                "view_meta",
                "parent",
                "parent.view_id = child.view_id"),
            brokenReference(
                "policy_version_info",
                "child",
                "policy_meta",
                "parent",
                "parent.policy_id = child.policy_id"),
            brokenReference(
                "job_run_meta",
                "child",
                "job_template_meta",
                "parent",
                "parent.job_template_id = child.job_template_id"),
            brokenReference(
                "user_role_rel",
                "relation",
                "user_meta",
                "source",
                "source.user_id = relation.user_id"),
            brokenReference(
                "user_role_rel",
                "relation",
                "role_meta",
                "source",
                "source.role_id = relation.role_id"),
            brokenReference(
                "group_role_rel",
                "relation",
                "group_meta",
                "source",
                "source.group_id = relation.group_id"),
            brokenReference(
                "group_role_rel",
                "relation",
                "role_meta",
                "source",
                "source.role_id = relation.role_id"),
            brokenReference(
                "role_meta_securable_object",
                "relation",
                "role_meta",
                "source",
                "source.role_id = relation.role_id"),
            brokenReference(
                "tag_relation_meta",
                "relation",
                "tag_meta",
                "source",
                "source.tag_id = relation.tag_id"),
            brokenReference(
                "policy_relation_meta",
                "relation",
                "policy_meta",
                "source",
                "source.policy_id = relation.policy_id"),
            brokenOwnerPrincipal(),
            brokenTypedRelation(
                "owner_meta", "relation", "metadata_object_type", "metadata_object_id"),
            brokenTypedRelation(
                "role_meta_securable_object", "relation", "type", "metadata_object_id"),
            brokenTypedRelation(
                "tag_relation_meta", "relation", "metadata_object_type", "metadata_object_id"),
            brokenTypedRelation(
                "policy_relation_meta", "relation", "metadata_object_type", "metadata_object_id"),
            brokenTypedRelation(
                "statistic_meta", "relation", "metadata_object_type", "metadata_object_id"));
    return "SELECT SUM(broken) FROM (" + unions + ") broken_references";
  }

  private static MetalakeAggregateTable aggregateTable(Map<String, Object> parameters) {
    Object value = parameters.get("aggregateTable");
    if (!(value instanceof MetalakeAggregateTable)) {
      throw new IllegalArgumentException("aggregateTable must be a MetalakeAggregateTable");
    }
    return (MetalakeAggregateTable) value;
  }

  private static String exactRoot() {
    return "SELECT CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS broken FROM metalake_meta"
        + " WHERE "
        + generation("metalake_meta");
  }

  private static String brokenLeaf(String table, String alias) {
    return brokenReference(
        table,
        alias,
        "schema_meta",
        "parent",
        "parent.schema_id = "
            + alias
            + ".schema_id AND parent.catalog_id = "
            + alias
            + ".catalog_id AND parent.metalake_id = "
            + alias
            + ".metalake_id");
  }

  private static String brokenReference(
      String childTable,
      String childAlias,
      String parentTable,
      String parentAlias,
      String joinPredicate) {
    return "SELECT COUNT(*) AS broken FROM "
        + childTable
        + " "
        + childAlias
        + " WHERE "
        + generation(childAlias)
        + " AND NOT EXISTS (SELECT 1 FROM "
        + parentTable
        + " "
        + parentAlias
        + " WHERE "
        + joinPredicate
        + " AND "
        + generation(parentAlias)
        + ")";
  }

  private static String brokenOwnerPrincipal() {
    return "SELECT COUNT(*) AS broken FROM owner_meta relation WHERE "
        + generation("relation")
        + " AND NOT ((relation.owner_type = 'USER' AND EXISTS (SELECT 1 FROM user_meta owner"
        + " WHERE owner.user_id = relation.owner_id AND "
        + generation("owner")
        + ")) OR (relation.owner_type = 'GROUP' AND EXISTS (SELECT 1 FROM group_meta owner"
        + " WHERE owner.group_id = relation.owner_id AND "
        + generation("owner")
        + ")))";
  }

  private static String brokenTypedRelation(
      String table, String alias, String typeColumn, String idColumn) {
    return "SELECT COUNT(*) AS broken FROM "
        + table
        + " "
        + alias
        + " WHERE "
        + generation(alias)
        + " AND NOT ("
        + generationTargetMembership(alias, typeColumn, idColumn)
        + ")";
  }

  private static String generationTargetMembership(
      String alias, String typeColumn, String idColumn) {
    String type = alias + "." + typeColumn;
    String id = alias + "." + idColumn;
    return generationTarget(type, id, "METALAKE", "metalake_id", "metalake_meta")
        + " OR "
        + generationTarget(type, id, "CATALOG", "catalog_id", "catalog_meta")
        + " OR "
        + generationTarget(type, id, "SCHEMA", "schema_id", "schema_meta")
        + " OR "
        + generationTarget(type, id, "FILESET", "fileset_id", "fileset_meta")
        + " OR "
        + generationTarget(type, id, "TABLE", "table_id", "table_meta")
        + " OR "
        + generationTarget(type, id, "VIEW", "view_id", "view_meta")
        + " OR "
        + generationTarget(type, id, "TOPIC", "topic_id", "topic_meta")
        + " OR "
        + generationTarget(type, id, "COLUMN", "column_id", "table_column_version_info")
        + " OR "
        + generationTarget(type, id, "ROLE", "role_id", "role_meta")
        + " OR "
        + generationTarget(type, id, "MODEL", "model_id", "model_meta")
        + " OR "
        + generationTarget(type, id, "TAG", "tag_id", "tag_meta")
        + " OR "
        + generationTarget(type, id, "POLICY", "policy_id", "policy_meta")
        + " OR "
        + generationTarget(type, id, "JOB", "job_run_id", "job_run_meta")
        + " OR "
        + generationTarget(type, id, "JOB_TEMPLATE", "job_template_id", "job_template_meta")
        + " OR "
        + generationTarget(type, id, "FUNCTION", "function_id", "function_meta");
  }

  private static String generationTarget(
      String typeColumn, String idColumn, String type, String entityIdColumn, String table) {
    return "("
        + typeColumn
        + " = '"
        + type
        + "' AND "
        + idColumn
        + " IN (SELECT "
        + entityIdColumn
        + " FROM "
        + table
        + " target WHERE "
        + generation("target")
        + "))";
  }

  private static String generation(String alias) {
    return alias + ".deleted_at = #{deletedAt} AND " + alias + ".deletion_id = #{deletionId}";
  }

  private static String tableName(MetalakeAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case OWNER:
        return "owner_meta";
      case SECURABLE_OBJECT:
        return "role_meta_securable_object";
      case USER_ROLE:
        return "user_role_rel";
      case GROUP_ROLE:
        return "group_role_rel";
      case TAG_RELATION:
        return "tag_relation_meta";
      case POLICY_RELATION:
        return "policy_relation_meta";
      case STATISTIC:
        return "statistic_meta";
      case TABLE_COLUMN:
        return "table_column_version_info";
      case TABLE_VERSION:
        return "table_version_info";
      case FILESET_VERSION:
        return "fileset_version_info";
      case FUNCTION_VERSION:
        return "function_version_info";
      case MODEL_ALIAS:
        return "model_version_alias_rel";
      case MODEL_VERSION:
        return "model_version_info";
      case VIEW_VERSION:
        return "view_version_info";
      case POLICY_VERSION:
        return "policy_version_info";
      case JOB_RUN:
        return "job_run_meta";
      case TABLE:
        return "table_meta";
      case FILESET:
        return "fileset_meta";
      case TOPIC:
        return "topic_meta";
      case FUNCTION:
        return "function_meta";
      case MODEL:
        return "model_meta";
      case VIEW:
        return "view_meta";
      case JOB_TEMPLATE:
        return "job_template_meta";
      case USER:
        return "user_meta";
      case GROUP:
        return "group_meta";
      case ROLE:
        return "role_meta";
      case TAG:
        return "tag_meta";
      case POLICY:
        return "policy_meta";
      case SCHEMA:
        return "schema_meta";
      case CATALOG:
        return "catalog_meta";
      case METALAKE:
        return "metalake_meta";
      default:
        throw new IllegalArgumentException(
            "Unsupported metalake aggregate table " + aggregateTable);
    }
  }

  private static String membershipPredicate(MetalakeAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case TABLE_VERSION:
        return "table_id IN (SELECT table_id FROM table_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case MODEL_ALIAS:
        return "model_id IN (SELECT model_id FROM model_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case USER_ROLE:
        return "user_id IN (SELECT user_id FROM user_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case GROUP_ROLE:
        return "group_id IN (SELECT group_id FROM group_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case SECURABLE_OBJECT:
        return "role_id IN (SELECT role_id FROM role_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case TAG_RELATION:
        return "tag_id IN (SELECT tag_id FROM tag_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      case POLICY_RELATION:
        return "policy_id IN (SELECT policy_id FROM policy_meta WHERE deleted_at = 0"
            + " AND metalake_id = #{metalakeId})";
      default:
        return "metalake_id = #{metalakeId}";
    }
  }

  private static String timestampScope(MetalakeAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case METALAKE:
        // Metalake names are globally unique but immediately reusable after deletion. Scanning all
        // roots prevents a new immutable ID from reusing the same (name, deleted_at) key.
        return "1 = 1";
      case TABLE_VERSION:
        return "table_id IN (SELECT table_id FROM table_meta"
            + " WHERE metalake_id = #{metalakeId})";
      case MODEL_ALIAS:
        return "model_id IN (SELECT model_id FROM model_meta"
            + " WHERE metalake_id = #{metalakeId})";
      case USER_ROLE:
        return "user_id IN (SELECT user_id FROM user_meta" + " WHERE metalake_id = #{metalakeId})";
      case GROUP_ROLE:
        return "group_id IN (SELECT group_id FROM group_meta"
            + " WHERE metalake_id = #{metalakeId})";
      case SECURABLE_OBJECT:
        return "role_id IN (SELECT role_id FROM role_meta" + " WHERE metalake_id = #{metalakeId})";
      case TAG_RELATION:
        return "tag_id IN (SELECT tag_id FROM tag_meta" + " WHERE metalake_id = #{metalakeId})";
      case POLICY_RELATION:
        return "policy_id IN (SELECT policy_id FROM policy_meta"
            + " WHERE metalake_id = #{metalakeId})";
      default:
        return "metalake_id = #{metalakeId}";
    }
  }
}
