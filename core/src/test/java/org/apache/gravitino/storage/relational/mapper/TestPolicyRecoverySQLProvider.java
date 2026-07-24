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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestPolicyRecoverySQLProvider {

  private final PolicyRecoverySQLProvider provider = new PolicyRecoverySQLProvider();

  @Test
  void testAggregateMutationOrderKeepsPolicyBaseLast() {
    assertVersionThenBase(
        List.of(provider.softDeletePolicyVersions(), provider.softDeletePolicyMeta()));
    assertVersionThenBase(List.of(provider.restorePolicyVersions(), provider.restorePolicyMeta()));
    assertVersionThenBase(
        List.of(provider.hardDeletePolicyVersions(), provider.hardDeletePolicyMeta()));
  }

  @Test
  void testSoftDeleteExcludesPriorTombstonesAndUsesOneToken() {
    for (String sql :
        List.of(provider.softDeletePolicyVersions(), provider.softDeletePolicyMeta())) {
      assertTrue(sql.contains("SET deleted_at = #{deletedAt}"), sql);
      assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
      assertTrue(sql.contains("deleted_at = 0 AND deletion_id IS NULL"), sql);
    }

    assertTrue(provider.softDeletePolicyMeta().contains("current_version = #{currentVersion}"));
  }

  @Test
  void testRestoreAndPurgeUseExactGenerationPredicates() {
    for (String sql :
        List.of(
            provider.restorePolicyVersions(),
            provider.restorePolicyMeta(),
            provider.hardDeletePolicyVersions(),
            provider.hardDeletePolicyMeta())) {
      assertTrue(sql.contains("deleted_at = #{deletedAt}"), sql);
      assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
      assertTrue(sql.contains("policy_id = #{policyId}"), sql);
    }

    assertTrue(provider.restorePolicyMeta().contains("policy_name = #{policyName}"));
    assertTrue(provider.hardDeletePolicyMeta().contains("current_version = #{currentVersion}"));

    for (String sql :
        List.of(provider.countPolicyGeneration(), provider.countPolicyVersionGeneration())) {
      assertTrue(sql.contains("policy_id = #{policyId}"), sql);
      assertTrue(sql.contains("deleted_at = #{deletedAt}"), sql);
      assertTrue(sql.contains("deletion_id = #{deletionId}"), sql);
    }
  }

  @Test
  void testCurrentVersionIntegrityRemainsProvableWhenDetailIsMissing() {
    String liveLock = provider.lockLivePolicy();
    String generationLock = provider.selectPolicyGenerationForUpdate();
    String currentVersionCount = provider.countCurrentVersionGeneration();

    assertTrue(liveLock.contains("LEFT JOIN policy_version_info"), liveLock);
    assertTrue(liveLock.contains("pv.version = pm.current_version"), liveLock);
    assertTrue(liveLock.contains("FOR UPDATE"), liveLock);

    assertTrue(generationLock.contains("LEFT JOIN policy_version_info"), generationLock);
    assertTrue(generationLock.contains("pv.deleted_at = pm.deleted_at"), generationLock);
    assertTrue(generationLock.contains("pv.deletion_id = pm.deletion_id"), generationLock);
    assertTrue(generationLock.contains("FOR UPDATE"), generationLock);

    assertTrue(currentVersionCount.contains("version = #{currentVersion}"), currentVersionCount);
    assertTrue(currentVersionCount.contains("deleted_at = #{deletedAt}"), currentVersionCount);
    assertTrue(currentVersionCount.contains("deletion_id = #{deletionId}"), currentVersionCount);
  }

  @Test
  void testRootListingExcludesMetalakeCascadeComponentTombstones() {
    String sql = provider.listDeletedRootPolicies();

    assertTrue(sql.contains("LEFT JOIN entity_deletion"), sql);
    assertTrue(sql.contains("ed.entity_type = 'POLICY'"), sql);
    assertTrue(sql.contains("ed.entity_id = pm.policy_id"), sql);
    assertTrue(sql.contains("ed.parent_id = #{metalakeId}"), sql);
    assertTrue(sql.contains("pm.deletion_id IS NULL OR"), sql);
  }

  @Test
  void testLiveReadsAndVersionLocksUseStrictGenerationState() {
    for (String sql :
        List.of(
            provider.lockLivePolicy(),
            provider.listLivePolicies(),
            provider.listLivePoliciesByIds())) {
      assertTrue(sql.contains("pm.deleted_at = 0"), sql);
      assertTrue(sql.contains("pm.deletion_id IS NULL"), sql);
    }

    String versions = provider.listPolicyVersionGenerationForUpdate();
    assertTrue(versions.contains("pv.deleted_at = #{deletedAt}"), versions);
    assertTrue(versions.contains("pv.deletion_id = #{deletionId}"), versions);
    assertTrue(versions.contains("ORDER BY pv.version, pv.id FOR UPDATE"), versions);

    String recorded = provider.selectDeletedPoliciesForUpdate();
    assertTrue(recorded.contains("pm.deleted_at > 0"), recorded);
    assertFalse(recorded.contains("pm.deletion_id IS NOT NULL"), recorded);
    assertTrue(recorded.contains("ORDER BY pm.policy_id FOR UPDATE"), recorded);
  }

  @Test
  void testNewestTimestampCoversBaseVersionsAndReceipts() {
    String sql = provider.selectNewestPolicyDeletedAt();

    assertTrue(sql.contains("FROM policy_meta"), sql);
    assertTrue(sql.contains("FROM policy_version_info"), sql);
    assertTrue(sql.contains("FROM entity_deletion"), sql);
    assertTrue(sql.contains("policy_name = #{policyName}"), sql);
    assertTrue(sql.contains("entity_name = #{policyName}"), sql);
  }

  @Test
  void testLegacyCleanupLocksUnambiguousRootsAndDeletesVersionsFirst() {
    String roots = provider.selectLegacyPolicyRootsForUpdate();
    assertTrue(roots.contains("pm.deletion_id IS NULL"), roots);
    assertTrue(roots.contains("candidate.policy_id = pm.policy_id) = 1"), roots);
    assertTrue(roots.contains("LIMIT #{limit} FOR UPDATE"), roots);

    String versions = provider.hardDeleteAllPolicyVersions();
    String policies = provider.hardDeleteLegacyPolicyRoots();
    assertTrue(versions.contains("DELETE FROM policy_version_info"), versions);
    assertTrue(policies.contains("DELETE FROM policy_meta"), policies);
    assertTrue(policies.contains("deletion_id IS NULL"), policies);
    assertTrue(versions.contains("collection='policyIds'"), versions);
    assertTrue(policies.contains("collection='policyIds'"), policies);
  }

  private static void assertVersionThenBase(List<String> statements) {
    assertEquals(2, statements.size());
    assertTrue(statements.get(0).contains("policy_version_info"), statements.get(0));
    assertTrue(statements.get(1).contains("policy_meta"), statements.get(1));
  }
}
