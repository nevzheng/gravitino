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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestSchemaRecoverySQLProvider {

  @Test
  void testExternalReferenceValidationRequiresLiveSources() {
    String sql = new SchemaRecoverySQLProvider().countBrokenExternalReferences();

    for (String table :
        new String[] {
          "owner_meta",
          "user_meta",
          "group_meta",
          "role_meta_securable_object",
          "role_meta",
          "tag_relation_meta",
          "tag_meta",
          "policy_relation_meta",
          "policy_meta"
        }) {
      assertTrue(sql.contains(table), () -> "Missing external reference check for " + table);
    }
    assertTrue(sql.contains("owner.deleted_at = 0"), sql);
    assertTrue(sql.contains("role.deleted_at = 0"), sql);
    assertTrue(sql.contains("tag.deleted_at = 0"), sql);
    assertTrue(sql.contains("policy.deleted_at = 0"), sql);
  }
}
