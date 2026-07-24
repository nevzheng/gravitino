--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--  http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

ALTER TABLE user_meta ADD COLUMN IF NOT EXISTS external_id VARCHAR(256) DEFAULT NULL;
ALTER TABLE user_meta ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE group_meta ADD COLUMN IF NOT EXISTS external_id VARCHAR(256) DEFAULT NULL;

COMMENT ON COLUMN user_meta.external_id IS 'external identifier from an upstream identity system';
COMMENT ON COLUMN user_meta.enabled IS 'whether the user is enabled, 0 is disabled, 1 is enabled';
COMMENT ON COLUMN group_meta.external_id IS 'external identifier from an upstream identity system';

CREATE UNIQUE INDEX IF NOT EXISTS uk_mid_ueid_del ON user_meta (metalake_id, external_id, deleted_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mid_geid_del ON group_meta (metalake_id, external_id, deleted_at);

ALTER TABLE table_column_version_info
    ALTER COLUMN column_comment TYPE VARCHAR(4096);

ALTER TABLE table_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE catalog_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE schema_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE table_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE function_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE function_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE model_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE model_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE model_version_alias_rel ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE fileset_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE fileset_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE view_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE view_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE topic_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE table_column_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE owner_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE role_meta_securable_object ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE tag_relation_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE statistic_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE policy_relation_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE metalake_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE user_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE user_role_rel ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE group_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE group_role_rel ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE role_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE tag_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE policy_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE policy_version_info ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE job_template_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);
ALTER TABLE job_run_meta ADD COLUMN IF NOT EXISTS deletion_id VARCHAR(64);

COMMENT ON COLUMN table_meta.deletion_id IS 'table deletion generation identifier';
COMMENT ON COLUMN catalog_meta.deletion_id IS 'catalog deletion generation identifier';
COMMENT ON COLUMN schema_meta.deletion_id IS 'schema deletion generation identifier';
COMMENT ON COLUMN table_version_info.deletion_id IS 'table deletion generation identifier';
COMMENT ON COLUMN function_meta.deletion_id IS 'function deletion generation identifier';
COMMENT ON COLUMN function_version_info.deletion_id IS 'function deletion generation identifier';
COMMENT ON COLUMN model_meta.deletion_id IS 'model deletion generation identifier';
COMMENT ON COLUMN model_version_info.deletion_id IS 'model deletion generation identifier';
COMMENT ON COLUMN model_version_alias_rel.deletion_id IS 'model deletion generation identifier';
COMMENT ON COLUMN fileset_meta.deletion_id IS 'fileset deletion generation identifier';
COMMENT ON COLUMN fileset_version_info.deletion_id IS 'fileset deletion generation identifier';
COMMENT ON COLUMN view_meta.deletion_id IS 'view deletion generation identifier';
COMMENT ON COLUMN view_version_info.deletion_id IS 'view deletion generation identifier';
COMMENT ON COLUMN topic_meta.deletion_id IS 'topic deletion generation identifier';
COMMENT ON COLUMN table_column_version_info.deletion_id IS 'column deletion generation identifier';
COMMENT ON COLUMN owner_meta.deletion_id IS 'owner relation deletion generation identifier';
COMMENT ON COLUMN role_meta_securable_object.deletion_id IS 'securable object deletion generation identifier';
COMMENT ON COLUMN tag_relation_meta.deletion_id IS 'tag relation deletion generation identifier';
COMMENT ON COLUMN statistic_meta.deletion_id IS 'statistic deletion generation identifier';
COMMENT ON COLUMN policy_relation_meta.deletion_id IS 'policy relation deletion generation identifier';
COMMENT ON COLUMN metalake_meta.deletion_id IS 'metalake deletion generation identifier';
COMMENT ON COLUMN user_meta.deletion_id IS 'user deletion generation identifier';
COMMENT ON COLUMN user_role_rel.deletion_id IS 'user-role deletion generation identifier';
COMMENT ON COLUMN group_meta.deletion_id IS 'group deletion generation identifier';
COMMENT ON COLUMN group_role_rel.deletion_id IS 'group-role deletion generation identifier';
COMMENT ON COLUMN role_meta.deletion_id IS 'role deletion generation identifier';
COMMENT ON COLUMN tag_meta.deletion_id IS 'tag deletion generation identifier';
COMMENT ON COLUMN policy_meta.deletion_id IS 'policy deletion generation identifier';
COMMENT ON COLUMN policy_version_info.deletion_id IS 'policy version deletion generation identifier';
COMMENT ON COLUMN job_template_meta.deletion_id IS 'job template deletion generation identifier';
COMMENT ON COLUMN job_run_meta.deletion_id IS 'job run deletion generation identifier';

CREATE TABLE IF NOT EXISTS entity_deletion (
  deletion_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
  entity_type     VARCHAR(32)  NOT NULL,
  entity_id       BIGINT       NOT NULL,
  metalake_id     BIGINT       NOT NULL,
  catalog_id      BIGINT,
  parent_id       BIGINT,
  entity_name     VARCHAR(256) NOT NULL,
  deleted_at      BIGINT       NOT NULL,
  expires_at      BIGINT       NOT NULL,
  deleted_by      VARCHAR(128),
  entity_version  BIGINT,
  affected_row_count BIGINT,
  state           VARCHAR(16)  NOT NULL,
  revision        BIGINT       NOT NULL DEFAULT 0,
  restored_at     BIGINT,
  restore_etag    VARCHAR(256),
  purged_at       BIGINT,
  UNIQUE (entity_type, entity_id, deleted_at)
);
CREATE INDEX IF NOT EXISTS idx_ed_parent_state_time
  ON entity_deletion (entity_type, parent_id, state, deleted_at);
CREATE INDEX IF NOT EXISTS idx_ed_name_time
  ON entity_deletion (entity_type, parent_id, entity_name, deleted_at);
COMMENT ON TABLE entity_deletion IS 'durable soft-deletion generations and restore receipts';
COMMENT ON COLUMN entity_deletion.deletion_id IS 'immutable identifier for one deletion generation';
COMMENT ON COLUMN entity_deletion.entity_type IS 'Gravitino entity type';
COMMENT ON COLUMN entity_deletion.entity_id IS 'immutable identifier of the deleted entity';
COMMENT ON COLUMN entity_deletion.metalake_id IS 'immutable identifier of the owning metalake';
COMMENT ON COLUMN entity_deletion.catalog_id IS 'immutable identifier of the owning catalog, when applicable';
COMMENT ON COLUMN entity_deletion.parent_id IS 'immutable identifier of the immediate parent, when applicable';
COMMENT ON COLUMN entity_deletion.entity_name IS 'entity name at deletion time';
COMMENT ON COLUMN entity_deletion.deleted_at IS 'deletion timestamp in milliseconds';
COMMENT ON COLUMN entity_deletion.expires_at IS 'creation-time recoverability expiry snapshot in milliseconds';
COMMENT ON COLUMN entity_deletion.deleted_by IS 'principal that requested deletion';
COMMENT ON COLUMN entity_deletion.entity_version IS 'entity version captured at deletion time, when applicable';
COMMENT ON COLUMN entity_deletion.affected_row_count IS 'number of metadata rows stamped by the deletion, when known';
COMMENT ON COLUMN entity_deletion.state IS 'DELETED | RESTORING | RESTORED | PURGING | PURGED';
COMMENT ON COLUMN entity_deletion.revision IS 'optimistic state-machine revision';
COMMENT ON COLUMN entity_deletion.restored_at IS 'successful restore timestamp in milliseconds';
COMMENT ON COLUMN entity_deletion.restore_etag IS 'exact entity tag accepted by the successful restore';
COMMENT ON COLUMN entity_deletion.purged_at IS 'permanent purge timestamp in milliseconds';
