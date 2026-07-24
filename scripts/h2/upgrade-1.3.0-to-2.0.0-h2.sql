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

ALTER TABLE `user_meta` ADD COLUMN `external_id` VARCHAR(256) DEFAULT NULL COMMENT 'external identifier from an upstream identity system' AFTER `metalake_id`;
ALTER TABLE `user_meta` ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether the user is enabled, 0 is disabled, 1 is enabled' AFTER `external_id`;

ALTER TABLE `group_meta` ADD COLUMN `external_id` VARCHAR(256) DEFAULT NULL COMMENT 'external identifier from an upstream identity system' AFTER `metalake_id`;

CREATE UNIQUE INDEX IF NOT EXISTS `uk_mid_ueid_del` ON `user_meta` (`metalake_id`, `external_id`, `deleted_at`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_mid_geid_del` ON `group_meta` (`metalake_id`, `external_id`, `deleted_at`);

ALTER TABLE `table_column_version_info`
    ALTER COLUMN `column_comment` VARCHAR(4096) DEFAULT '';

ALTER TABLE `table_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `catalog_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'catalog deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `schema_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'schema deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `table_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `function_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'function deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `function_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'function deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `model_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'model deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `model_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'model deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `model_version_alias_rel`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'model deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `fileset_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'fileset deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `fileset_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'fileset deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `view_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'view deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `view_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'view deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `topic_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'topic deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `table_column_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'column deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `owner_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'owner relation deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `role_meta_securable_object`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'securable object deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `tag_relation_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'tag relation deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `statistic_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'statistic deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `policy_relation_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'policy relation deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `metalake_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'metalake deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `user_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'user deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `user_role_rel` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'user-role deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `group_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'group deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `group_role_rel` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'group-role deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `role_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'role deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `tag_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'tag deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `policy_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'policy deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `policy_version_info` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'policy version deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `job_template_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'job template deletion generation identifier' AFTER `deleted_at`;
ALTER TABLE `job_run_meta` ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'job run deletion generation identifier' AFTER `deleted_at`;

CREATE TABLE IF NOT EXISTS `entity_deletion` (
  `deletion_id`      VARCHAR(64)  NOT NULL COMMENT 'immutable identifier for one deletion generation',
  `entity_type`      VARCHAR(32)  NOT NULL COMMENT 'Gravitino entity type',
  `entity_id`        BIGINT       NOT NULL COMMENT 'immutable identifier of the deleted entity',
  `metalake_id`      BIGINT       NOT NULL COMMENT 'immutable identifier of the owning metalake',
  `catalog_id`       BIGINT       NULL COMMENT 'immutable identifier of the owning catalog, when applicable',
  `parent_id`        BIGINT       NULL COMMENT 'immutable identifier of the immediate parent, when applicable',
  `entity_name`      VARCHAR(256) NOT NULL COMMENT 'entity name at deletion time',
  `deleted_at`       BIGINT       NOT NULL COMMENT 'deletion timestamp in milliseconds',
  `expires_at`       BIGINT       NOT NULL COMMENT 'creation-time recoverability expiry snapshot in milliseconds',
  `deleted_by`       VARCHAR(128) NULL COMMENT 'principal that requested deletion',
  `entity_version`   BIGINT       NULL COMMENT 'entity version captured at deletion time, when applicable',
  `affected_row_count` BIGINT     NULL COMMENT 'number of metadata rows stamped by the deletion, when known',
  `state`            VARCHAR(16)  NOT NULL COMMENT 'DELETED | RESTORING | RESTORED | PURGING | PURGED',
  `revision`         BIGINT       NOT NULL DEFAULT 0 COMMENT 'optimistic state-machine revision',
  `restored_at`      BIGINT       NULL COMMENT 'successful restore timestamp in milliseconds',
  `restore_etag`     VARCHAR(256) NULL COMMENT 'exact entity tag accepted by the successful restore',
  `purged_at`        BIGINT       NULL COMMENT 'permanent purge timestamp in milliseconds',
  PRIMARY KEY (`deletion_id`),
  UNIQUE (`entity_type`, `entity_id`, `deleted_at`)
) COMMENT='durable soft-deletion generations and restore receipts';
CREATE INDEX IF NOT EXISTS `idx_ed_parent_state_time`
  ON `entity_deletion` (`entity_type`, `parent_id`, `state`, `deleted_at`);
CREATE INDEX IF NOT EXISTS `idx_ed_name_time`
  ON `entity_deletion` (`entity_type`, `parent_id`, `entity_name`, `deleted_at`);
