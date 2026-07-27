<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# [Metadata] Asynchronous Hard Delete and Soft Delete for Iceberg REST Catalog — Deletion Lifecycle and Purge Jobs

| Field        | Value                                                                           |
| ------------ | ------------------------------------------------------------------------------- |
| Status       | Table API/store POC design draft; implementation proposed                      |
| Author       | Nevin Zheng                                                                     |
| Created      | 2026-07-26                                                                      |
| Last updated | 2026-07-26                                                                      |
| Module       | `iceberg/iceberg-rest-server`, `core`, relational store                         |
| Related      | [Metadata Undelete Semantics API draft](iceberg-rest-table-deletion-lifecycle.md); [asynchronous hard-delete PRD](async-iceberg-rest-hard-deletion.md) |
| Reviewers    | Jerry Shao (primary); additional reviewers TBD                                  |

## Scope

This is the storage and concurrency model behind the Iceberg REST table-deletion API. Read it with
the API/semantics draft to understand not only what DELETE and UNDROP do, but why an old cleanup
worker cannot delete a restored, re-dropped, or same-name replacement table.

The design is deliberately narrow: `entity_type=TABLE`, an Iceberg-specific context, conditional
UNDROP, retained deletion generations, and restart-safe asynchronous hard purge. The shared action
record is generically named so other entity handlers can adopt the identifier shape later, but this
POC does not define generic metadata recovery, cascade recovery, alternate purge policies, provider
trash, or a cleanup plug-in SPI.

## Goals

1. **Address one deletion generation exactly.** `entity_id` preserves the original immutable
   Gravitino table identity; opaque `deletion_id` identifies one drop of that table. Catalog,
   namespace, and table name are routing, lookup, audit, and reservation snapshots—not mutation
   identity.
2. **Separate sources of truth.** The deletion action owns lifecycle and retention, `table_meta`
   owns the relational tombstone, the Iceberg context owns recovery/purge inputs, and an append-only
   log owns audit history.
3. **Fail closed across the relational/Iceberg boundary.** DELETE and UNDROP are small sagas. An
   ambiguous external result remains reserved and repairable; it is never guessed into success.
4. **Make purge durable at batch and table granularity.** One bounded job header owns scheduling,
   lease, heartbeat, and aggregate progress. Each deletion action is a durable job item and reaches
   `PURGED` independently.
5. **Use one optimistic concurrency boundary.** Action revision plus exact predicates arbitrate
   UNDROP, GC, retries, stale workers, and lost responses.

## Metadata model

The POC adds three lifecycle records, one append-only audit record, and nullable deletion pointers
on the table metadata rows changed by a drop. The schema below is the target logical shape;
backend-specific migrations and index lengths remain implementation work.

### What each record represents

`entity_deletion` is one durable deletion event, not the live/dead flag for every table. A table's
current relational state remains on `table_meta.deleted_at`; while tombstoned, that row points to
the exact deletion action through `table_meta.deletion_id`.

`iceberg_deletion_context` is the one-to-one handler payload for that action. It freezes the
Iceberg identifier, table UUID, metadata location, and protected FileIO reconstruction input needed
to register or purge the old generation without consulting a mutable live table.

`iceberg_purge_job` is one bounded batch execution. Many actions may point to the same job. The
header owns worker coordination; action rows own per-table cleanup progress and error state.

For example: `orders` is dropped as D1, restored, and later dropped again as D2. D1 remains
`RESTORED` as a receipt. D2 is a new row with a new retention clock and can never reopen or mutate
D1.

### Schema

```sql
CREATE TABLE iceberg_purge_job (
  purge_job_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
  purge_job_type        VARCHAR(64)  NOT NULL, -- ICEBERG_REST_PURGE
  state                 VARCHAR(32)  NOT NULL, -- PENDING|RUNNING|SUCCEEDED|PARTIAL_FAILED|FAILED
  lease_owner           VARCHAR(128) NULL,
  lease_epoch           BIGINT       NOT NULL,
  lease_expires_at      BIGINT       NULL,
  heartbeat_at          BIGINT       NULL,
  next_run_at           BIGINT       NULL,
  lease_attempt_count   INT          NOT NULL DEFAULT 0,
  max_item_attempts     INT          NOT NULL,
  item_count            INT          NOT NULL,
  succeeded_count       INT          NOT NULL DEFAULT 0,
  failed_count          INT          NOT NULL DEFAULT 0,
  cleanup_last_error    VARCHAR(2048) NULL,
  retry_of_job_id       VARCHAR(64)  NULL,
  correlation_id        VARCHAR(128) NOT NULL,
  created_at            BIGINT       NOT NULL,
  updated_at            BIGINT       NOT NULL,
  completed_at          BIGINT       NULL
);

CREATE INDEX idx_iceberg_purge_job_lease
  ON iceberg_purge_job (state, next_run_at, lease_expires_at);

CREATE TABLE entity_deletion (
  deletion_id           VARCHAR(64)  NOT NULL PRIMARY KEY,
  entity_type           VARCHAR(32)  NOT NULL, -- TABLE in this POC
  entity_id             BIGINT       NOT NULL, -- immutable source table_id
  entity_version        BIGINT       NOT NULL,
  metalake_id           BIGINT       NOT NULL,
  catalog_id            BIGINT       NOT NULL,
  schema_id             BIGINT       NOT NULL,
  namespace_snapshot    VARCHAR(512) NOT NULL,
  entity_name_snapshot  VARCHAR(256) NOT NULL,
  name_lookup_key       VARCHAR(64)  NOT NULL,
  active_name_key       VARCHAR(64)  NULL,
  state                 VARCHAR(16)  NOT NULL, -- DELETED|RESTORED|PURGING|PURGED
  revision              BIGINT       NOT NULL,
  deleted_at            BIGINT       NOT NULL,
  retention_expires_at  BIGINT       NULL,
  deleted_by            VARCHAR(128) NOT NULL,
  purge_requested       BOOLEAN      NOT NULL,
  purge_job_type        VARCHAR(64)  NOT NULL, -- ICEBERG_REST_PURGE
  purge_job_id          VARCHAR(64)  NULL,
  cleanup_status        VARCHAR(16)  NULL, -- PENDING|RUNNING|FAILED|SUCCEEDED
  cleanup_progress      VARCHAR(32)  NULL,
  cleanup_checkpoint_ref MEDIUMTEXT  NULL,
  cleanup_attempt_count INT          NOT NULL DEFAULT 0,
  cleanup_next_retry_at BIGINT       NULL,
  cleanup_last_error_code VARCHAR(64) NULL,
  cleanup_last_error    VARCHAR(2048) NULL,
  cleanup_last_error_at BIGINT       NULL,
  affected_metadata_counts MEDIUMTEXT NOT NULL,
  accepted_restore_etag VARCHAR(192) NULL,
  request_id            VARCHAR(128) NOT NULL,
  correlation_id        VARCHAR(128) NOT NULL,
  restored_at           BIGINT       NULL,
  purged_at             BIGINT       NULL,
  updated_at            BIGINT       NOT NULL
);

CREATE UNIQUE INDEX uk_entity_deletion_active_name
  ON entity_deletion (active_name_key);

CREATE INDEX idx_entity_deletion_name_lookup
  ON entity_deletion (name_lookup_key, deleted_at DESC, deletion_id);

CREATE INDEX idx_entity_deletion_entity
  ON entity_deletion (entity_type, entity_id, deleted_at DESC);

CREATE INDEX idx_entity_deletion_gc
  ON entity_deletion (state, retention_expires_at, deletion_id);

CREATE INDEX idx_entity_deletion_job_items
  ON entity_deletion (purge_job_id, cleanup_status, deletion_id);

CREATE TABLE iceberg_deletion_context (
  deletion_id             VARCHAR(64)  NOT NULL PRIMARY KEY,
  iceberg_namespace       VARCHAR(512) NOT NULL,
  iceberg_table_name      VARCHAR(256) NOT NULL,
  iceberg_table_uuid      VARCHAR(64)  NOT NULL,
  metadata_location       MEDIUMTEXT   NOT NULL,
  file_io_impl            VARCHAR(256) NOT NULL,
  protected_file_io_ref   MEDIUMTEXT   NOT NULL,
  context_digest          VARCHAR(64)  NOT NULL,
  operation_state         VARCHAR(32)  NOT NULL, -- STABLE plus internal saga states
  operation_owner         VARCHAR(128) NULL,
  operation_epoch         BIGINT       NOT NULL DEFAULT 0,
  operation_lease_expires_at BIGINT    NULL,
  operation_heartbeat_at  BIGINT       NULL,
  operation_accepted_etag VARCHAR(192) NULL,
  operation_request_id    VARCHAR(128) NULL,
  operation_correlation_id VARCHAR(128) NULL,
  external_call_started_at BIGINT      NULL,
  operation_last_error_code VARCHAR(64) NULL,
  reconciliation_reason  VARCHAR(2048) NULL,
  created_at              BIGINT       NOT NULL,
  updated_at              BIGINT       NOT NULL
);

ALTER TABLE table_meta ADD COLUMN deletion_id VARCHAR(64) NULL;

CREATE INDEX idx_table_meta_deletion ON table_meta (deletion_id);

ALTER TABLE table_version_info
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE table_column_version_info
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE owner_meta
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE role_meta_securable_object
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE tag_relation_meta
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE policy_relation_meta
  ADD COLUMN deletion_id VARCHAR(64) NULL;
ALTER TABLE statistic_meta
  ADD COLUMN deletion_id VARCHAR(64) NULL;

-- Each affected table also receives an index on deletion_id.

CREATE TABLE entity_deletion_audit (
  audit_id              VARCHAR(64)  NOT NULL PRIMARY KEY,
  deletion_id           VARCHAR(64)  NOT NULL,
  entity_type           VARCHAR(32)  NOT NULL,
  entity_id             BIGINT       NOT NULL,
  event_type            VARCHAR(64)  NOT NULL,
  action_revision       BIGINT       NULL,
  prior_state           VARCHAR(16)  NULL,
  new_state             VARCHAR(16)  NULL,
  prior_cleanup_status  VARCHAR(16)  NULL,
  new_cleanup_status    VARCHAR(16)  NULL,
  operation_state       VARCHAR(32)  NULL,
  purge_job_id          VARCHAR(64)  NULL,
  lease_epoch           BIGINT       NULL,
  actor                 VARCHAR(128) NOT NULL,
  request_id            VARCHAR(128) NULL,
  correlation_id        VARCHAR(128) NOT NULL,
  reason_code           VARCHAR(64)  NULL,
  reason                 VARCHAR(2048) NULL,
  created_at            BIGINT       NOT NULL
);

CREATE INDEX idx_entity_deletion_audit_action
  ON entity_deletion_audit (deletion_id, created_at, audit_id);
```

`deletion_id` and `purge_job_id` are opaque tokens. `VARCHAR(64)` is intentional: the public
identifier does not expose entity type, table name, time, job type, or database ordering.

`name_lookup_key` is the immutable hexadecimal SHA-256 digest of a canonical, length-prefixed
encoding of `entity_type`, `catalog_id`, namespace components, and table name. Name discovery uses
the digest index and verifies the full stored snapshots. `active_name_key` holds the same value only
while an action is `DELETED` or `PURGING`. A digest collision fails closed as a name conflict; it can
block an unrelated name but can never authorize a mutation. `RESTORED` and `PURGED` clear only the
active key transactionally, so terminal receipts remain discoverable by exact ID without blocking
reuse.

The `table_meta` pointer is the canonical root relationship; affected-row pointers only scope the
same generation's related metadata. These are indexed logical foreign keys, following the existing
relational-store convention, and every one points from metadata to the action. There is no cascading
back-reference from `entity_deletion` to metadata: a `PURGED` receipt must survive hard deletion of
those rows. Context, action, and job cleanup uses explicit checked ordering rather than database
cascade. A purge-job header must not be pruned while any retained action still references it.

`context_digest` covers only immutable handler input—the Iceberg identity, metadata root, and
protected FileIO reference—not the mutable operation owner, epoch, lease, or reconciliation fields.

### Affected Gravitino metadata rows

The root pointer alone makes mutation of `table_meta` generation-safe, but it does not identify the
table version, columns, owner, securable-object relationships, tags, policies, and statistics that
the same drop soft-deleted. Restoring every historical row for `table_id` would be incorrect after a
restore and re-drop.

The POC therefore stamps D1 on every row that the table-delete transaction changes from live to
deleted. UNDROP and hard-delete select related rows by immutable table/object ID plus
`deletion_id=D1`; the root row additionally supplies the canonical pointer and source table ID. A
uniqueness conflict or unexpected row set fails the whole relational transaction and enters
reconciliation—it never widens to “all deleted rows for T1.”

The action stores a bounded per-table count summary for the stamped row groups. UNDROP requires one
root row, the expected current-version row, and the captured counts for optional related groups.
The generic relational GC adds `deletion_id IS NULL` to legacy cleanup predicates, so it cannot
remove action-managed rows before exact restore or purge finalization.

Using only a shared millisecond `deleted_at` is insufficient as a generation proof: the current
delete service evaluates timestamps in separate statements, and even an application-supplied time
can collide with a later restore/re-drop. `deleted_at` remains the PRD time field; `deletion_id` is
the generation discriminator.

### Relationship diagram

```text
table_meta + D1-stamped related rows       entity_deletion
┌──────────────────────┐  logical refs     ┌───────────────────────────────┐
│ table_id (PK)        │                   │ deletion_id (PK)              │
│ deleted_at           │                   │ entity_type=TABLE             │
│ deletion_id ─────────┼──────────────────>│ entity_id=source table_id     │
└──────────────────────┘                   │ state + revision              │
                                           │ retention + cleanup item      │
                                           │ purge_job_type + purge_job_id ├─────┐
                                           └──────────────┬────────────────┘     │ N:1
                                                          │ 1:1                  │
                                                          v                      v
                                         iceberg_deletion_context       iceberg_purge_job
                                         ┌───────────────────────┐      ┌──────────────────┐
                                         │ metadata location     │      │ lease + epoch    │
                                         │ UUID + identifier     │      │ batch progress   │
                                         │ protected FileIO ref  │      │ retry lineage    │
                                         │ internal saga marker  │      └──────────────────┘
                                         └───────────────────────┘

All lifecycle transitions ───────────────> append-only deletion audit
```

## Core terms

| Term                     | Definition                                                                                                                                 |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Entity ID                | Immutable original `table_meta.table_id`. It survives restore and is retained as origin/audit identity after hard deletion.                |
| Deletion ID              | Opaque primary key for one drop generation. A restored table's next drop always creates another deletion ID.                              |
| Name lookup key          | Immutable digest of the saved Iceberg REST route. It accelerates discovery only; full snapshots are verified before returning a result.   |
| Active-name key          | Nullable unique copy of the lookup key held by `DELETED`/`PURGING`; it is a reservation, never mutation identity.                          |
| Active deletion          | An action in `DELETED` or `PURGING`. It owns the canonical name reservation.                                                               |
| Recoverable deletion     | A stable `DELETED` action with `server_now < retention_expires_at`, no purge job, and no unresolved Iceberg operation marker.              |
| Iceberg context          | Immutable handler input captured at delete time: identifier, UUID, metadata root, and protected FileIO reconstruction data.               |
| Purge job                | One bounded batch execution and lease. It is not the table lifecycle and may contain several independently completing actions.            |
| Exact-generation predicate | Root: `table_id=entity_id AND table_meta.deletion_id=deletion_id`; related rows: the same table/object ID plus `deletion_id`.           |
| Terminal receipt         | A retained `RESTORED` or `PURGED` action used for exact replay, explained `410`, correlation, and audit.                                   |

## State machine

```text
                         successful UNDROP
             ┌────────────────────────────────────┐
             │                                    v
        ┌─────────┐                           ┌──────────┐
        │ DELETED │                           │ RESTORED │
        └────┬────┘                           └────┬─────┘
             │ retention_expires_at <= now;        │ receipt expiry
             │ bounded GC claim wins               v
             v                                    removed
        ┌─────────┐  successful item finalize ┌────────┐
        │ PURGING ├───────────────────────────>│ PURGED │
        └────┬────┘                            └───┬────┘
             │ cleanup failure:                    │ receipt expiry
             │ remain PURGING/FAILED               v
             └── retry or manual repair           removed
```

`DELETED` means logically deleted and retained. `RESTORED` cancels that deletion generation.
`PURGING` means a purge job owns the action and is the irreversible UNDROP boundary, even before its
first object-store call. `PURGED` means external cleanup was confirmed and the exact Gravitino
metadata generation was removed.

There is no public `RESTORING` or `PURGE_PENDING`. Iceberg calls cannot share a relational
transaction, so `iceberg_deletion_context.operation_state` records internal saga progress and
reconciliation without expanding the public action state machine.

Action state and cleanup status answer different questions:

| Action state | `cleanup_status` | Meaning                                                                                           |
| ------------ | ---------------- | ------------------------------------------------------------------------------------------------- |
| `DELETED`    | `PENDING`        | Retained; GC has not claimed it.                                                                  |
| `RESTORED`   | `NULL`           | Deletion was cancelled; no cleanup may run.                                                       |
| `PURGING`    | `PENDING`        | A batch owns the generation, but this item has not started external I/O. UNDROP is already denied. |
| `PURGING`    | `RUNNING`        | A leased worker is executing this item.                                                           |
| `PURGING`    | `FAILED`         | Cleanup is exhausted or nonretryable; UNDROP remains forbidden because deletion may be partial.    |
| `PURGED`     | `SUCCEEDED`      | External deletion and exact-generation relational finalization both completed.                    |

The Iceberg context's internal operation states are:

| Operation state                    | Meaning                                                                                         |
| ---------------------------------- | ----------------------------------------------------------------------------------------------- |
| `STABLE`                           | No external saga is in progress; public action predicates may run.                              |
| `DROP_PREPARED`                    | Action, context, tombstones, and reservation exist; unregister has not been proven.              |
| `DROP_RECONCILIATION_REQUIRED`     | The DELETE-side external outcome is unknown; recovery and GC are blocked.                        |
| `UNDROP_CLAIMED`                   | A fenced claimant owns the restore operation; no public `RESTORING` state is exposed.            |
| `UNDROP_COMPENSATING`              | Registration succeeded but relational restore did not; exact unregister compensation is active. |
| `UNDROP_RECONCILIATION_REQUIRED`   | Restore/compensation outcome is unknown; name remains reserved and GC is blocked.                |

## DELETE saga

The request must capture recovery state before it removes the Iceberg registration. Otherwise a
crash can leave no durable route back to the deleted table.

1. Authorize and load the live table, original `table_id`, Iceberg UUID, metadata location, and
   FileIO reconstruction input.
2. In one relational transaction, allocate D1; insert `entity_deletion` and
   `iceberg_deletion_context(operation_state=DROP_PREPARED)`; stamp
   `table_meta.deleted_at/deletion_id`; stamp D1 on every affected table-related row; reserve the
   canonical name; and append the prepared audit event. D1 is not discoverable or GC-eligible until
   its context becomes `STABLE`.
3. Unregister exactly the captured Iceberg identifier. This is metadata removal only; it never
   deletes files on the request thread.
4. In a second transaction, verify the same D1/table pointer, set the context `STABLE`, advance the
   action revision, and append `DELETE_ACCEPTED`. Only then return `204`.

If unregister provably fails without a side effect, compensation clears the D1 tombstone and name
reservation and records the failed attempt. If the outcome is unknown, D1 remains hidden,
reserved, and `DROP_RECONCILIATION_REQUIRED`; normal load/list continue to show the table as deleted,
same-name create/register returns a retryable conflict, and deletion discovery/UNDROP cannot proceed
until a reconciler proves registration state. A request timeout therefore means “outcome unknown,”
never “assume it failed.” A repeated Iceberg DELETE may return the normal missing-table response
after the action stabilizes; when retention is enabled, management discovery is the authoritative
way to observe D1.

The PRD controls are resolved at delete time:

| Configuration / request                     | Persisted result                                                                                          |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Soft delete disabled, `purgeRequested=false` | Existing metadata-only drop; this recovery/purge lifecycle is not created.                                |
| Soft delete disabled, `purgeRequested=true`  | Nonrecoverable hard cleanup; `retention_expires_at=NULL`, immediately GC-eligible.                         |
| Soft delete enabled, either request value    | `retention_expires_at=deleted_at + configured retention`; request flag is retained for audit only.         |
| Soft delete enabled, retention `0`           | Deadline equals deletion time; the action is immediately GC-eligible and has no reliable UNDROP window.   |

The exact properties are `gravitino.iceberg-rest.soft-delete.enabled` (default `false`) and
`gravitino.iceberg-rest.soft-delete.retention-ms` (default `86400000`; range 0–90 days). A persisted
deadline is authoritative for that generation; later configuration changes do not rewrite it.

## UNDROP transaction

UNDROP is an Iceberg handler operation, not a generic metadata `PATCH`. The request must carry D1
explicitly as well as D1's strong ETag; `If-Match` proves the representation but is never used as an
identifier.

The handler proceeds as follows:

1. Read D1 by primary key and verify the routed catalog/namespace/name snapshots. Check
   `state=DELETED`, exact current revision, `retention_expires_at > server_now`, no `purge_job_id`,
   stable context, matching `table_meta.table_id/deletion_id`, and that no different live object
   owns the name.
2. In one relational transaction, CAS and advance the action revision and change the context from
   `STABLE` to internal `UNDROP_CLAIMED`, with owner, monotonically increasing epoch, renewable
   lease, accepted input ETag, and operation request/correlation IDs. Before invoking Iceberg,
   persist `external_call_started_at`; before that marker, an expired claim can be cleared safely,
   but after it an expired lease requires reconciliation and is not directly claimable by GC.
3. Through an internal path that cannot auto-import or mint a new Gravitino identity, register the
   saved metadata location and verify the saved Iceberg table UUID.
4. In one relational transaction, fence on D1, revision, and operation epoch; restore the original
   `table_meta` generation; clear `table_meta.deletion_id` and D1's active-name key; commit
   `RESTORED`; restore only related rows stamped with D1; save the
   accepted ETag; and append the audit event. Return the current normal Iceberg `LoadTableResponse`
   only after both sides verify.

The claimant renews its lease throughout the external call. If it loses the epoch, it cannot commit
`RESTORED`. A reconciler first determines whether the saved generation was registered, then either
finishes the exact restore or unregisters only that registration. Proven compensation returns D1
to a stable, recoverable `DELETED` representation with a new revision. An ambiguous result remains
reserved and `recoverable=false`; it never becomes eligible for purge until reconciliation proves
that no restore side effect remains.

An exact replay matches D1's `accepted_restore_etag`. It verifies that the live row still has D1's
immutable `entity_id` and returns the table's current metadata. It does not require the live metadata
location to remain equal to the saved deletion snapshot, because legitimate Iceberg commits may
have advanced it after restoration.

## Expiry and GC claim

`PURGE_PENDING` is derived, not stored:

```text
state=DELETED
AND retention_expires_at <= server_now
AND purge_job_id IS NULL
AND iceberg_deletion_context.operation_state = STABLE
```

Migrated or bridged R1-style nonrecoverable actions with `retention_expires_at IS NULL` use a
separate immediate eligibility predicate. Current R1 has no action row. The generic relational GC
must not remove the table tombstone before this Iceberg deadline.

A free GC worker reads a small deterministic window ordered by deadline and deletion ID. In one
bounded claim transaction it:

1. creates one `iceberg_purge_job` header with `purge_job_type=ICEBERG_REST_PURGE`, correlation ID,
   and `state=PENDING`;
2. CASes every still-eligible candidate from `DELETED/PENDING` directly to `PURGING/PENDING`,
   advances its revision, and stamps the same `purge_job_id`; and
3. records the number actually claimed, deleting the empty header if every candidate lost its CAS.

No external cleanup occurs inside this transaction. UNDROP claims the same action revision and
context boundary, so only one side can win. Once the job association commits, POST UNDROP returns
`410` even if the worker has not issued its first storage request.

## Purge-job execution

### Header and item responsibilities

The job header is coordination state: batch type, owner, lease epoch, heartbeat, aggregate counts,
attempts, and correlation. It does not decide whether a table is deleted. Each action row is the
durable item and remains authoritative for that generation's cleanup status.

Aggregate counters are transactional caches of item state. A restart may recompute them from all
actions with the job ID. A corrupt or stale counter can delay header completion but can never mark
an item `PURGED`.

### Lease, heartbeat, and reclaim

A free worker CASes an eligible `PENDING` header—or a stale `RUNNING` header—to `RUNNING`, writes the
owner, increments `lease_epoch` and `lease_attempt_count`, and sets heartbeat/expiry. It then CASes
one ready `PURGING/PENDING` action to `PURGING/RUNNING` before external I/O. Heartbeat renewal runs on
a scheduler independent of blocking object-store calls. Every heartbeat, item transition,
aggregate update, and terminal write predicates on
`purge_job_id + lease_owner + lease_epoch + state=RUNNING`.

If work remains but every unfinished item is waiting for backoff, the owner returns the header to
`PENDING`, clears the lease fields, and sets `next_run_at` to the earliest durable item retry time.
A restarted replica therefore cannot collapse configured backoff into an immediate retry.

If the lease expires, another replica CASes the same header to a new owner and higher epoch. The old
worker must stop before issuing another delete batch when it observes lease loss. Object-store calls
already in flight cannot be recalled, so all targets must belong to the immutable old-generation
context and all relational completion remains fenced. Reclaim restarts incomplete items from their
saved root/context; it never selects another table by name.

### External hard cleanup

For each action the worker reconstructs the old Iceberg graph from its saved metadata root. It
deletes only objects reachable from that generation, in dependency order, and removes the root
metadata file last. It never recursively deletes a warehouse/table-name prefix. Missing targets may
count as already removed only when the handler can prove they are the exact snapshotted target.

The existing R1 traversal and heartbeat mechanics are useful, but its best-effort per-file helper
cannot prove normative success if individual delete errors are suppressed. The new handler must
propagate unresolved target failures and persist enough progress to distinguish “not attempted,”
“confirmed absent,” and “deleted.” The storage representation for very large target/checkpoint sets
is implementation work; it must remain bounded and restart-safe.

`cleanup_progress` records the last completed dependency level; `cleanup_checkpoint_ref` locates a
protected, bounded checkpoint when exact targets cannot fit on the action row. A missing root is
success evidence only when the same generation's checkpoint proves every lower level completed and
root deletion had begun. If the root is missing without that proof, the handler cannot reconstruct
the graph and enters manual repair instead of declaring `PURGED`.

Hard-delete semantics on a versioned object store also need exact provider treatment. For example,
a simple S3 `DELETE` may create a delete marker rather than permanently remove an older version.
Before destructive execution, the handler must resolve and durably checkpoint any exact object
version identifiers required by the product's hard-delete guarantee. Retrying an exact target is
logically safe; treating an unversioned name or prefix as the target is not.

### Per-item finalization

Only confirmed external success may finalize one action. In one relational transaction the worker
checks:

```text
job = expected purge_job_id + live lease owner/epoch
action = expected deletion_id + state PURGING + cleanup_status RUNNING
context = expected immutable digest + metadata location + Iceberg UUID
table_meta = entity_id + deletion_id
related metadata = entity/object id + deletion_id
saved table version = entity_version
```

It then hard-deletes the root row only for the exact `table_id/deletion_id` and related rows only for
the exact table/object ID plus D1, commits the action as `PURGED/SUCCEEDED`,
clears the active-name key, advances the revision, updates job counts, and appends
`PURGE_COMPLETED` atomically. A restored, re-dropped, or live same-name table cannot satisfy these
predicates.

If external cleanup succeeds but the metadata predicate mismatches, the worker deletes no unrelated
row. The action remains `PURGING/FAILED`, retains its reservation and context, and emits
`MANUAL_REPAIR_REQUIRED`. An operator may resolve only by deletion ID, source table ID, and audit
correlation—not by table name alone.

### Partial batch outcomes and retry

Item completion never waits for the whole batch. If D1 succeeds, D2 fails, and D3 is still running,
D1 is already `PURGED`; D2 remains `PURGING/FAILED`; and D3 may continue. No failed item rolls a
successful sibling back to `DELETED`.

Automatic retries remain under the same job ID and current or reclaimed lease until the job's
persisted `max_item_attempts` is exhausted. Each attempt CASes the item to `RUNNING`, increments
`cleanup_attempt_count`, and clears its retry timestamp. A retryable failure stores the sanitized
error and durable backoff time, then returns the item to `PURGING/PENDING`; an exhausted or
nonretryable failure becomes `PURGING/FAILED`. When every item is terminal for that execution, the
header becomes:

| Item outcome                         | Header state       |
| ------------------------------------ | ------------------ |
| Every item `PURGED/SUCCEEDED`        | `SUCCEEDED`        |
| Some succeeded, some exhausted       | `PARTIAL_FAILED`   |
| Every item exhausted without success | `FAILED`           |

A later operator redrive creates a new job with `retry_of_job_id`, then CAS-attaches only the exact
`PURGING/FAILED` actions to it, resets their per-job attempt count, and moves their cleanup status to
`PENDING` without changing lifecycle state. The earlier header and audit retain its final attempt
count as immutable evidence. This redrive rule is proposed for review; the POC may initially expose
status and require manual storage repair instead of shipping a mutation endpoint.

## Reference implementation: current R1 executor

The current R1 implementation is useful code context, not proof that this lifecycle already exists.
It persists one `iceberg_cleanup_job` row per table with
`PENDING/RUNNING/SUCCEEDED/FAILED`, attempt count, latest error, a heartbeat-based CAS token, stale
claim reclaim, a shared bounded delete pool, and root-metadata-last traversal.

The POC can reuse those worker patterns, but it must not inherit these correctness gaps:

- R1 loads the metadata root, removes the Iceberg registration, and only then inserts the cleanup
  row. A crash between unregister and insert has no durable action or job.
- R1 has no deletion ID, original Gravitino table ID, retention deadline, batch header, explicit
  owner/epoch, action revision, or append-only lifecycle correlation.
- Losing the heartbeat CAS prevents a stale worker's terminal database update, but does not
  currently interrupt that worker's physical deletion. The new executor must check its epoch between
  bounded delete batches; all targets must remain exact-generation safe even when an already-issued
  storage call finishes after lease loss.
- R1 may immediately reclaim a failed `PENDING` row rather than observing a durable retry schedule.
  Target retries need bounded attempts and visible timing/backoff semantics.
- Missing roots or manifests are treated as success/skipped, delete manifests and deleted entries
  are not fully covered, and the Iceberg bulk-delete helper can log and suppress individual failures.
  None of those outcomes alone is sufficient evidence for normative `PURGED`.

These are POC bridge items. This document does not retroactively change R1 behavior or its schema.

## Source-of-truth and audit boundary

| Record                         | Authoritative for                                                                                              |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------- |
| `entity_deletion`              | Generation identity, public lifecycle, revision, retention, active reservation, cleanup item state, job link |
| `table_meta`                   | Current Gravitino table identity and relational tombstone                                                     |
| `iceberg_deletion_context`     | Immutable external recovery/purge input and internal saga/reconciliation progress                            |
| `iceberg_purge_job`            | Batch execution ownership, lease fencing, attempts, and aggregate progress                                    |
| Append-only deletion audit     | Who/what/when history; never authorization for a transition                                                   |
| `entity_change_log`            | Cache invalidation history only; not lifecycle or compliance audit                                            |

Minimum audit events are `DELETE_PREPARED`, `DELETE_ACCEPTED`, `DELETE_RECONCILIATION_REQUIRED`,
`UNDROP_CLAIMED`, `UNDROP_RESTORED`, `UNDROP_COMPENSATED`, `PURGE_BATCH_CREATED`, `PURGE_STARTED`,
`PURGE_RECLAIMED`, `PURGE_ITEM_RETRIED`, `PURGE_COMPLETED`, `PURGE_FAILED`,
`MANUAL_REPAIR_REQUIRED`, and `RECEIPT_EXPIRED`.

Every event records deletion ID, source table ID, actor or worker, request ID, correlation ID, job
ID and lease epoch when applicable, timestamp, prior/new lifecycle and cleanup states, and a bounded
sanitized reason. It never records credentials, tokens, raw FileIO properties, table metadata, or
object-store authorization material.

## Terminal and audit retention

`RESTORED` and `PURGED` actions remain long enough for exact replay, explained `410`, operational
correlation, and the audit requirement. They no longer reserve the name. Receipt expiry is a
separate GC pass that first verifies no `table_meta` or job reference remains, deletes the Iceberg
context and action in dependency order, and appends `RECEIPT_EXPIRED` without deleting audit.

Sensitive FileIO material should be scrubbed or revokeable as soon as neither retry nor repair needs
it. A minimal non-secret context digest may outlive it in the audit receipt. Terminal job headers
remain at least as long as their referencing actions, then expire separately. Exact durations are a
product/compliance decision, not inherited automatically from soft-delete retention.

## ETags: concurrency and retry, explained

The strong action ETag is computed from the deletion ID, action revision, state, deadline, cleanup
status, job association, operation/reconciliation status, and canonical non-secret representation:

```text
iceberg-deletion-{deletionId}-r{revision}-{sha256(canonical representation)}
```

It is an HTTP representation validator, not the action identifier, transaction lock, job lease, or
Iceberg table ETag. The management GET returns `Cache-Control: private, no-store`; ETag eligibility
also ends at `retention_expires_at`, so server-side expiry produces `410` before generic `412`.

| Situation                                 | Resolution                                                                                                   |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Two UNDROP requests use D1 and one ETag   | One claim executes; the second bounded-waits or receives retryable `503`, then matches the receipt.         |
| UNDROP races a GC batch claim             | Both CAS D1's revision/context boundary; one wins. A GC winner makes UNDROP `410`.                           |
| The UNDROP response is lost               | Replay the same D1 and accepted ETag; do not register twice.                                                 |
| The table commits after restore           | Replay verifies immutable source table ID and returns current metadata; v17 advancing to v18 is not stale.   |
| A same-name replacement exists            | D1 cannot mutate it; exact ID predicates return D1's terminal result or an identity conflict.                |
| A stale purge worker finishes             | Its job epoch predicate fails; it cannot hard-delete metadata or commit `PURGED`.                            |

## Compact error model

| HTTP  | Condition                                                                                           | Client behavior                                                        |
| ----: | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `400` | Invalid deletion ID, path, header, or request shape                                                 | Fix the request                                                        |
| `403` | Caller is outside the management authorization boundary                                             | Do not retry unchanged                                                 |
| `404` | No active deletion or retained exact replay receipt exists                                           | Rediscover explicitly                                                  |
| `409` | Live-name/identity/parent conflict, or an Iceberg saga requires reconciliation                        | Resolve the typed conflict; never switch generations automatically     |
| `410` | Deadline reached, GC owns the action, or a retained `PURGED` receipt exists                           | Terminal for UNDROP                                                    |
| `412` | Same otherwise-eligible action changed since the supplied ETag                                       | Reread D1; retry only if it remains the same generation                 |
| `428` | `If-Match` is absent                                                                                 | Read the action and retry conditionally                                |
| `5xx` | Relational or Iceberg outcome may be unknown                                                         | Replay the exact deletion ID/request with bounded backoff               |

## Open items and review decisions

1. **Exact addressing and reservation.** Keep `deletionId` mandatory for UNDROP; approve the API
   draft's exact route shape and the nullable canonical SHA-256 `active_name_key`, or choose a body
   identifier and separate collision-free reservation table. A name or ETag alone is never an
   identifier.
2. **Purge exhaustion and receipts.** Approve `PARTIAL_FAILED` plus linked redrive jobs, the
   operator/manual-repair surface, and concrete action/job/audit retention periods. The initial POC
   must at minimum expose failure and must never release a partially purged name silently.
3. **Versioned-object hard delete.** Define whether provider delete markers satisfy the PRD. If
   permanent version removal is required, choose the bounded durable checkpoint representation for
   exact object version IDs before claiming normative `PURGED`.

## POC implementation sequence

1. Land opaque `entity_deletion`, nullable `table_meta.deletion_id`, Iceberg context, non-cascading
   lifetime, active-name reservation, and append-only lifecycle audit.
2. Add the DELETE saga marker/reconciler and conditional management discovery/UNDROP with exact
   deletion identity, action ETags, fenced operation claims, and replay receipts.
3. Add bounded batch claim, `iceberg_purge_job`, lease epochs, heartbeat/reclaim, action-item
   progress, generation-predicated finalization, and terminal receipt cleanup.
4. Reuse only proven R1 mechanics—metadata-root reconstruction, root-last ordering, polling, and
   heartbeat CAS—then add the missing generation, batch, error-propagation, and audit guarantees.
5. Test crash points and races end to end: DELETE ambiguity, UNDROP/GC, lease expiry during storage
   calls, mixed batch results, exact replay, same-name reuse, re-drop, metadata mismatch, and receipt
   expiry.
