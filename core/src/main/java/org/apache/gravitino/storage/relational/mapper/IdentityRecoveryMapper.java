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
import javax.annotation.Nullable;
import org.apache.gravitino.storage.relational.po.GroupPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped user, group, and role recovery. */
public interface IdentityRecoveryMapper {

  /** Result map for user root rows used by recovery. */
  @Results(
      id = "userRecoveryResultMap",
      value = {
        @Result(property = "userId", column = "user_id", id = true),
        @Result(property = "userName", column = "user_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "externalId", column = "external_id"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  UserPO userRecoveryResultMap();

  /** Result map for group root rows used by recovery. */
  @Results(
      id = "groupRecoveryResultMap",
      value = {
        @Result(property = "groupId", column = "group_id", id = true),
        @Result(property = "groupName", column = "group_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "externalId", column = "external_id"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  GroupPO groupRecoveryResultMap();

  /** Result map for role root rows used by recovery. */
  @Results(
      id = "roleRecoveryResultMap",
      value = {
        @Result(property = "roleId", column = "role_id", id = true),
        @Result(property = "roleName", column = "role_name"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "properties", column = "properties"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  RolePO roleRecoveryResultMap();

  /** Locks the live metalake that serializes identity membership changes. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 AND deletion_id IS NULL FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks one live user by immutable parent and name. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "lockLiveUser")
  UserPO lockLiveUser(@Param("metalakeId") long metalakeId, @Param("userName") String userName);

  /** Locks one live group by immutable parent and name. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "lockLiveGroup")
  GroupPO lockLiveGroup(@Param("metalakeId") long metalakeId, @Param("groupName") String groupName);

  /** Locks one live role by immutable parent and name. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "lockLiveRole")
  RolePO lockLiveRole(@Param("metalakeId") long metalakeId, @Param("roleName") String roleName);

  /** Locks deleted user roots whose immutable IDs must not be overwritten. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectDeletedUsersForUpdate")
  List<UserPO> selectDeletedUsersForUpdate(@Param("userIds") List<Long> userIds);

  /** Locks deleted group roots whose immutable IDs must not be overwritten. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectDeletedGroupsForUpdate")
  List<GroupPO> selectDeletedGroupsForUpdate(@Param("groupIds") List<Long> groupIds);

  /** Locks deleted role roots whose immutable IDs must not be overwritten. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectDeletedRolesForUpdate")
  List<RolePO> selectDeletedRolesForUpdate(@Param("roleIds") List<Long> roleIds);

  /** Lists live users below one metalake. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveUsers")
  List<UserPO> listLiveUsers(@Param("metalakeId") long metalakeId);

  /** Lists live groups below one metalake. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveGroups")
  List<GroupPO> listLiveGroups(@Param("metalakeId") long metalakeId);

  /** Lists live roles below one metalake. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveRoles")
  List<RolePO> listLiveRoles(@Param("metalakeId") long metalakeId);

  /** Lists globally live users matching immutable IDs. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveUsersByIds")
  List<UserPO> listLiveUsersByIds(@Param("userIds") List<Long> userIds);

  /** Lists globally live groups matching immutable IDs. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveGroupsByIds")
  List<GroupPO> listLiveGroupsByIds(@Param("groupIds") List<Long> groupIds);

  /** Lists globally live roles matching immutable IDs. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listLiveRolesByIds")
  List<RolePO> listLiveRolesByIds(@Param("roleIds") List<Long> roleIds);

  /** Lists independently deleted user roots below one live metalake. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listDeletedRootUsers")
  List<UserPO> listDeletedRootUsers(@Param("metalakeId") long metalakeId);

  /** Lists independently deleted group roots below one live metalake. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listDeletedRootGroups")
  List<GroupPO> listDeletedRootGroups(@Param("metalakeId") long metalakeId);

  /** Lists independently deleted role roots below one live metalake. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "listDeletedRootRoles")
  List<RolePO> listDeletedRootRoles(@Param("metalakeId") long metalakeId);

  /** Locks live user rows occupying the restore target's name or external ID. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "listLiveUserOccupantsForUpdate")
  List<UserPO> listLiveUserOccupantsForUpdate(
      @Param("metalakeId") long metalakeId,
      @Param("userName") String userName,
      @Param("externalId") @Nullable String externalId);

  /** Locks live group rows occupying the restore target's name or external ID. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "listLiveGroupOccupantsForUpdate")
  List<GroupPO> listLiveGroupOccupantsForUpdate(
      @Param("metalakeId") long metalakeId,
      @Param("groupName") String groupName,
      @Param("externalId") @Nullable String externalId);

  /** Locks one exact user deletion generation. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectUserGenerationForUpdate")
  UserPO selectUserGenerationForUpdate(
      @Param("userId") long userId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks one exact group deletion generation. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectGroupGenerationForUpdate")
  GroupPO selectGroupGenerationForUpdate(
      @Param("groupId") long groupId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks one exact role deletion generation. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectRoleGenerationForUpdate")
  RolePO selectRoleGenerationForUpdate(
      @Param("roleId") long roleId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Returns the greatest prior tombstone timestamp touching one user aggregate. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectNewestUserDeletedAt")
  Long selectNewestUserDeletedAt(
      @Param("userId") long userId,
      @Param("metalakeId") long metalakeId,
      @Param("userName") String userName,
      @Param("externalId") @Nullable String externalId);

  /** Returns the greatest prior tombstone timestamp touching one group aggregate. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectNewestGroupDeletedAt")
  Long selectNewestGroupDeletedAt(
      @Param("groupId") long groupId,
      @Param("metalakeId") long metalakeId,
      @Param("groupName") String groupName,
      @Param("externalId") @Nullable String externalId);

  /** Returns the greatest prior tombstone timestamp touching one role aggregate. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "selectNewestRoleDeletedAt")
  Long selectNewestRoleDeletedAt(
      @Param("roleId") long roleId,
      @Param("metalakeId") long metalakeId,
      @Param("roleName") String roleName);

  /** Stamps one deletion generation on live rows in one user aggregate table. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "softDeleteUserAggregateRows")
  int softDeleteUserAggregateRows(
      @Param("aggregateTable") UserAggregateTable aggregateTable,
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Stamps one deletion generation on live rows in one group aggregate table. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "softDeleteGroupAggregateRows")
  int softDeleteGroupAggregateRows(
      @Param("aggregateTable") GroupAggregateTable aggregateTable,
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Stamps one deletion generation on live rows in one role aggregate table. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "softDeleteRoleAggregateRows")
  int softDeleteRoleAggregateRows(
      @Param("aggregateTable") RoleAggregateTable aggregateTable,
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one user aggregate table captured by an exact generation. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "countUserGenerationRows")
  int countUserGenerationRows(
      @Param("aggregateTable") UserAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one group aggregate table captured by an exact generation. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "countGroupGenerationRows")
  int countGroupGenerationRows(
      @Param("aggregateTable") GroupAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts rows in one role aggregate table captured by an exact generation. */
  @SelectProvider(type = IdentityRecoverySQLProvider.class, method = "countRoleGenerationRows")
  int countRoleGenerationRows(
      @Param("aggregateTable") RoleAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts broken references in one exact user deletion generation. */
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "countBrokenUserGenerationReferences")
  int countBrokenUserGenerationReferences(
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts broken references in one exact group deletion generation. */
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "countBrokenGroupGenerationReferences")
  int countBrokenGroupGenerationReferences(
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts broken references in one exact role deletion generation. */
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "countBrokenRoleGenerationReferences")
  int countBrokenRoleGenerationReferences(
      @Param("entityId") long entityId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores rows from one exact user deletion generation. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "restoreUserGenerationRows")
  int restoreUserGenerationRows(
      @Param("aggregateTable") UserAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores rows from one exact group deletion generation. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "restoreGroupGenerationRows")
  int restoreGroupGenerationRows(
      @Param("aggregateTable") GroupAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Restores rows from one exact role deletion generation. */
  @UpdateProvider(type = IdentityRecoverySQLProvider.class, method = "restoreRoleGenerationRows")
  int restoreRoleGenerationRows(
      @Param("aggregateTable") RoleAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId,
      @Param("restoredAt") long restoredAt);

  /** Permanently deletes rows from one exact user deletion generation. */
  @DeleteProvider(type = IdentityRecoverySQLProvider.class, method = "hardDeleteUserGenerationRows")
  int hardDeleteUserGenerationRows(
      @Param("aggregateTable") UserAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes rows from one exact group deletion generation. */
  @DeleteProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "hardDeleteGroupGenerationRows")
  int hardDeleteGroupGenerationRows(
      @Param("aggregateTable") GroupAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes rows from one exact role deletion generation. */
  @DeleteProvider(type = IdentityRecoverySQLProvider.class, method = "hardDeleteRoleGenerationRows")
  int hardDeleteRoleGenerationRows(
      @Param("aggregateTable") RoleAggregateTable aggregateTable,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks one bounded batch of unrecorded legacy user roots. */
  @ResultMap("userRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectLegacyUserRootsForUpdate")
  List<UserPO> selectLegacyUserRootsForUpdate(
      @Param("legacyTimeline") long legacyTimeline, @Param("limit") int limit);

  /** Locks one bounded batch of unrecorded legacy group roots. */
  @ResultMap("groupRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectLegacyGroupRootsForUpdate")
  List<GroupPO> selectLegacyGroupRootsForUpdate(
      @Param("legacyTimeline") long legacyTimeline, @Param("limit") int limit);

  /** Locks one bounded batch of unrecorded legacy role roots. */
  @ResultMap("roleRecoveryResultMap")
  @SelectProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "selectLegacyRoleRootsForUpdate")
  List<RolePO> selectLegacyRoleRootsForUpdate(
      @Param("legacyTimeline") long legacyTimeline, @Param("limit") int limit);

  /** Permanently deletes expired unrecorded rows in one legacy user aggregate table. */
  @DeleteProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "hardDeleteLegacyUserAggregateRows")
  int hardDeleteLegacyUserAggregateRows(
      @Param("aggregateTable") UserAggregateTable aggregateTable,
      @Param("userIds") List<Long> userIds,
      @Param("legacyTimeline") long legacyTimeline);

  /** Permanently deletes expired unrecorded rows in one legacy group aggregate table. */
  @DeleteProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "hardDeleteLegacyGroupAggregateRows")
  int hardDeleteLegacyGroupAggregateRows(
      @Param("aggregateTable") GroupAggregateTable aggregateTable,
      @Param("groupIds") List<Long> groupIds,
      @Param("legacyTimeline") long legacyTimeline);

  /** Permanently deletes expired unrecorded rows in one legacy role aggregate table. */
  @DeleteProvider(
      type = IdentityRecoverySQLProvider.class,
      method = "hardDeleteLegacyRoleAggregateRows")
  int hardDeleteLegacyRoleAggregateRows(
      @Param("aggregateTable") RoleAggregateTable aggregateTable,
      @Param("roleIds") List<Long> roleIds,
      @Param("legacyTimeline") long legacyTimeline);
}
