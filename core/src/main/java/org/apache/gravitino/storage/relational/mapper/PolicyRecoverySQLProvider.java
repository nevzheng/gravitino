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

/** SQL provider for exact, generation-scoped policy deletion and recovery. */
public class PolicyRecoverySQLProvider {

  private static final String POLICY_COLUMNS =
      "pm.policy_id, pm.policy_name, pm.policy_type, pm.metalake_id, pm.audit_info,"
          + " pm.current_version, pm.last_version, pm.deleted_at, pm.deletion_id";

  private static final String CURRENT_VERSION_COLUMNS =
      ", pv.id AS version_id, pv.metalake_id AS version_metalake_id,"
          + " pv.policy_id AS version_policy_id, pv.version AS version_number,"
          + " pv.policy_comment AS version_policy_comment, pv.enabled AS version_enabled,"
          + " pv.content AS version_content, pv.deleted_at AS version_deleted_at,"
          + " pv.deletion_id AS version_deletion_id";

  private static final String VERSION_COLUMNS =
      "pv.id, pv.metalake_id, pv.policy_id, pv.version, pv.policy_comment, pv.enabled,"
          + " pv.content, pv.deleted_at, pv.deletion_id";

  /** Builds the locking read for a live policy and its current version. */
  public String lockLivePolicy() {
    return "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM (SELECT * FROM policy_meta pm"
        + " WHERE pm.metalake_id = #{metalakeId} AND pm.policy_name = #{policyName}"
        + " AND pm.deleted_at = 0 AND pm.deletion_id IS NULL FOR UPDATE) pm"
        + " LEFT JOIN policy_version_info pv"
        + " ON pv.policy_id = pm.policy_id AND pv.version = pm.current_version"
        + " AND pv.deleted_at = 0 AND pv.deletion_id IS NULL";
  }

  /** Builds the locking read for policy tombstones matching immutable IDs. */
  public String selectDeletedPoliciesForUpdate() {
    return "<script>"
        + "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM (SELECT * FROM policy_meta pm"
        + " WHERE pm.deleted_at > 0 AND pm.policy_id IN"
        + " <foreach collection='policyIds' item='policyId' open='(' separator=',' close=')'>"
        + "#{policyId}"
        + "</foreach>"
        + " ORDER BY pm.policy_id FOR UPDATE) pm LEFT JOIN policy_version_info pv"
        + exactCurrentVersionJoin()
        + " ORDER BY pm.policy_id"
        + "</script>";
  }

  /** Builds the read for live policies below one metalake. */
  public String listLivePolicies() {
    return "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM policy_meta pm LEFT JOIN policy_version_info pv"
        + " ON pv.policy_id = pm.policy_id AND pv.version = pm.current_version"
        + " AND pv.deleted_at = 0 AND pv.deletion_id IS NULL"
        + " WHERE pm.metalake_id = #{metalakeId}"
        + " AND pm.deleted_at = 0 AND pm.deletion_id IS NULL";
  }

  /** Builds the read for globally live policies matching immutable IDs. */
  public String listLivePoliciesByIds() {
    return "<script>"
        + "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM policy_meta pm LEFT JOIN policy_version_info pv"
        + " ON pv.policy_id = pm.policy_id AND pv.version = pm.current_version"
        + " AND pv.deleted_at = 0 AND pv.deletion_id IS NULL"
        + " WHERE pm.deleted_at = 0 AND pm.deletion_id IS NULL AND pm.policy_id IN"
        + " <foreach collection='policyIds' item='policyId' open='(' separator=',' close=')'>"
        + "#{policyId}"
        + "</foreach>"
        + "</script>";
  }

  /** Builds the read for independently deleted policy roots below one metalake. */
  public String listDeletedRootPolicies() {
    return "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM policy_meta pm LEFT JOIN policy_version_info pv"
        + exactCurrentVersionJoin()
        + " LEFT JOIN entity_deletion ed ON ed.entity_type = 'POLICY'"
        + " AND ed.entity_id = pm.policy_id AND ed.deleted_at = pm.deleted_at"
        + " AND ed.deletion_id = pm.deletion_id"
        + " WHERE pm.metalake_id = #{metalakeId} AND pm.deleted_at > 0 AND ("
        + "pm.deletion_id IS NULL OR (pm.deletion_id IS NOT NULL"
        + " AND ed.metalake_id = #{metalakeId} AND ed.parent_id = #{metalakeId}))"
        + " ORDER BY pm.deleted_at DESC, pm.policy_id DESC";
  }

  /** Builds the locking read for unambiguous legacy policy roots eligible for permanent cleanup. */
  public String selectLegacyPolicyRootsForUpdate() {
    return "SELECT "
        + POLICY_COLUMNS
        + " FROM policy_meta pm WHERE pm.deleted_at > 0 AND pm.deleted_at < #{legacyTimeline}"
        + " AND pm.deletion_id IS NULL AND (SELECT COUNT(*) FROM policy_meta candidate"
        + " WHERE candidate.policy_id = pm.policy_id) = 1"
        + " ORDER BY pm.deleted_at, pm.policy_id LIMIT #{limit} FOR UPDATE";
  }

  /** Builds the delete for all versions owned by selected unambiguous legacy policy roots. */
  public String hardDeleteAllPolicyVersions() {
    return "<script>DELETE FROM policy_version_info WHERE policy_id IN"
        + policyIdList()
        + "</script>";
  }

  /** Builds the exact legacy-root delete after all owned versions have been removed. */
  public String hardDeleteLegacyPolicyRoots() {
    return "<script>DELETE FROM policy_meta WHERE deleted_at > 0"
        + " AND deleted_at &lt; #{legacyTimeline} AND deletion_id IS NULL AND policy_id IN"
        + policyIdList()
        + "</script>";
  }

  /** Builds the locking read for one exact policy base generation. */
  public String selectPolicyGenerationForUpdate() {
    return "SELECT "
        + POLICY_COLUMNS
        + CURRENT_VERSION_COLUMNS
        + " FROM (SELECT * FROM policy_meta pm WHERE pm.policy_id = #{policyId}"
        + " AND pm.deleted_at = #{deletedAt} AND pm.deletion_id = #{deletionId}"
        + " FOR UPDATE) pm LEFT JOIN policy_version_info pv"
        + exactCurrentVersionJoin();
  }

  /** Builds the locking read for every version in one exact policy generation. */
  public String listPolicyVersionGenerationForUpdate() {
    return "SELECT "
        + VERSION_COLUMNS
        + " FROM policy_version_info pv WHERE pv.policy_id = #{policyId}"
        + " AND pv.deleted_at = #{deletedAt} AND pv.deletion_id = #{deletionId}"
        + " ORDER BY pv.version, pv.id FOR UPDATE";
  }

  /** Builds the query for the newest prior tombstone timestamp for a policy identity or name. */
  public String selectNewestPolicyDeletedAt() {
    return "SELECT MAX(deleted_at) FROM ("
        + "SELECT MAX(deleted_at) AS deleted_at FROM policy_meta"
        + " WHERE policy_id = #{policyId}"
        + " OR (metalake_id = #{metalakeId} AND policy_name = #{policyName})"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM policy_version_info"
        + " WHERE policy_id = #{policyId}"
        + " UNION ALL SELECT MAX(deleted_at) AS deleted_at FROM entity_deletion"
        + " WHERE entity_type = 'POLICY' AND (entity_id = #{policyId}"
        + " OR (parent_id = #{metalakeId} AND entity_name = #{policyName}))"
        + ") policy_deletions";
  }

  /** Builds the update that stamps all live versions before the policy base row. */
  public String softDeletePolicyVersions() {
    return "UPDATE policy_version_info SET deleted_at = #{deletedAt},"
        + " deletion_id = #{deletionId} WHERE policy_id = #{policyId}"
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the update that stamps the live policy base after all versions. */
  public String softDeletePolicyMeta() {
    return "UPDATE policy_meta SET deleted_at = #{deletedAt}, deletion_id = #{deletionId}"
        + exactPolicyIdentity()
        + " AND deleted_at = 0 AND deletion_id IS NULL";
  }

  /** Builds the count for the exact policy base generation. */
  public String countPolicyGeneration() {
    return "SELECT COUNT(*) FROM policy_meta WHERE policy_id = #{policyId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the count for all versions in the exact policy generation. */
  public String countPolicyVersionGeneration() {
    return "SELECT COUNT(*) FROM policy_version_info WHERE policy_id = #{policyId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the integrity count for the required current version in the exact generation. */
  public String countCurrentVersionGeneration() {
    return "SELECT COUNT(*) FROM policy_version_info WHERE policy_id = #{policyId}"
        + " AND version = #{currentVersion} AND deleted_at = #{deletedAt}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation update that restores versions before the policy base row. */
  public String restorePolicyVersions() {
    return "UPDATE policy_version_info SET deleted_at = 0, deletion_id = NULL"
        + " WHERE policy_id = #{policyId} AND deleted_at = #{deletedAt}"
        + " AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation update that restores the policy base last. */
  public String restorePolicyMeta() {
    return "UPDATE policy_meta SET deleted_at = 0, deletion_id = NULL"
        + exactPolicyIdentity()
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges versions before the policy base row. */
  public String hardDeletePolicyVersions() {
    return "DELETE FROM policy_version_info WHERE policy_id = #{policyId}"
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  /** Builds the exact-generation delete that purges the policy base last. */
  public String hardDeletePolicyMeta() {
    return "DELETE FROM policy_meta"
        + exactPolicyIdentity()
        + " AND deleted_at = #{deletedAt} AND deletion_id = #{deletionId}";
  }

  private static String exactCurrentVersionJoin() {
    return " ON pv.policy_id = pm.policy_id AND pv.version = pm.current_version"
        + " AND pv.deleted_at = pm.deleted_at AND ((pv.deletion_id = pm.deletion_id)"
        + " OR (pv.deletion_id IS NULL AND pm.deletion_id IS NULL))";
  }

  private static String exactPolicyIdentity() {
    return " WHERE policy_id = #{policyId} AND metalake_id = #{metalakeId}"
        + " AND policy_name = #{policyName} AND current_version = #{currentVersion}";
  }

  private static String policyIdList() {
    return " <foreach collection='policyIds' item='policyId' open='(' separator=',' close=')'>"
        + "#{policyId}"
        + "</foreach>";
  }
}
