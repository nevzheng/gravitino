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
CREATE INDEX IF NOT EXISTS `idx_tm_deletion` ON `table_meta` (`deletion_id`);

ALTER TABLE `table_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_tvi_table_deletion`
    ON `table_version_info` (`table_id`, `deletion_id`);

ALTER TABLE `table_column_version_info`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'column deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_tcvi_table_deletion`
    ON `table_column_version_info` (`table_id`, `deletion_id`);

ALTER TABLE `owner_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_owner_object_deletion`
    ON `owner_meta` (`metadata_object_id`, `metadata_object_type`, `deletion_id`);

ALTER TABLE `role_meta_securable_object`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_securable_object_deletion`
    ON `role_meta_securable_object` (`metadata_object_id`, `type`, `deletion_id`);

ALTER TABLE `tag_relation_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_tag_relation_deletion`
    ON `tag_relation_meta` (`deletion_id`, `metadata_object_type`, `metadata_object_id`);

ALTER TABLE `policy_relation_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_policy_relation_deletion`
    ON `policy_relation_meta` (`metadata_object_id`, `metadata_object_type`, `deletion_id`);

ALTER TABLE `statistic_meta`
    ADD COLUMN `deletion_id` VARCHAR(64) DEFAULT NULL COMMENT 'table deletion generation identifier' AFTER `deleted_at`;
CREATE INDEX IF NOT EXISTS `idx_statistic_object_deletion`
    ON `statistic_meta` (`metadata_object_id`, `metadata_object_type`, `deletion_id`);

CREATE TABLE IF NOT EXISTS `entity_deletion` (
  `deletion_id`              VARCHAR(64)   NOT NULL COMMENT 'opaque identifier for one deletion generation',
  `entity_type`              VARCHAR(32)   NOT NULL COMMENT 'entity type, TABLE in the Iceberg REST implementation',
  `entity_id`                BIGINT        NOT NULL COMMENT 'immutable source entity id',
  `entity_version`           BIGINT        NOT NULL COMMENT 'source entity version captured at deletion',
  `metalake_id`              BIGINT        NOT NULL COMMENT 'immutable owning metalake id',
  `catalog_id`               BIGINT        NOT NULL COMMENT 'immutable owning catalog id',
  `parent_id`                BIGINT        NOT NULL COMMENT 'immutable immediate parent id, schema id for a table',
  `namespace_snapshot`       VARCHAR(512)  NOT NULL COMMENT 'namespace snapshot used for routing and audit',
  `entity_name_snapshot`     VARCHAR(128)  NOT NULL COMMENT 'entity name captured at deletion',
  `name_lookup_key`          VARCHAR(64)   NOT NULL COMMENT 'digest used to find deletion generations by canonical name',
  `active_name_key`          VARCHAR(64)   DEFAULT NULL COMMENT 'unique name reservation while deletion is active',
  `state`                    VARCHAR(16)   NOT NULL COMMENT 'DELETED|RESTORED|PURGING|PURGED',
  `revision`                 BIGINT        NOT NULL DEFAULT 0 COMMENT 'optimistic lifecycle revision',
  `deleted_at`               BIGINT        NOT NULL COMMENT 'deletion timestamp in milliseconds',
  `retention_expires_at`     BIGINT        DEFAULT NULL COMMENT 'fixed recovery deadline, NULL means immediate nonrecoverable cleanup',
  `deleted_by`               VARCHAR(128)  NOT NULL COMMENT 'actor that requested deletion',
  `purge_requested`          TINYINT(1)    NOT NULL COMMENT 'original Iceberg REST purgeRequested value for audit',
  `purge_job_type`           VARCHAR(64)   NOT NULL COMMENT 'durable purge executor type',
  `purge_job_id`             VARCHAR(64)   DEFAULT NULL COMMENT 'batch purge job that claimed this generation',
  `cleanup_status`           VARCHAR(16)   DEFAULT NULL COMMENT 'PENDING|RUNNING|FAILED|SUCCEEDED',
  `cleanup_attempt_count`    INT           NOT NULL DEFAULT 0 COMMENT 'number of cleanup attempts',
  `cleanup_last_error`       VARCHAR(2048) DEFAULT NULL COMMENT 'sanitized most recent cleanup error',
  `accepted_restore_etag`    VARCHAR(192)  DEFAULT NULL COMMENT 'deletion action ETag accepted by successful UNDROP',
  `request_id`               VARCHAR(128)  DEFAULT NULL COMMENT 'originating request id',
  `correlation_id`           VARCHAR(128)  DEFAULT NULL COMMENT 'lifecycle correlation id',
  `restored_at`              BIGINT        DEFAULT NULL COMMENT 'successful restore timestamp in milliseconds',
  `purged_at`                BIGINT        DEFAULT NULL COMMENT 'successful purge timestamp in milliseconds',
  `updated_at`               BIGINT        NOT NULL COMMENT 'last lifecycle update timestamp in milliseconds',
  PRIMARY KEY (`deletion_id`),
  UNIQUE KEY `uk_entity_deletion_active_name` (`active_name_key`),
  KEY `idx_entity_deletion_name_lookup` (`name_lookup_key`, `deleted_at`, `deletion_id`),
  KEY `idx_entity_deletion_entity_history` (`entity_type`, `entity_id`, `deleted_at`, `deletion_id`),
  KEY `idx_entity_deletion_gc` (`state`, `retention_expires_at`, `deletion_id`),
  KEY `idx_entity_deletion_purge_job` (`purge_job_id`, `cleanup_status`, `deletion_id`)
) COMMENT='durable deletion lifecycle actions and terminal receipts';

CREATE TABLE IF NOT EXISTS `iceberg_deletion_context` (
  `deletion_id`           VARCHAR(64)  NOT NULL COMMENT 'deletion generation identifier',
  `iceberg_namespace`     VARCHAR(512) NOT NULL COMMENT 'Iceberg namespace snapshot',
  `iceberg_table_name`    VARCHAR(128) NOT NULL COMMENT 'Iceberg table name snapshot',
  `iceberg_table_uuid`    VARCHAR(64)  NOT NULL COMMENT 'Iceberg table UUID',
  `metadata_location`     CLOB         NOT NULL COMMENT 'metadata root used by the purge worker',
  `file_io_impl`          VARCHAR(256) NOT NULL COMMENT 'FileIO implementation class',
  `protected_file_io_ref` CLOB         NOT NULL COMMENT 'protected reference used to reconstruct FileIO without exposing secrets',
  `context_digest`        VARCHAR(64)  NOT NULL COMMENT 'digest of immutable purge input',
  `created_at`            BIGINT       NOT NULL COMMENT 'creation timestamp in milliseconds',
  `updated_at`            BIGINT       NOT NULL COMMENT 'last update timestamp in milliseconds',
  PRIMARY KEY (`deletion_id`)
) COMMENT='immutable Iceberg REST purge context keyed by deletion generation';

CREATE TABLE IF NOT EXISTS `entity_deletion_audit` (
  `audit_id`                 VARCHAR(64)   NOT NULL COMMENT 'audit event identifier',
  `deletion_id`              VARCHAR(64)   NOT NULL COMMENT 'deletion generation identifier',
  `entity_type`              VARCHAR(32)   NOT NULL COMMENT 'entity type',
  `entity_id`                BIGINT        NOT NULL COMMENT 'immutable source entity id',
  `event_type`               VARCHAR(64)   NOT NULL COMMENT 'lifecycle event type',
  `action_revision`          BIGINT        DEFAULT NULL COMMENT 'action revision associated with the event',
  `prior_state`              VARCHAR(16)   DEFAULT NULL COMMENT 'prior lifecycle state',
  `new_state`                VARCHAR(16)   DEFAULT NULL COMMENT 'new lifecycle state',
  `prior_cleanup_status`     VARCHAR(16)   DEFAULT NULL COMMENT 'prior cleanup status',
  `new_cleanup_status`       VARCHAR(16)   DEFAULT NULL COMMENT 'new cleanup status',
  `purge_job_id`             VARCHAR(64)   DEFAULT NULL COMMENT 'associated purge job id',
  `lease_epoch`              BIGINT        DEFAULT NULL COMMENT 'purge worker fencing epoch',
  `actor`                    VARCHAR(128)  NOT NULL COMMENT 'request actor or worker identity',
  `request_id`               VARCHAR(128)  DEFAULT NULL COMMENT 'request id',
  `correlation_id`           VARCHAR(128)  NOT NULL COMMENT 'lifecycle correlation id',
  `reason_code`              VARCHAR(64)   DEFAULT NULL COMMENT 'bounded machine-readable reason',
  `reason`                   VARCHAR(2048) DEFAULT NULL COMMENT 'sanitized reason without credentials or secrets',
  `created_at`               BIGINT        NOT NULL COMMENT 'event timestamp in milliseconds',
  PRIMARY KEY (`audit_id`),
  KEY `idx_entity_deletion_audit_action` (`deletion_id`, `created_at`, `audit_id`)
) COMMENT='append-only deletion lifecycle audit events';

CREATE TABLE IF NOT EXISTS `iceberg_purge_job` (
  `purge_job_id`       VARCHAR(64)   NOT NULL COMMENT 'opaque identifier for one bounded purge batch',
  `purge_job_type`     VARCHAR(64)   NOT NULL COMMENT 'durable executor type, ICEBERG_REST_PURGE in this implementation',
  `state`              VARCHAR(32)   NOT NULL COMMENT 'PENDING|RUNNING|SUCCEEDED|PARTIAL_FAILED|FAILED',
  `owner`              VARCHAR(128)  DEFAULT NULL COMMENT 'worker currently holding the batch lease',
  `lease_epoch`        BIGINT        NOT NULL DEFAULT 0 COMMENT 'monotonic fencing token incremented at every claim',
  `lease_expires_at`   BIGINT        DEFAULT NULL COMMENT 'server timestamp after which another worker may reclaim the batch',
  `heartbeat_at`       BIGINT        DEFAULT NULL COMMENT 'last successful lease heartbeat in milliseconds',
  `attempt_count`      INT           NOT NULL DEFAULT 0 COMMENT 'number of batch claims including reclaims',
  `item_count`         INT           NOT NULL COMMENT 'number of deletion actions in the batch',
  `pending_count`      INT           NOT NULL DEFAULT 0 COMMENT 'table items waiting to run',
  `running_count`      INT           NOT NULL DEFAULT 0 COMMENT 'table items currently running',
  `succeeded_count`    INT           NOT NULL DEFAULT 0 COMMENT 'table items durably purged',
  `failed_count`       INT           NOT NULL DEFAULT 0 COMMENT 'table items at their retry ceiling',
  `retrying_count`     INT           NOT NULL DEFAULT 0 COMMENT 'table items waiting for another attempt',
  `last_error`         VARCHAR(2048) DEFAULT NULL COMMENT 'sanitized batch-level error without credentials or paths',
  `created_by`         VARCHAR(128)  NOT NULL COMMENT 'collector identity that created the batch',
  `request_id`         VARCHAR(128)  DEFAULT NULL COMMENT 'collector request identifier',
  `correlation_id`     VARCHAR(128)  NOT NULL COMMENT 'audit correlation identifier for the batch',
  `created_at`         BIGINT        NOT NULL COMMENT 'creation timestamp in milliseconds',
  `started_at`         BIGINT        DEFAULT NULL COMMENT 'first successful claim timestamp in milliseconds',
  `completed_at`       BIGINT        DEFAULT NULL COMMENT 'terminal timestamp in milliseconds',
  `updated_at`         BIGINT        NOT NULL COMMENT 'last durable job update in milliseconds',
  PRIMARY KEY (`purge_job_id`),
  KEY `idx_iceberg_purge_job_claim` (`state`, `lease_expires_at`, `updated_at`, `purge_job_id`)
) COMMENT='durable bounded Iceberg purge batch headers';

CREATE TABLE IF NOT EXISTS `iceberg_purge_plan` (
  `deletion_id`       VARCHAR(64) NOT NULL COMMENT 'deletion action whose exact physical targets are snapshotted',
  `purge_job_id`      VARCHAR(64) NOT NULL COMMENT 'batch that owns this table item',
  `context_digest`    VARCHAR(64) NOT NULL COMMENT 'digest of the immutable Iceberg deletion context',
  `state`             VARCHAR(16) NOT NULL COMMENT 'PLANNING|READY',
  `target_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'durable target count after the plan becomes READY',
  `root_target_id`    VARCHAR(64) DEFAULT NULL COMMENT 'root metadata target, which must have the greatest delete order',
  `created_at`        BIGINT      NOT NULL COMMENT 'plan creation timestamp in milliseconds',
  `completed_at`      BIGINT      DEFAULT NULL COMMENT 'timestamp when the exact target snapshot became READY',
  `updated_at`        BIGINT      NOT NULL COMMENT 'last durable plan update in milliseconds',
  PRIMARY KEY (`deletion_id`),
  KEY `idx_iceberg_purge_plan_job` (`purge_job_id`, `state`, `deletion_id`)
) COMMENT='durable completeness marker for one deletion action physical target snapshot';

CREATE TABLE IF NOT EXISTS `iceberg_purge_target` (
  `deletion_id`       VARCHAR(64)   NOT NULL COMMENT 'owning deletion action',
  `target_id`         VARCHAR(64)   NOT NULL COMMENT 'deterministic digest of deletion id, kind, URI, and object version',
  `purge_job_id`      VARCHAR(64)   NOT NULL COMMENT 'batch that owns this physical target',
  `target_type`       VARCHAR(32)   NOT NULL COMMENT 'DATA|MANIFEST|MANIFEST_LIST|STATISTICS|METADATA|ROOT_METADATA',
  `target_uri`        CLOB          NOT NULL COMMENT 'exact object URI snapshotted before cleanup starts',
  `object_version`    VARCHAR(256)  DEFAULT NULL COMMENT 'exact provider object version when available',
  `delete_order`      INT           NOT NULL COMMENT 'child-before-parent order with root metadata greatest',
  `state`             VARCHAR(16)   NOT NULL COMMENT 'PENDING|RUNNING|RETRYING|SUCCEEDED|FAILED',
  `lease_epoch`       BIGINT        NOT NULL DEFAULT 0 COMMENT 'batch fencing epoch that claimed the target',
  `attempt_count`     INT           NOT NULL DEFAULT 0 COMMENT 'number of external delete attempts',
  `last_error`        VARCHAR(2048) DEFAULT NULL COMMENT 'sanitized most recent target error',
  `created_at`        BIGINT        NOT NULL COMMENT 'target snapshot timestamp in milliseconds',
  `updated_at`        BIGINT        NOT NULL COMMENT 'last progress update timestamp in milliseconds',
  PRIMARY KEY (`deletion_id`, `target_id`),
  KEY `idx_iceberg_purge_target_work` (`purge_job_id`, `deletion_id`, `state`, `delete_order`, `target_id`)
) COMMENT='per-object progress ledger for restart-safe Iceberg hard deletion';
