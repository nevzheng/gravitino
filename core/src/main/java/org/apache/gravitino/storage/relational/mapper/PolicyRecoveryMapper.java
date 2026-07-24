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
import org.apache.gravitino.storage.relational.po.PolicyPO;
import org.apache.gravitino.storage.relational.po.PolicyVersionPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for exact, generation-scoped policy deletion and recovery. */
public interface PolicyRecoveryMapper {

  /** Result map for policy base rows and their optional current-version detail. */
  @Results(
      id = "policyRecoveryResultMap",
      value = {
        @Result(property = "policyId", column = "policy_id", id = true),
        @Result(property = "policyName", column = "policy_name"),
        @Result(property = "policyType", column = "policy_type"),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "auditInfo", column = "audit_info"),
        @Result(property = "currentVersion", column = "current_version"),
        @Result(property = "lastVersion", column = "last_version"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id"),
        @Result(property = "policyVersionPO.id", column = "version_id", id = true),
        @Result(property = "policyVersionPO.metalakeId", column = "version_metalake_id"),
        @Result(property = "policyVersionPO.policyId", column = "version_policy_id"),
        @Result(property = "policyVersionPO.version", column = "version_number"),
        @Result(property = "policyVersionPO.policyComment", column = "version_policy_comment"),
        @Result(property = "policyVersionPO.enabled", column = "version_enabled"),
        @Result(property = "policyVersionPO.content", column = "version_content"),
        @Result(property = "policyVersionPO.deletedAt", column = "version_deleted_at"),
        @Result(property = "policyVersionPO.deletionId", column = "version_deletion_id")
      })
  @Select("SELECT 1")
  PolicyPO policyRecoveryResultMap();

  /** Result map for policy-version rows used by generation integrity checks. */
  @Results(
      id = "policyRecoveryVersionResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "metalakeId", column = "metalake_id"),
        @Result(property = "policyId", column = "policy_id"),
        @Result(property = "version", column = "version"),
        @Result(property = "policyComment", column = "policy_comment"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "content", column = "content"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "deletionId", column = "deletion_id")
      })
  @Select("SELECT 1")
  PolicyVersionPO policyRecoveryVersionResultMap();

  /** Locks the live metalake that serializes policy membership changes. */
  @Select({
    "SELECT metalake_id FROM metalake_meta",
    "WHERE metalake_id = #{metalakeId} AND deleted_at = 0 FOR UPDATE"
  })
  Long lockLiveMetalake(@Param("metalakeId") long metalakeId);

  /** Locks a live policy and left-joins its required current-version detail. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "lockLivePolicy")
  PolicyPO lockLivePolicy(
      @Param("metalakeId") long metalakeId, @Param("policyName") String policyName);

  /** Locks policy tombstones whose immutable IDs must not be overwritten. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "selectDeletedPoliciesForUpdate")
  List<PolicyPO> selectDeletedPoliciesForUpdate(@Param("policyIds") List<Long> policyIds);

  /** Lists live policy identities below one metalake. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "listLivePolicies")
  List<PolicyPO> listLivePolicies(@Param("metalakeId") long metalakeId);

  /** Lists globally live policy rows matching candidate immutable IDs. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "listLivePoliciesByIds")
  List<PolicyPO> listLivePoliciesByIds(@Param("policyIds") List<Long> policyIds);

  /** Lists independently recorded policy-root tombstones below one live metalake. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "listDeletedRootPolicies")
  List<PolicyPO> listDeletedRootPolicies(@Param("metalakeId") long metalakeId);

  /** Locks a bounded set of legacy policy roots that have no competing ID generation. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(
      type = PolicyRecoverySQLProvider.class,
      method = "selectLegacyPolicyRootsForUpdate")
  List<PolicyPO> selectLegacyPolicyRootsForUpdate(
      @Param("legacyTimeline") long legacyTimeline, @Param("limit") int limit);

  /** Permanently deletes every version owned by selected unambiguous legacy policy roots. */
  @DeleteProvider(type = PolicyRecoverySQLProvider.class, method = "hardDeleteAllPolicyVersions")
  int hardDeleteAllPolicyVersions(@Param("policyIds") List<Long> policyIds);

  /** Permanently deletes selected legacy policy roots after their versions. */
  @DeleteProvider(type = PolicyRecoverySQLProvider.class, method = "hardDeleteLegacyPolicyRoots")
  int hardDeleteLegacyPolicyRoots(
      @Param("policyIds") List<Long> policyIds, @Param("legacyTimeline") long legacyTimeline);

  /** Locks the exact policy base generation while retaining a missing current-version signal. */
  @ResultMap("policyRecoveryResultMap")
  @SelectProvider(
      type = PolicyRecoverySQLProvider.class,
      method = "selectPolicyGenerationForUpdate")
  PolicyPO selectPolicyGenerationForUpdate(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Locks every version row captured by one exact policy deletion generation. */
  @ResultMap("policyRecoveryVersionResultMap")
  @SelectProvider(
      type = PolicyRecoverySQLProvider.class,
      method = "listPolicyVersionGenerationForUpdate")
  List<PolicyVersionPO> listPolicyVersionGenerationForUpdate(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Returns the newest tombstone timestamp for the immutable policy or current parent/name. */
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "selectNewestPolicyDeletedAt")
  Long selectNewestPolicyDeletedAt(
      @Param("policyId") long policyId,
      @Param("metalakeId") long metalakeId,
      @Param("policyName") String policyName);

  /** Stamps every live policy version before stamping the policy base row. */
  @UpdateProvider(type = PolicyRecoverySQLProvider.class, method = "softDeletePolicyVersions")
  int softDeletePolicyVersions(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Stamps the live policy base after every version has been captured. */
  @UpdateProvider(type = PolicyRecoverySQLProvider.class, method = "softDeletePolicyMeta")
  int softDeletePolicyMeta(
      @Param("policyId") long policyId,
      @Param("metalakeId") long metalakeId,
      @Param("policyName") String policyName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the policy base row in one exact deletion generation. */
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "countPolicyGeneration")
  int countPolicyGeneration(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts all policy-version rows in one exact deletion generation. */
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "countPolicyVersionGeneration")
  int countPolicyVersionGeneration(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Counts the required current-version row in one exact deletion generation. */
  @SelectProvider(type = PolicyRecoverySQLProvider.class, method = "countCurrentVersionGeneration")
  int countCurrentVersionGeneration(
      @Param("policyId") long policyId,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores every version from one exact generation before restoring the policy base. */
  @UpdateProvider(type = PolicyRecoverySQLProvider.class, method = "restorePolicyVersions")
  int restorePolicyVersions(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Restores the exact policy base after every version has been restored. */
  @UpdateProvider(type = PolicyRecoverySQLProvider.class, method = "restorePolicyMeta")
  int restorePolicyMeta(
      @Param("policyId") long policyId,
      @Param("metalakeId") long metalakeId,
      @Param("policyName") String policyName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes versions from one exact generation before deleting the policy base. */
  @DeleteProvider(type = PolicyRecoverySQLProvider.class, method = "hardDeletePolicyVersions")
  int hardDeletePolicyVersions(
      @Param("policyId") long policyId,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);

  /** Permanently deletes the exact policy base after every version has been purged. */
  @DeleteProvider(type = PolicyRecoverySQLProvider.class, method = "hardDeletePolicyMeta")
  int hardDeletePolicyMeta(
      @Param("policyId") long policyId,
      @Param("metalakeId") long metalakeId,
      @Param("policyName") String policyName,
      @Param("currentVersion") long currentVersion,
      @Param("deletedAt") long deletedAt,
      @Param("deletionId") String deletionId);
}
