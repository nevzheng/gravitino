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

/** SQL provider for generation-scoped user, group, and role deletion and recovery. */
public class IdentityRecoverySQLProvider {

  private static final String USER_COLUMNS =
      "user_id, user_name, metalake_id, external_id, enabled, audit_info, current_version,"
          + " last_version, deleted_at, deletion_id";
  private static final String GROUP_COLUMNS =
      "group_id, group_name, metalake_id, external_id, audit_info, current_version,"
          + " last_version, deleted_at, deletion_id";
  private static final String ROLE_COLUMNS =
      "role_id, role_name, metalake_id, properties, audit_info, current_version,"
          + " last_version, deleted_at, deletion_id";

  /** Builds the locking lookup for one live user. */
  public String lockLiveUser() {
    return selectLiveUser() + " AND user_name = #{userName} ORDER BY user_id FOR UPDATE";
  }

  /** Builds the locking lookup for one live group. */
  public String lockLiveGroup() {
    return selectLiveGroup() + " AND group_name = #{groupName} ORDER BY group_id FOR UPDATE";
  }

  /** Builds the locking lookup for one live role. */
  public String lockLiveRole() {
    return selectLiveRole() + " AND role_name = #{roleName} ORDER BY role_id FOR UPDATE";
  }

  /** Builds the locking lookup for deleted users whose immutable IDs cannot be overwritten. */
  public String selectDeletedUsersForUpdate() {
    return deletedRootsForUpdate("user_meta", USER_COLUMNS, "user_id", "userIds", "userId");
  }

  /** Builds the locking lookup for deleted groups whose immutable IDs cannot be overwritten. */
  public String selectDeletedGroupsForUpdate() {
    return deletedRootsForUpdate("group_meta", GROUP_COLUMNS, "group_id", "groupIds", "groupId");
  }

  /** Builds the locking lookup for deleted roles whose immutable IDs cannot be overwritten. */
  public String selectDeletedRolesForUpdate() {
    return deletedRootsForUpdate("role_meta", ROLE_COLUMNS, "role_id", "roleIds", "roleId");
  }

  /** Builds the read for live users below one metalake. */
  public String listLiveUsers() {
    return selectLiveUser() + " ORDER BY user_id";
  }

  /** Builds the read for live groups below one metalake. */
  public String listLiveGroups() {
    return selectLiveGroup() + " ORDER BY group_id";
  }

  /** Builds the read for live roles below one metalake. */
  public String listLiveRoles() {
    return selectLiveRole() + " ORDER BY role_id";
  }

  /** Builds the read for globally live users matching immutable IDs. */
  public String listLiveUsersByIds() {
    return liveRootsByIds("user_meta", USER_COLUMNS, "user_id", "userIds", "userId");
  }

  /** Builds the read for globally live groups matching immutable IDs. */
  public String listLiveGroupsByIds() {
    return liveRootsByIds("group_meta", GROUP_COLUMNS, "group_id", "groupIds", "groupId");
  }

  /** Builds the read for globally live roles matching immutable IDs. */
  public String listLiveRolesByIds() {
    return liveRootsByIds("role_meta", ROLE_COLUMNS, "role_id", "roleIds", "roleId");
  }

  /** Builds the read for independently deleted user roots below one live metalake. */
  public String listDeletedRootUsers() {
    return listDeletedRoots("USER", "user_meta", "um", USER_COLUMNS, "user_id");
  }

  /** Builds the read for independently deleted group roots below one live metalake. */
  public String listDeletedRootGroups() {
    return listDeletedRoots("GROUP", "group_meta", "gm", GROUP_COLUMNS, "group_id");
  }

  /** Builds the read for independently deleted role roots below one live metalake. */
  public String listDeletedRootRoles() {
    return listDeletedRoots("ROLE", "role_meta", "rm", ROLE_COLUMNS, "role_id");
  }

  /** Builds a locking read for live users occupying a name or non-null external ID. */
  public String listLiveUserOccupantsForUpdate() {
    return "<script>SELECT "
        + USER_COLUMNS
        + " FROM user_meta WHERE metalake_id = #{metalakeId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL AND (user_name = #{userName}"
        + "<if test='externalId != null'> OR external_id = #{externalId}</if>)"
        + " ORDER BY user_id FOR UPDATE</script>";
  }

  /** Builds a locking read for live groups occupying a name or non-null external ID. */
  public String listLiveGroupOccupantsForUpdate() {
    return "<script>SELECT "
        + GROUP_COLUMNS
        + " FROM group_meta WHERE metalake_id = #{metalakeId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL AND (group_name = #{groupName}"
        + "<if test='externalId != null'> OR external_id = #{externalId}</if>)"
        + " ORDER BY group_id FOR UPDATE</script>";
  }

  /** Builds the locking lookup for one exact user deletion generation. */
  public String selectUserGenerationForUpdate() {
    return exactGeneration("user_meta", USER_COLUMNS, "user_id", "userId");
  }

  /** Builds the locking lookup for one exact group deletion generation. */
  public String selectGroupGenerationForUpdate() {
    return exactGeneration("group_meta", GROUP_COLUMNS, "group_id", "groupId");
  }

  /** Builds the locking lookup for one exact role deletion generation. */
  public String selectRoleGenerationForUpdate() {
    return exactGeneration("role_meta", ROLE_COLUMNS, "role_id", "roleId");
  }

  /** Builds the query for the newest tombstone timestamp touching one user aggregate. */
  public String selectNewestUserDeletedAt() {
    return "<script>SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM user_meta WHERE user_id = #{userId}"
        + " OR (metalake_id = #{metalakeId} AND user_name = #{userName})"
        + "<if test='externalId != null'> OR (metalake_id = #{metalakeId}"
        + " AND external_id = #{externalId})</if>"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM user_role_rel"
        + " WHERE user_id = #{userId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM owner_meta"
        + " WHERE owner_id = #{userId} AND owner_type = 'USER'"
        + deletionTimestampReceipt("USER", "userId", "userName")
        + ") user_deletions</script>";
  }

  /** Builds the query for the newest tombstone timestamp touching one group aggregate. */
  public String selectNewestGroupDeletedAt() {
    return "<script>SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM group_meta WHERE group_id = #{groupId}"
        + " OR (metalake_id = #{metalakeId} AND group_name = #{groupName})"
        + "<if test='externalId != null'> OR (metalake_id = #{metalakeId}"
        + " AND external_id = #{externalId})</if>"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM group_role_rel"
        + " WHERE group_id = #{groupId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM owner_meta"
        + " WHERE owner_id = #{groupId} AND owner_type = 'GROUP'"
        + deletionTimestampReceipt("GROUP", "groupId", "groupName")
        + ") group_deletions</script>";
  }

  /** Builds the query for the newest tombstone timestamp touching one role aggregate. */
  public String selectNewestRoleDeletedAt() {
    return "SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM role_meta WHERE role_id = #{roleId}"
        + " OR (metalake_id = #{metalakeId} AND role_name = #{roleName})"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM user_role_rel"
        + " WHERE role_id = #{roleId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM group_role_rel"
        + " WHERE role_id = #{roleId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM role_meta_securable_object"
        + " WHERE role_id = #{roleId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM owner_meta"
        + " WHERE metadata_object_id = #{roleId} AND metadata_object_type = 'ROLE'"
        + deletionTimestampReceipt("ROLE", "roleId", "roleName")
        + ") role_deletions";
  }

  /** Builds the update that stamps live rows belonging to one standalone user aggregate. */
  public String softDeleteUserAggregateRows(Map<String, Object> parameters) {
    UserAggregateTable table = userAggregateTable(parameters);
    return softDeleteAggregateRows(userTableName(table), userMembership(table));
  }

  /** Builds the update that stamps live rows belonging to one standalone group aggregate. */
  public String softDeleteGroupAggregateRows(Map<String, Object> parameters) {
    GroupAggregateTable table = groupAggregateTable(parameters);
    return softDeleteAggregateRows(groupTableName(table), groupMembership(table));
  }

  /** Builds the update that stamps live rows belonging to one standalone role aggregate. */
  public String softDeleteRoleAggregateRows(Map<String, Object> parameters) {
    RoleAggregateTable table = roleAggregateTable(parameters);
    return softDeleteAggregateRows(roleTableName(table), roleMembership(table));
  }

  /** Builds a count for rows captured in one exact user deletion generation. */
  public String countUserGenerationRows(Map<String, Object> parameters) {
    return countGenerationRows(userTableName(userAggregateTable(parameters)));
  }

  /** Builds a count for rows captured in one exact group deletion generation. */
  public String countGroupGenerationRows(Map<String, Object> parameters) {
    return countGenerationRows(groupTableName(groupAggregateTable(parameters)));
  }

  /** Builds a count for rows captured in one exact role deletion generation. */
  public String countRoleGenerationRows(Map<String, Object> parameters) {
    return countGenerationRows(roleTableName(roleAggregateTable(parameters)));
  }

  /** Builds the exact-generation restore for one user aggregate table. */
  public String restoreUserGenerationRows(Map<String, Object> parameters) {
    UserAggregateTable table = userAggregateTable(parameters);
    return restoreGenerationRows(userTableName(table), table == UserAggregateTable.OWNER);
  }

  /** Builds the exact-generation restore for one group aggregate table. */
  public String restoreGroupGenerationRows(Map<String, Object> parameters) {
    GroupAggregateTable table = groupAggregateTable(parameters);
    return restoreGenerationRows(groupTableName(table), table == GroupAggregateTable.OWNER);
  }

  /** Builds the exact-generation restore for one role aggregate table. */
  public String restoreRoleGenerationRows(Map<String, Object> parameters) {
    RoleAggregateTable table = roleAggregateTable(parameters);
    return restoreGenerationRows(roleTableName(table), table == RoleAggregateTable.OWNER);
  }

  /** Builds the exact-generation purge for one user aggregate table. */
  public String hardDeleteUserGenerationRows(Map<String, Object> parameters) {
    return hardDeleteGenerationRows(userTableName(userAggregateTable(parameters)));
  }

  /** Builds the exact-generation purge for one group aggregate table. */
  public String hardDeleteGroupGenerationRows(Map<String, Object> parameters) {
    return hardDeleteGenerationRows(groupTableName(groupAggregateTable(parameters)));
  }

  /** Builds the exact-generation purge for one role aggregate table. */
  public String hardDeleteRoleGenerationRows(Map<String, Object> parameters) {
    return hardDeleteGenerationRows(roleTableName(roleAggregateTable(parameters)));
  }

  /** Builds the aggregate-integrity check for one exact user deletion generation. */
  public String countBrokenUserGenerationReferences() {
    return brokenReferenceSum(
        exactRoot("user_meta", "user_id"),
        brokenMembership("user_role_rel", "relation", "relation.user_id = #{entityId}"),
        brokenMembership(
            "owner_meta",
            "relation",
            "relation.owner_id = #{entityId} AND relation.owner_type = 'USER'"));
  }

  /** Builds the aggregate-integrity check for one exact group deletion generation. */
  public String countBrokenGroupGenerationReferences() {
    return brokenReferenceSum(
        exactRoot("group_meta", "group_id"),
        brokenMembership("group_role_rel", "relation", "relation.group_id = #{entityId}"),
        brokenMembership(
            "owner_meta",
            "relation",
            "relation.owner_id = #{entityId} AND relation.owner_type = 'GROUP'"));
  }

  /** Builds the aggregate-integrity check for one exact role deletion generation. */
  public String countBrokenRoleGenerationReferences() {
    return brokenReferenceSum(
        exactRoot("role_meta", "role_id"),
        brokenMembership("user_role_rel", "relation", "relation.role_id = #{entityId}"),
        brokenMembership("group_role_rel", "relation", "relation.role_id = #{entityId}"),
        brokenMembership(
            "role_meta_securable_object", "relation", "relation.role_id = #{entityId}"),
        brokenMembership(
            "owner_meta",
            "relation",
            "relation.metadata_object_id = #{entityId}"
                + " AND relation.metadata_object_type = 'ROLE'"));
  }

  /** Builds the locking read for legacy user roots eligible for permanent cleanup. */
  public String selectLegacyUserRootsForUpdate() {
    return legacyRootsForUpdate("user_meta", USER_COLUMNS, "user_id");
  }

  /** Builds the locking read for legacy group roots eligible for permanent cleanup. */
  public String selectLegacyGroupRootsForUpdate() {
    return legacyRootsForUpdate("group_meta", GROUP_COLUMNS, "group_id");
  }

  /** Builds the locking read for legacy role roots eligible for permanent cleanup. */
  public String selectLegacyRoleRootsForUpdate() {
    return legacyRootsForUpdate("role_meta", ROLE_COLUMNS, "role_id");
  }

  /** Builds the legacy cleanup for one user aggregate table. */
  public String hardDeleteLegacyUserAggregateRows(Map<String, Object> parameters) {
    UserAggregateTable table = userAggregateTable(parameters);
    return hardDeleteLegacyRows(
        userTableName(table), userLegacyMembership(table), "userIds", "userId");
  }

  /** Builds the legacy cleanup for one group aggregate table. */
  public String hardDeleteLegacyGroupAggregateRows(Map<String, Object> parameters) {
    GroupAggregateTable table = groupAggregateTable(parameters);
    return hardDeleteLegacyRows(
        groupTableName(table), groupLegacyMembership(table), "groupIds", "groupId");
  }

  /** Builds the legacy cleanup for one role aggregate table. */
  public String hardDeleteLegacyRoleAggregateRows(Map<String, Object> parameters) {
    RoleAggregateTable table = roleAggregateTable(parameters);
    return hardDeleteLegacyRows(
        roleTableName(table), roleLegacyMembership(table), "roleIds", "roleId");
  }

  private static String selectLiveUser() {
    return "SELECT "
        + USER_COLUMNS
        + " FROM user_meta WHERE metalake_id = #{metalakeId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  private static String selectLiveGroup() {
    return "SELECT "
        + GROUP_COLUMNS
        + " FROM group_meta WHERE metalake_id = #{metalakeId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  private static String selectLiveRole() {
    return "SELECT "
        + ROLE_COLUMNS
        + " FROM role_meta WHERE metalake_id = #{metalakeId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  private static String deletedRootsForUpdate(
      String table, String columns, String idColumn, String collection, String item) {
    return "<script>SELECT "
        + columns
        + " FROM "
        + table
        + " WHERE deleted_at > 0 AND deletion_id IS NOT NULL AND "
        + idColumn
        + " IN "
        + idList(collection, item)
        + " ORDER BY "
        + idColumn
        + " FOR UPDATE</script>";
  }

  private static String liveRootsByIds(
      String table, String columns, String idColumn, String collection, String item) {
    return "<script>SELECT "
        + columns
        + " FROM "
        + table
        + " WHERE deleted_at = 0 AND deletion_id IS NULL AND "
        + idColumn
        + " IN "
        + idList(collection, item)
        + " ORDER BY "
        + idColumn
        + "</script>";
  }

  private static String listDeletedRoots(
      String entityType, String table, String alias, String columns, String idColumn) {
    String qualifiedColumns =
        Arrays.stream(columns.split(", "))
            .map(column -> alias + "." + column)
            .collect(Collectors.joining(", "));
    return "SELECT "
        + qualifiedColumns
        + " FROM "
        + table
        + " "
        + alias
        + " LEFT JOIN entity_deletion ed ON ed.entity_type = '"
        + entityType
        + "' AND ed.entity_id = "
        + alias
        + "."
        + idColumn
        + " AND ed.deleted_at = "
        + alias
        + ".deleted_at AND ed.deletion_id = "
        + alias
        + ".deletion_id WHERE "
        + alias
        + ".metalake_id = #{metalakeId} AND "
        + alias
        + ".deleted_at > 0 AND ("
        + alias
        + ".deletion_id IS NULL OR ("
        + alias
        + ".deletion_id IS NOT NULL AND ed.metalake_id = #{metalakeId}"
        + " AND ed.parent_id = #{metalakeId})) ORDER BY "
        + alias
        + ".deleted_at DESC, "
        + alias
        + "."
        + idColumn
        + " DESC";
  }

  private static String exactGeneration(
      String table, String columns, String idColumn, String idParameter) {
    return "SELECT "
        + columns
        + " FROM "
        + table
        + " WHERE "
        + idColumn
        + " = #{"
        + idParameter
        + "} AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId} FOR UPDATE";
  }

  private static String deletionTimestampReceipt(
      String entityType, String idParameter, String nameParameter) {
    return " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
        + " WHERE entity_type = '"
        + entityType
        + "' AND (entity_id = #{"
        + idParameter
        + "} OR (parent_id = #{metalakeId} AND entity_name = #{"
        + nameParameter
        + "}))";
  }

  private static String softDeleteAggregateRows(String table, String membership) {
    return "UPDATE "
        + table
        + " SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + ("owner_meta".equals(table) ? ", updated_at = #{deletedAt}" : "")
        + " WHERE deleted_at = 0 AND deletion_id IS NULL AND ("
        + membership
        + ")";
  }

  private static String countGenerationRows(String table) {
    return "SELECT COUNT(*) FROM "
        + table
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  private static String restoreGenerationRows(String table, boolean owner) {
    return "UPDATE "
        + table
        + " SET deleted_at = 0, deletion_id = NULL"
        + (owner ? ", updated_at = #{restoredAt}" : "")
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  private static String hardDeleteGenerationRows(String table) {
    return "DELETE FROM "
        + table
        + " WHERE deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  private static String brokenReferenceSum(String... branches) {
    return "SELECT SUM(broken) FROM ("
        + String.join(" UNION ALL ", branches)
        + ") broken_identity_references";
  }

  private static String exactRoot(String table, String idColumn) {
    return "SELECT CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS broken FROM "
        + table
        + " WHERE "
        + idColumn
        + " = #{entityId} AND "
        + generation(table);
  }

  private static String brokenMembership(String table, String alias, String membership) {
    return "SELECT COUNT(*) AS broken FROM "
        + table
        + " "
        + alias
        + " WHERE "
        + generation(alias)
        + " AND NOT ("
        + membership
        + ")";
  }

  private static String generation(String alias) {
    return alias + ".deleted_at = #{deletedAt} AND " + alias + ".deletion_id = #{deletionId}";
  }

  private static String legacyRootsForUpdate(String table, String columns, String idColumn) {
    return "SELECT "
        + columns
        + " FROM "
        + table
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline}"
        + " AND deletion_id IS NULL ORDER BY deleted_at, "
        + idColumn
        + " LIMIT #{limit} FOR UPDATE";
  }

  private static String hardDeleteLegacyRows(
      String table, String membership, String collection, String item) {
    return "<script>DELETE FROM "
        + table
        + " WHERE deleted_at > 0 AND deleted_at &lt; #{legacyTimeline}"
        + " AND deletion_id IS NULL AND ("
        + membership.replace("#{entityIds}", idList(collection, item))
        + ")</script>";
  }

  private static String idList(String collection, String item) {
    return "<foreach collection='"
        + collection
        + "' item='"
        + item
        + "' open='(' separator=',' close=')'>#{"
        + item
        + "}</foreach>";
  }

  private static UserAggregateTable userAggregateTable(Map<String, Object> parameters) {
    Object value = parameters.get("aggregateTable");
    if (!(value instanceof UserAggregateTable)) {
      throw new IllegalArgumentException("aggregateTable must be a UserAggregateTable");
    }
    return (UserAggregateTable) value;
  }

  private static GroupAggregateTable groupAggregateTable(Map<String, Object> parameters) {
    Object value = parameters.get("aggregateTable");
    if (!(value instanceof GroupAggregateTable)) {
      throw new IllegalArgumentException("aggregateTable must be a GroupAggregateTable");
    }
    return (GroupAggregateTable) value;
  }

  private static RoleAggregateTable roleAggregateTable(Map<String, Object> parameters) {
    Object value = parameters.get("aggregateTable");
    if (!(value instanceof RoleAggregateTable)) {
      throw new IllegalArgumentException("aggregateTable must be a RoleAggregateTable");
    }
    return (RoleAggregateTable) value;
  }

  private static String userTableName(UserAggregateTable table) {
    switch (table) {
      case USER_ROLE:
        return "user_role_rel";
      case OWNER:
        return "owner_meta";
      case USER:
        return "user_meta";
      default:
        throw new IllegalArgumentException("Unsupported user aggregate table " + table);
    }
  }

  private static String groupTableName(GroupAggregateTable table) {
    switch (table) {
      case GROUP_ROLE:
        return "group_role_rel";
      case OWNER:
        return "owner_meta";
      case GROUP:
        return "group_meta";
      default:
        throw new IllegalArgumentException("Unsupported group aggregate table " + table);
    }
  }

  private static String roleTableName(RoleAggregateTable table) {
    switch (table) {
      case USER_ROLE:
        return "user_role_rel";
      case GROUP_ROLE:
        return "group_role_rel";
      case SECURABLE_OBJECT:
        return "role_meta_securable_object";
      case OWNER:
        return "owner_meta";
      case ROLE:
        return "role_meta";
      default:
        throw new IllegalArgumentException("Unsupported role aggregate table " + table);
    }
  }

  private static String userMembership(UserAggregateTable table) {
    switch (table) {
      case USER_ROLE:
        return "user_id = #{entityId}";
      case OWNER:
        return "owner_id = #{entityId} AND owner_type = 'USER'";
      case USER:
        return "user_id = #{entityId}";
      default:
        throw new IllegalArgumentException("Unsupported user aggregate table " + table);
    }
  }

  private static String groupMembership(GroupAggregateTable table) {
    switch (table) {
      case GROUP_ROLE:
        return "group_id = #{entityId}";
      case OWNER:
        return "owner_id = #{entityId} AND owner_type = 'GROUP'";
      case GROUP:
        return "group_id = #{entityId}";
      default:
        throw new IllegalArgumentException("Unsupported group aggregate table " + table);
    }
  }

  private static String roleMembership(RoleAggregateTable table) {
    switch (table) {
      case USER_ROLE:
      case GROUP_ROLE:
      case SECURABLE_OBJECT:
        return "role_id = #{entityId}";
      case OWNER:
        return "metadata_object_id = #{entityId} AND metadata_object_type = 'ROLE'";
      case ROLE:
        return "role_id = #{entityId}";
      default:
        throw new IllegalArgumentException("Unsupported role aggregate table " + table);
    }
  }

  private static String userLegacyMembership(UserAggregateTable table) {
    switch (table) {
      case USER_ROLE:
        return "user_id IN #{entityIds}";
      case OWNER:
        return "owner_id IN #{entityIds} AND owner_type = 'USER'";
      case USER:
        return "user_id IN #{entityIds}";
      default:
        throw new IllegalArgumentException("Unsupported user aggregate table " + table);
    }
  }

  private static String groupLegacyMembership(GroupAggregateTable table) {
    switch (table) {
      case GROUP_ROLE:
        return "group_id IN #{entityIds}";
      case OWNER:
        return "owner_id IN #{entityIds} AND owner_type = 'GROUP'";
      case GROUP:
        return "group_id IN #{entityIds}";
      default:
        throw new IllegalArgumentException("Unsupported group aggregate table " + table);
    }
  }

  private static String roleLegacyMembership(RoleAggregateTable table) {
    switch (table) {
      case USER_ROLE:
      case GROUP_ROLE:
      case SECURABLE_OBJECT:
        return "role_id IN #{entityIds}";
      case OWNER:
        return "metadata_object_id IN #{entityIds} AND metadata_object_type = 'ROLE'";
      case ROLE:
        return "role_id IN #{entityIds}";
      default:
        throw new IllegalArgumentException("Unsupported role aggregate table " + table);
    }
  }
}
