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

/** SQL provider for generation-scoped catalog-tree deletion, restore, and purge. */
public class CatalogRecoverySQLProvider {

  /** Builds the update that stamps live rows belonging to one catalog tree. */
  public String softDeleteAggregateRows(Map<String, Object> parameters) {
    CatalogAggregateTable aggregateTable = aggregateTable(parameters);
    return "UPDATE "
        + tableName(aggregateTable)
        + " SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + (aggregateTable == CatalogAggregateTable.OWNER ? ", updated_at = #{deletedAt}" : "")
        + " WHERE deleted_at = 0 AND deletion_id IS NULL AND ("
        + membershipPredicate(aggregateTable)
        + ")";
  }

  /** Builds a count query for rows in one exact catalog deletion generation. */
  public String countGenerationRows(Map<String, Object> parameters) {
    CatalogAggregateTable aggregateTable = aggregateTable(parameters);
    return "SELECT COUNT(*) FROM "
        + tableName(aggregateTable)
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the update that restores every row in one exact catalog deletion generation. */
  public String restoreGenerationRows(Map<String, Object> parameters) {
    CatalogAggregateTable aggregateTable = aggregateTable(parameters);
    return "UPDATE "
        + tableName(aggregateTable)
        + " SET deleted_at = 0, deletion_id = NULL"
        + (aggregateTable == CatalogAggregateTable.OWNER ? ", updated_at = #{restoredAt}" : "")
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the hard delete for every row in one exact catalog deletion generation. */
  public String hardDeleteGenerationRows(Map<String, Object> parameters) {
    CatalogAggregateTable aggregateTable = aggregateTable(parameters);
    return "DELETE FROM "
        + tableName(aggregateTable)
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds a query for the newest prior timestamp among rows owned by one catalog tree. */
  public String selectNewestAggregateDeletedAt() {
    String unions =
        Arrays.stream(CatalogAggregateTable.values())
            .map(
                aggregateTable ->
                    "SELECT MAX(deleted_at) AS deleted_at FROM "
                        + tableName(aggregateTable)
                        + " WHERE "
                        + timestampScope(aggregateTable))
            .collect(Collectors.joining(" UNION ALL "));
    unions +=
        " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
            + " WHERE entity_type = 'CATALOG' AND entity_id = #{catalogId}";
    return "SELECT MAX(deleted_at) FROM (" + unions + ") catalog_deletions";
  }

  /** Builds a query that counts rows disconnected from their exact-generation parent. */
  public String countBrokenGenerationReferences() {
    String exactRoot =
        "SELECT CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS broken FROM catalog_meta"
            + " WHERE "
            + generation("catalog_meta");
    String schemaToCatalog =
        brokenReference(
            "schema_meta",
            "child",
            "catalog_meta",
            "parent",
            "parent.catalog_id = child.catalog_id" + " AND parent.metalake_id = child.metalake_id");
    String unions =
        String.join(
            " UNION ALL ",
            exactRoot,
            schemaToCatalog,
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
                "parent.table_id = child.table_id" + " AND parent.version = child.table_version"),
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
                "parent.fileset_id = child.fileset_id"
                    + " AND parent.schema_id = child.schema_id"
                    + " AND parent.catalog_id = child.catalog_id"
                    + " AND parent.metalake_id = child.metalake_id"),
            brokenReference(
                "function_version_info",
                "child",
                "function_meta",
                "parent",
                "parent.function_id = child.function_id"
                    + " AND parent.schema_id = child.schema_id"
                    + " AND parent.catalog_id = child.catalog_id"
                    + " AND parent.metalake_id = child.metalake_id"),
            brokenReference(
                "model_version_info",
                "child",
                "model_meta",
                "parent",
                "parent.model_id = child.model_id"
                    + " AND parent.schema_id = child.schema_id"
                    + " AND parent.catalog_id = child.catalog_id"
                    + " AND parent.metalake_id = child.metalake_id"),
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
                "parent.view_id = child.view_id"
                    + " AND parent.schema_id = child.schema_id"
                    + " AND parent.catalog_id = child.catalog_id"
                    + " AND parent.metalake_id = child.metalake_id"),
            brokenRelation(
                "owner_meta",
                "relation",
                "metadata_object_type",
                "metadata_object_id",
                true,
                false),
            brokenRelation(
                "role_meta_securable_object",
                "relation",
                "type",
                "metadata_object_id",
                true,
                false),
            brokenRelation(
                "tag_relation_meta",
                "relation",
                "metadata_object_type",
                "metadata_object_id",
                true,
                true),
            brokenRelation(
                "policy_relation_meta",
                "relation",
                "metadata_object_type",
                "metadata_object_id",
                false,
                false),
            brokenRelation(
                "statistic_meta",
                "relation",
                "metadata_object_type",
                "metadata_object_id",
                false,
                false),
            brokenOwnerPrincipal(),
            brokenLiveReference(
                "role_meta_securable_object",
                "relation",
                "role_id",
                "role_meta",
                "role",
                "role_id"),
            brokenLiveReference(
                "tag_relation_meta", "relation", "tag_id", "tag_meta", "tag", "tag_id"),
            brokenLiveReference(
                "policy_relation_meta",
                "relation",
                "policy_id",
                "policy_meta",
                "policy",
                "policy_id"));
    return "SELECT SUM(broken) FROM (" + unions + ") broken_references";
  }

  private static CatalogAggregateTable aggregateTable(Map<String, Object> parameters) {
    Object value = parameters.get("aggregateTable");
    if (!(value instanceof CatalogAggregateTable)) {
      throw new IllegalArgumentException("aggregateTable must be a CatalogAggregateTable");
    }
    return (CatalogAggregateTable) value;
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

  private static String brokenRelation(
      String table,
      String alias,
      String typeColumn,
      String idColumn,
      boolean includeViewAndFunction,
      boolean includeColumn) {
    return "SELECT COUNT(*) AS broken FROM "
        + table
        + " "
        + alias
        + " WHERE "
        + generation(alias)
        + " AND NOT ("
        + generationTargetMembership(
            alias, typeColumn, idColumn, includeViewAndFunction, includeColumn)
        + ")";
  }

  private static String brokenOwnerPrincipal() {
    return "SELECT COUNT(*) AS broken FROM owner_meta relation WHERE "
        + generation("relation")
        + " AND NOT ((relation.owner_type = 'USER' AND EXISTS (SELECT 1 FROM user_meta owner"
        + " WHERE owner.user_id = relation.owner_id"
        + " AND owner.metalake_id = relation.metalake_id AND owner.deleted_at = 0))"
        + " OR (relation.owner_type = 'GROUP' AND EXISTS (SELECT 1 FROM group_meta owner"
        + " WHERE owner.group_id = relation.owner_id"
        + " AND owner.metalake_id = relation.metalake_id AND owner.deleted_at = 0)))";
  }

  private static String brokenLiveReference(
      String childTable,
      String childAlias,
      String childIdColumn,
      String parentTable,
      String parentAlias,
      String parentIdColumn) {
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
        + parentAlias
        + "."
        + parentIdColumn
        + " = "
        + childAlias
        + "."
        + childIdColumn
        + " AND "
        + parentAlias
        + ".deleted_at = 0"
        + ")";
  }

  private static String generationTargetMembership(
      String alias,
      String typeColumn,
      String idColumn,
      boolean includeViewAndFunction,
      boolean includeColumn) {
    String qualifiedType = alias + "." + typeColumn;
    String qualifiedId = alias + "." + idColumn;
    String predicate =
        generationTarget(qualifiedType, qualifiedId, "CATALOG", "catalog_id", "catalog_meta")
            + " OR "
            + generationTarget(qualifiedType, qualifiedId, "SCHEMA", "schema_id", "schema_meta")
            + " OR "
            + generationTarget(qualifiedType, qualifiedId, "TOPIC", "topic_id", "topic_meta")
            + " OR "
            + generationTarget(qualifiedType, qualifiedId, "TABLE", "table_id", "table_meta")
            + " OR "
            + generationTarget(qualifiedType, qualifiedId, "FILESET", "fileset_id", "fileset_meta")
            + " OR "
            + generationTarget(qualifiedType, qualifiedId, "MODEL", "model_id", "model_meta");
    if (includeViewAndFunction) {
      predicate +=
          " OR "
              + generationTarget(qualifiedType, qualifiedId, "VIEW", "view_id", "view_meta")
              + " OR "
              + generationTarget(
                  qualifiedType, qualifiedId, "FUNCTION", "function_id", "function_meta");
    }
    if (includeColumn) {
      predicate +=
          " OR "
              + generationTarget(
                  qualifiedType, qualifiedId, "COLUMN", "column_id", "table_column_version_info");
    }
    return predicate;
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

  private static String tableName(CatalogAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case OWNER:
        return "owner_meta";
      case SECURABLE_OBJECT:
        return "role_meta_securable_object";
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
      case SCHEMA:
        return "schema_meta";
      case CATALOG:
        return "catalog_meta";
      default:
        throw new IllegalArgumentException("Unsupported catalog aggregate table " + aggregateTable);
    }
  }

  private static String membershipPredicate(CatalogAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case CATALOG:
        return "catalog_id = #{catalogId}";
      case SCHEMA:
      case TABLE:
      case FILESET:
      case TOPIC:
      case FUNCTION:
      case MODEL:
      case VIEW:
        return "catalog_id = #{catalogId}";
      case TABLE_COLUMN:
      case TABLE_VERSION:
        return "table_id IN (SELECT table_id FROM table_meta WHERE deleted_at = 0"
            + " AND catalog_id = #{catalogId})";
      case FILESET_VERSION:
        return "fileset_id IN (SELECT fileset_id FROM fileset_meta WHERE deleted_at = 0"
            + " AND catalog_id = #{catalogId})";
      case FUNCTION_VERSION:
        return "function_id IN (SELECT function_id FROM function_meta WHERE deleted_at = 0"
            + " AND catalog_id = #{catalogId})";
      case MODEL_VERSION:
      case MODEL_ALIAS:
        return "model_id IN (SELECT model_id FROM model_meta WHERE deleted_at = 0"
            + " AND catalog_id = #{catalogId})";
      case VIEW_VERSION:
        return "view_id IN (SELECT view_id FROM view_meta WHERE deleted_at = 0"
            + " AND catalog_id = #{catalogId})";
      case OWNER:
        return relationMembership("metadata_object_type", "metadata_object_id", false);
      case SECURABLE_OBJECT:
        return relationMembership("type", "metadata_object_id", false);
      case TAG_RELATION:
        return relationMembership("metadata_object_type", "metadata_object_id", true);
      case POLICY_RELATION:
      case STATISTIC:
        return policyOrStatisticMembership("metadata_object_type", "metadata_object_id");
      default:
        throw new IllegalArgumentException("Unsupported catalog aggregate table " + aggregateTable);
    }
  }

  private static String relationMembership(String typeColumn, String idColumn, boolean columns) {
    String predicate =
        directCatalog(typeColumn, idColumn)
            + " OR "
            + directObject(typeColumn, idColumn, "SCHEMA", "schema_id", "schema_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "TOPIC", "topic_id", "topic_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "TABLE", "table_id", "table_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "FILESET", "fileset_id", "fileset_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "MODEL", "model_id", "model_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "VIEW", "view_id", "view_meta")
            + " OR "
            + directObject(typeColumn, idColumn, "FUNCTION", "function_id", "function_meta");
    if (columns) {
      predicate +=
          " OR ("
              + typeColumn
              + " = 'COLUMN' AND "
              + idColumn
              + " IN (SELECT tc.column_id FROM table_column_version_info tc"
              + " INNER JOIN table_meta tm ON tm.table_id = tc.table_id"
              + " WHERE tc.deleted_at = 0 AND tm.deleted_at = 0"
              + " AND tc.catalog_id = #{catalogId}))";
    }
    return predicate;
  }

  private static String policyOrStatisticMembership(String typeColumn, String idColumn) {
    return directCatalog(typeColumn, idColumn)
        + " OR "
        + directObject(typeColumn, idColumn, "SCHEMA", "schema_id", "schema_meta")
        + " OR "
        + directObject(typeColumn, idColumn, "TOPIC", "topic_id", "topic_meta")
        + " OR "
        + directObject(typeColumn, idColumn, "TABLE", "table_id", "table_meta")
        + " OR "
        + directObject(typeColumn, idColumn, "FILESET", "fileset_id", "fileset_meta")
        + " OR "
        + directObject(typeColumn, idColumn, "MODEL", "model_id", "model_meta");
  }

  private static String directCatalog(String typeColumn, String idColumn) {
    return "(" + typeColumn + " = 'CATALOG' AND " + idColumn + " = #{catalogId})";
  }

  private static String directObject(
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
        + " WHERE deleted_at = 0 AND catalog_id = #{catalogId}))";
  }

  private static String timestampScope(CatalogAggregateTable aggregateTable) {
    switch (aggregateTable) {
      case CATALOG:
        return "metalake_id = (SELECT metalake_id FROM catalog_meta"
            + " WHERE catalog_id = #{catalogId})";
      case SCHEMA:
      case TABLE_COLUMN:
      case FILESET_VERSION:
      case FUNCTION_VERSION:
      case MODEL_VERSION:
      case VIEW_VERSION:
      case TABLE:
      case FILESET:
      case TOPIC:
      case FUNCTION:
      case MODEL:
      case VIEW:
        return "catalog_id = #{catalogId}";
      case TABLE_VERSION:
        return "table_id IN (SELECT table_id FROM table_meta WHERE catalog_id = #{catalogId})";
      case MODEL_ALIAS:
        return "model_id IN (SELECT model_id FROM model_meta WHERE catalog_id = #{catalogId})";
      case OWNER:
      case SECURABLE_OBJECT:
      case TAG_RELATION:
      case POLICY_RELATION:
      case STATISTIC:
        return "1 = 1";
      default:
        throw new IllegalArgumentException("Unsupported catalog aggregate table " + aggregateTable);
    }
  }
}
