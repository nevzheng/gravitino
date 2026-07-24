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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestIdentityRecoverySQLProvider {

  private final IdentityRecoverySQLProvider provider = new IdentityRecoverySQLProvider();

  @Test
  void testAggregateEnumsKeepSharedRelationsFirstAndRootsLast() {
    assertEquals(UserAggregateTable.USER_ROLE, UserAggregateTable.values()[0]);
    assertEquals(UserAggregateTable.USER, last(UserAggregateTable.values()));
    assertEquals(GroupAggregateTable.GROUP_ROLE, GroupAggregateTable.values()[0]);
    assertEquals(GroupAggregateTable.GROUP, last(GroupAggregateTable.values()));
    assertEquals(RoleAggregateTable.USER_ROLE, RoleAggregateTable.values()[0]);
    assertEquals(RoleAggregateTable.ROLE, last(RoleAggregateTable.values()));
  }

  @Test
  void testSoftDeleteClaimsOnlyLiveRowsWithDirectionalMembership() {
    assertAggregateDelete(
        provider.softDeleteUserAggregateRows(params(UserAggregateTable.USER_ROLE)),
        "user_role_rel",
        "user_id = #{entityId}");
    assertAggregateDelete(
        provider.softDeleteUserAggregateRows(params(UserAggregateTable.OWNER)),
        "owner_meta",
        "owner_id = #{entityId} AND owner_type = 'USER'");
    assertAggregateDelete(
        provider.softDeleteUserAggregateRows(params(UserAggregateTable.USER)),
        "user_meta",
        "user_id = #{entityId}");

    assertAggregateDelete(
        provider.softDeleteGroupAggregateRows(params(GroupAggregateTable.GROUP_ROLE)),
        "group_role_rel",
        "group_id = #{entityId}");
    assertAggregateDelete(
        provider.softDeleteGroupAggregateRows(params(GroupAggregateTable.OWNER)),
        "owner_meta",
        "owner_id = #{entityId} AND owner_type = 'GROUP'");
    assertAggregateDelete(
        provider.softDeleteGroupAggregateRows(params(GroupAggregateTable.GROUP)),
        "group_meta",
        "group_id = #{entityId}");

    assertAggregateDelete(
        provider.softDeleteRoleAggregateRows(params(RoleAggregateTable.USER_ROLE)),
        "user_role_rel",
        "role_id = #{entityId}");
    assertAggregateDelete(
        provider.softDeleteRoleAggregateRows(params(RoleAggregateTable.GROUP_ROLE)),
        "group_role_rel",
        "role_id = #{entityId}");
    assertAggregateDelete(
        provider.softDeleteRoleAggregateRows(params(RoleAggregateTable.SECURABLE_OBJECT)),
        "role_meta_securable_object",
        "role_id = #{entityId}");
    assertAggregateDelete(
        provider.softDeleteRoleAggregateRows(params(RoleAggregateTable.OWNER)),
        "owner_meta",
        "metadata_object_id = #{entityId} AND metadata_object_type = 'ROLE'");
    assertAggregateDelete(
        provider.softDeleteRoleAggregateRows(params(RoleAggregateTable.ROLE)),
        "role_meta",
        "role_id = #{entityId}");
  }

  @Test
  void testExactGenerationRestoreCountAndPurgeNeverUseCounterpartState() {
    for (UserAggregateTable table : UserAggregateTable.values()) {
      assertExactGeneration(provider.countUserGenerationRows(params(table)));
      assertExactGeneration(provider.restoreUserGenerationRows(params(table)));
      assertExactGeneration(provider.hardDeleteUserGenerationRows(params(table)));
    }
    for (GroupAggregateTable table : GroupAggregateTable.values()) {
      assertExactGeneration(provider.countGroupGenerationRows(params(table)));
      assertExactGeneration(provider.restoreGroupGenerationRows(params(table)));
      assertExactGeneration(provider.hardDeleteGroupGenerationRows(params(table)));
    }
    for (RoleAggregateTable table : RoleAggregateTable.values()) {
      assertExactGeneration(provider.countRoleGenerationRows(params(table)));
      assertExactGeneration(provider.restoreRoleGenerationRows(params(table)));
      assertExactGeneration(provider.hardDeleteRoleGenerationRows(params(table)));
    }
  }

  @Test
  void testBrokenReferenceChecksProveOnlyRootAndDirectionalTokenMembership() {
    String user = provider.countBrokenUserGenerationReferences();
    assertTrue(user.contains("FROM user_meta WHERE user_id = #{entityId}"), user);
    assertTrue(user.contains("relation.user_id = #{entityId}"), user);
    assertTrue(user.contains("relation.owner_type = 'USER'"), user);
    assertFalse(user.contains("role_meta"), user);

    String group = provider.countBrokenGroupGenerationReferences();
    assertTrue(group.contains("FROM group_meta WHERE group_id = #{entityId}"), group);
    assertTrue(group.contains("relation.group_id = #{entityId}"), group);
    assertTrue(group.contains("relation.owner_type = 'GROUP'"), group);
    assertFalse(group.contains("role_meta"), group);

    String role = provider.countBrokenRoleGenerationReferences();
    assertTrue(role.contains("FROM role_meta WHERE role_id = #{entityId}"), role);
    assertTrue(role.contains("relation.role_id = #{entityId}"), role);
    assertTrue(role.contains("relation.metadata_object_type = 'ROLE'"), role);
    assertFalse(role.contains("user_meta"), role);
    assertFalse(role.contains("group_meta"), role);
    assertFalse(role.contains("NOT EXISTS"), role);
  }

  @Test
  void testRootListingsExposeOnlyStandaloneReceiptsOrLegacyRows() {
    assertDeletedRootListing(provider.listDeletedRootUsers(), "USER", "um.user_id");
    assertDeletedRootListing(provider.listDeletedRootGroups(), "GROUP", "gm.group_id");
    assertDeletedRootListing(provider.listDeletedRootRoles(), "ROLE", "rm.role_id");
  }

  @Test
  void testInsertFencesOnlyRecordedTombstoneIds() {
    for (String sql :
        List.of(
            provider.selectDeletedUsersForUpdate(),
            provider.selectDeletedGroupsForUpdate(),
            provider.selectDeletedRolesForUpdate())) {
      assertTrue(sql.contains("deleted_at > 0"), sql);
      assertTrue(sql.contains("deletion_id IS NOT NULL"), sql);
      assertTrue(sql.contains("FOR UPDATE"), sql);
    }
  }

  @Test
  void testLiveAndExactGenerationReadsHaveStrictStateAndLocks() {
    for (String sql :
        List.of(provider.lockLiveUser(), provider.lockLiveGroup(), provider.lockLiveRole())) {
      assertTrue(sql.contains("deleted_at = 0 AND deletion_id IS NULL"), sql);
      assertTrue(sql.endsWith("FOR UPDATE"), sql);
    }
    for (String sql :
        List.of(
            provider.listLiveUsers(),
            provider.listLiveGroups(),
            provider.listLiveRoles(),
            provider.listLiveUsersByIds(),
            provider.listLiveGroupsByIds(),
            provider.listLiveRolesByIds())) {
      assertTrue(sql.contains("deleted_at = 0 AND deletion_id IS NULL"), sql);
    }
    for (String sql :
        List.of(
            provider.selectUserGenerationForUpdate(),
            provider.selectGroupGenerationForUpdate(),
            provider.selectRoleGenerationForUpdate())) {
      assertTrue(sql.contains("deleted_at = #{deletedAt}"), sql);
      assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
      assertTrue(sql.endsWith("FOR UPDATE"), sql);
    }
  }

  @Test
  void testExternalIdOccupantsAreLockedOnlyWhenExternalIdExists() {
    String users = provider.listLiveUserOccupantsForUpdate();
    assertTrue(users.contains("user_name = #{userName}"), users);
    assertTrue(users.contains("<if test='externalId != null'>"), users);
    assertTrue(users.contains("external_id = #{externalId}"), users);
    assertTrue(users.contains("ORDER BY user_id FOR UPDATE"), users);

    String groups = provider.listLiveGroupOccupantsForUpdate();
    assertTrue(groups.contains("group_name = #{groupName}"), groups);
    assertTrue(groups.contains("<if test='externalId != null'>"), groups);
    assertTrue(groups.contains("external_id = #{externalId}"), groups);
    assertTrue(groups.contains("ORDER BY group_id FOR UPDATE"), groups);
  }

  @Test
  void testNewestTimestampCoversEveryAggregateTableAndReceipt() {
    String user = provider.selectNewestUserDeletedAt();
    assertContainsAll(user, "user_meta", "user_role_rel", "owner_meta", "entity_deletion");
    assertTrue(user.contains("external_id = #{externalId}"), user);
    assertTrue(user.contains("entity_type = 'USER'"), user);

    String group = provider.selectNewestGroupDeletedAt();
    assertContainsAll(group, "group_meta", "group_role_rel", "owner_meta", "entity_deletion");
    assertTrue(group.contains("external_id = #{externalId}"), group);
    assertTrue(group.contains("entity_type = 'GROUP'"), group);

    String role = provider.selectNewestRoleDeletedAt();
    assertContainsAll(
        role,
        "role_meta",
        "user_role_rel",
        "group_role_rel",
        "role_meta_securable_object",
        "owner_meta",
        "entity_deletion");
    assertTrue(role.contains("entity_type = 'ROLE'"), role);
  }

  @Test
  void testLegacyCleanupExcludesRecordedRowsAndKeepsRootLast() {
    for (String roots :
        List.of(
            provider.selectLegacyUserRootsForUpdate(),
            provider.selectLegacyGroupRootsForUpdate(),
            provider.selectLegacyRoleRootsForUpdate())) {
      assertTrue(roots.contains("deletion_id IS NULL"), roots);
      assertTrue(roots.contains("deleted_at < #{legacyTimeline}"), roots);
      assertTrue(roots.contains("LIMIT #{limit} FOR UPDATE"), roots);
    }

    List<String> userDeletes =
        Arrays.stream(UserAggregateTable.values())
            .map(table -> provider.hardDeleteLegacyUserAggregateRows(params(table)))
            .toList();
    List<String> groupDeletes =
        Arrays.stream(GroupAggregateTable.values())
            .map(table -> provider.hardDeleteLegacyGroupAggregateRows(params(table)))
            .toList();
    List<String> roleDeletes =
        Arrays.stream(RoleAggregateTable.values())
            .map(table -> provider.hardDeleteLegacyRoleAggregateRows(params(table)))
            .toList();

    assertTrue(userDeletes.get(0).contains("user_role_rel"), userDeletes.get(0));
    assertTrue(last(userDeletes.toArray(String[]::new)).contains("user_meta"));
    assertTrue(groupDeletes.get(0).contains("group_role_rel"), groupDeletes.get(0));
    assertTrue(last(groupDeletes.toArray(String[]::new)).contains("group_meta"));
    assertTrue(roleDeletes.get(0).contains("user_role_rel"), roleDeletes.get(0));
    assertTrue(last(roleDeletes.toArray(String[]::new)).contains("role_meta"));

    for (List<String> deletes : List.of(userDeletes, groupDeletes, roleDeletes)) {
      for (String sql : deletes) {
        assertTrue(sql.contains("deletion_id IS NULL"), sql);
        assertTrue(sql.contains("deleted_at &lt; #{legacyTimeline}"), sql);
      }
    }
  }

  @Test
  void testProviderRejectsAnAggregateEnumFromAnotherIdentityType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.softDeleteUserAggregateRows(params(GroupAggregateTable.GROUP)));
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.softDeleteRoleAggregateRows(params(UserAggregateTable.USER)));
  }

  private static void assertAggregateDelete(String sql, String table, String membership) {
    assertTrue(sql.startsWith("UPDATE " + table), sql);
    assertTrue(sql.contains("SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"), sql);
    assertTrue(sql.contains("deleted_at = 0 AND deletion_id IS NULL"), sql);
    assertTrue(sql.contains(membership), sql);
  }

  private static void assertExactGeneration(String sql) {
    assertTrue(sql.contains("deleted_at = #{deletedAt}"), sql);
    assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
    assertFalse(sql.contains("user_id = #{entityId}"), sql);
    assertFalse(sql.contains("group_id = #{entityId}"), sql);
    assertFalse(sql.contains("role_id = #{entityId}"), sql);
  }

  private static void assertDeletedRootListing(String sql, String entityType, String rootId) {
    assertTrue(sql.contains("ed.entity_type = '" + entityType + "'"), sql);
    assertTrue(sql.contains("ed.entity_id = " + rootId), sql);
    assertTrue(sql.contains("ed.parent_id = #{metalakeId}"), sql);
    assertTrue(sql.contains("deletion_id IS NULL OR"), sql);
  }

  private static void assertContainsAll(String sql, String... fragments) {
    for (String fragment : fragments) {
      assertTrue(sql.contains(fragment), sql);
    }
  }

  private static Map<String, Object> params(Object aggregateTable) {
    return Map.of("aggregateTable", aggregateTable);
  }

  private static <T> T last(T[] values) {
    return values[values.length - 1];
  }
}
