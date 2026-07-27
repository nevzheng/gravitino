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

# [API] Asynchronous Hard Delete and Soft Delete for Iceberg REST Catalog — Metadata Undelete Semantics

| Field        | Value                                                                                                          |
| ------------ | -------------------------------------------------------------------------------------------------------------- |
| Status       | Table API/store POC design complete; implementation proposed                                                   |
| Author       | Nevin Zheng                                                                                                    |
| Created      | 2026-07-26                                                                                                     |
| Last updated | 2026-07-26                                                                                                     |
| Module       | `iceberg/iceberg-rest-server`, `core`, relational store                                                        |
| Related      | [Iceberg REST deletion metadata and purge-job technical draft](iceberg-rest-table-deletion-metadata-design.md) |
| Reviewers    | Jerry Shao (primary); additional reviewers TBD                                                                 |

## Abstract

This proposes the Iceberg REST table-deletion contract: the unchanged Iceberg `DELETE`, one
management read for the retained deletion, conditional UNDROP, durable retention, and restart-safe
asynchronous hard purge. One opaque deletion ID identifies one drop generation; the saved Iceberg
metadata location and original Gravitino table ID make recovery exact.

The reviewable surface is the API shape, ETag concurrency, lifecycle states, metadata model, and GC
claim/finalization rules. This is not the general **Metadata Undelete Semantics** proposal and UNDROP
is not its metadata-only `PATCH`: this handler re-registers Iceberg state and restores the original
Gravitino identity. Only Iceberg REST tables and hard purge are in scope.

## Background

The Iceberg REST delete path either removes only the catalog registration or deletes physical data.
The asynchronous hard-delete PRD moves physical cleanup off the request thread. Optional soft
delete adds a retention window so a mistaken drop can be undone before cleanup begins.

The current R1 executor already persists per-table cleanup work, leases it across replicas, retries
failures, and deletes the root Iceberg metadata file last. It does not provide a recoverable deletion
generation, restore the original Gravitino metadata identity, or arbitrate UNDROP against cleanup.

## Gap

The server needs one durable record that answers: which table generation was deleted, when does its
retention end, may it still be restored, and which cleanup job owns it? Names alone are unsafe
because they are reusable. An external Iceberg registration and Gravitino relational metadata also
cannot be mutated in one database transaction, so ambiguous partial outcomes must remain visible
and repairable.

## Scope and goals

The POC supports `entity_type=TABLE` and `purge_job_type=ICEBERG_REST_PURGE`. It preserves the
original `table_id`, reserves the deleted name, makes UNDROP and GC race through one conditional
action, and lets any replica resume hard purge. It deliberately excludes generic metadata recovery,
provider trash/recycle bins, alternate purge policies, nested-object recovery, and broad
authorization semantics. Initial management operations may remain admin-only.

## General API

| Operation     | Route                                                                                              | Result                                                                                                  |
| ------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Delete        | `DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}?purgeRequested={boolean}`               | Existing bodyless `204`; logical deletion is accepted, but asynchronous file cleanup may remain.        |
| Read deletion | `GET /management/v1/{prefix}/namespaces/{namespace}/tables/{table}/deletion`                       | Current recoverable action plus a strong action ETag; secret handler context is omitted.                |
| UNDROP        | `POST /management/v1/{prefix}/namespaces/{namespace}/tables/{table}/deletions/{deletionId}/undrop` | Requires `If-Match`; returns the normal live Iceberg table response after both identities are restored. |

The name path discovers the active retained action. UNDROP then addresses the returned `deletionId`
explicitly; the routed name is validated against its saved snapshot but is never the mutation key.
The ETag pins that action's representation. A later same-name drop has a different ID and can never
be substituted automatically. The ETag is a deletion-action validator, not an identifier, Iceberg
table ETag, storage lease, or credential.

## Critical user journey: UNDROP an Iceberg table

Assume `orders` is dropped from `demo.sales` with soft delete enabled:

```http
DELETE /v1/demo/namespaces/sales/tables/orders?purgeRequested=true

HTTP/1.1 204 No Content
```

Read the retained generation and its current precondition:

```http
GET /management/v1/demo/namespaces/sales/tables/orders/deletion

HTTP/1.1 200 OK
ETag: "iceberg-deletion-77192-r4-0123456789abcdef"
Cache-Control: private, no-store
Content-Type: application/json

{
  "deletionId": "77192",
  "entityId": "984273",
  "state": "DELETED",
  "deletedAt": 1784800000000,
  "retentionExpiresAt": 1784886400000,
  "cleanupStatus": "PENDING",
  "deletedBy": "alice",
  "recoverable": true
}
```

Apply the conditional UNDROP to that same routed name:

```http
POST /management/v1/demo/namespaces/sales/tables/orders/deletions/77192/undrop
If-Match: "iceberg-deletion-77192-r4-0123456789abcdef"

HTTP/1.1 200 OK
Content-Type: application/json

{
  "metadata-location": "s3://warehouse/sales/orders/metadata/v17.metadata.json",
  "metadata": { "...": "normal LoadTableResponse fields" }
}
```

The server has re-registered the saved Iceberg metadata location, restored the original Gravitino
`table_id` and deletion-scoped metadata, and recorded `RESTORED`. If the `200` is lost, replaying the
same POST with the same ETag returns the same live table without registering or restoring twice.

## ETag and conflicts

The strong ETag is computed from the deletion ID, semantic revision, and canonical non-secret action
representation:

```text
iceberg-deletion-{deletionId}-r{revision}-{digest}
```

UNDROP first claims exactly `deletion_id + revision + state=DELETED` while
`retention_expires_at > server_now` and no purge job owns the action. A renewable internal claim
with a monotonically increasing epoch excludes GC while the external registration/reconciliation
call runs; it is not a public `RESTORING` state. Once an external call may have started, claim expiry
requires reconciliation before GC may proceed. GC claims the same action boundary, so exactly one
side wins.

Typed outcomes take precedence over generic staleness. Expiry and `PURGING`/`PURGED` are `410`; an
identity or live-name conflict is `409`; only an otherwise-eligible changed representation is `412`.
The discovery response is `private, no-store`; a previously returned ETag is usable only before the
persisted retention deadline.

The internal claim persists the accepted input ETag before calling Iceberg. A concurrent request
with that exact deletion ID and ETag is recognized as the same operation: the server may wait within
its request budget, otherwise it returns `503` with `Retry-After` for exact replay. It never starts a
second registration or returns `412` merely because the first accepted request advanced revision.

## Errors

|                    HTTP | Meaning                                                                                                     |
| ----------------------: | ----------------------------------------------------------------------------------------------------------- |
|                   `400` | Invalid path, header, or deletion request shape.                                                            |
|                   `403` | Caller is outside the management API's authorization boundary.                                              |
|                   `404` | No retained action/replay receipt exists, or this was a nonrecoverable R1 deletion.                         |
|                   `409` | Name, table ID, Iceberg UUID/location, parent, or reconciliation state conflicts with the saved generation. |
|                   `410` | Retention expired, the action is `PURGING`, or a retained `PURGED` receipt exists.                          |
|                   `412` | The same action changed since the supplied ETag; reread it.                                                 |
|                   `428` | `If-Match` is missing.                                                                                      |
| `500`/`502`/`503`/`504` | Outcome may be unknown; replay only the exact deletion ID and accepted ETag with bounded backoff.           |

Recovery discovery is intentionally narrower than operator cleanup status:

| Action / condition                | Name-based deletion GET | Exact UNDROP                                                              |
| --------------------------------- | ----------------------- | ------------------------------------------------------------------------- |
| Stable `DELETED`, before deadline | `200` + ETag            | Eligible when the deletion ID and ETag match                              |
| `DELETED`, deadline reached       | `410`                   | `410`                                                                     |
| Internal reconciliation required  | `409`                   | `409`; no restore or purge may guess the external outcome                 |
| `PURGING` or retained `PURGED`    | `410`                   | `410`                                                                     |
| `RESTORED` receipt                | `404` for discovery     | Exact accepted deletion ID + ETag replays `200` while the receipt remains |
| R1 nonrecoverable action          | `404`                   | `404`; operator cleanup visibility is a separate authenticated surface    |
| Receipt expired                   | `404`                   | `404`                                                                     |

## Cases and semantics

### Delete

| `soft-delete.enabled` | `purgeRequested` | Result                                                                                 |
| --------------------- | ---------------- | -------------------------------------------------------------------------------------- |
| `false` (default)     | `true`           | R1 asynchronous hard delete; `retention_expires_at=NULL`, not recoverable.             |
| `false` (default)     | `false`          | Existing metadata-only drop; files remain and no retained recovery action is created.  |
| `true`                | either           | R2 retained action; the wire flag is audit-only and hard purge begins after retention. |
| `true`, retention `0` | either           | Immediately GC-eligible; no usable recovery window.                                    |

The `retention_expires_at=NULL` action is the proposed bridge representation for the
nonrecoverable path; current R1 code persists only its per-table `iceberg_cleanup_job` row.

The PRD properties are `gravitino.iceberg-rest.soft-delete.enabled` (default `false`) and
`gravitino.iceberg-rest.soft-delete.retention-ms` (default `86400000`, 24 hours; valid range 0–90
days). `retention_expires_at` is computed and persisted at drop time, so later configuration changes
affect only later drops.

Before returning `204`, the server must durably capture the action and Iceberg context, stamp the
metadata tombstone and every affected table-related row with the exact deletion ID, and reserve the
name. Removing the Iceberg registration and committing the relational tombstone form a small saga;
an internal operation marker and reconciler close the crash gap rather than silently leaving a
dropped table with no action. While that marker is unresolved, the table is hidden, the name remains
reserved, and recovery/cleanup returns a typed retryable conflict; `204` means the stable `DELETED`
invariant was verified.

### UNDROP

After its conditional claim, the handler re-registers the saved Iceberg metadata location through
an internal path that cannot auto-import a new ID, then restores the original Gravitino table
generation, verifies both sides, and commits `RESTORED`. Ordinary register/import is not recovery
because it may mint a new ID and detach ID-keyed owner, tag, policy, statistic, or authorization
relationships.

The restore transaction selects the root by `table_id + deletion_id` and related metadata by the
same immutable table/object identity plus this deletion ID. It never restores every historically
deleted relation for the table.

If registration succeeds but metadata restoration fails, the server compensates by unregistering
only that registration, never by purging its files. Proven compensation returns the action to
`DELETED` with a new revision. An outcome that cannot be proven or compensated remains reserved and
stays `DELETED` but `recoverable=false` under its durable reconciliation marker until
exact-generation manual repair; it is never reported as a successful UNDROP.

An exact replay of a completed UNDROP verifies the retained action receipt and original live
`table_id`, then returns the table's current `LoadTableResponse`. It does not require the current
metadata location to remain equal to the saved deletion snapshot because normal commits may have
advanced the table after restoration.

### Asynchronous hard purge

`PURGE_PENDING` is derived, not persisted:

```text
state=DELETED AND retention_expires_at <= server_now
```

That is the R2 retained-action predicate. A migrated/bridged R1 action with
`retention_expires_at IS NULL` is separately eligible immediately.

GC selects a bounded deterministic candidate set. One claim transaction creates a `PENDING`
`iceberg_purge_job` header, CASes each still-eligible action directly to
`PURGING/cleanup_status=PENDING`, stamps the same `purge_job_id`, and creates no external side
effect. The action rows are the durable job items; the header owns job type, lease owner/epoch,
heartbeat, attempts, and aggregate progress. A free worker later leases the header and moves one
item to `RUNNING` before external I/O.

Workers reconstruct each old Iceberg file graph from its saved metadata location, delete only files
reachable from that generation—not a name or warehouse prefix—and delete the root metadata file
last. A stale heartbeat is reclaimable; every progress and terminal update is fenced by the current
lease epoch. The worker checks lease ownership between bounded delete batches; an already-issued
object-store request may finish after lease loss, which remains safe only because its targets came
from the immutable old-generation context. An exhausted or nonretryable failure remains
`PURGING + cleanup_status=FAILED`, retains the name, and never rolls the batch or successful siblings
back to `DELETED`.

An item commits `PURGED` immediately after its own confirmed success. A retryable failure remains
`PURGING`, returns to `cleanup_status=PENDING` with durable error/backoff data, and is retried under
the same job; exhaustion becomes `PURGING/FAILED`. When all items stop, the batch header is
`SUCCEEDED`, `PARTIAL_FAILED`, or `FAILED`; an exhausted failed item may be attached by CAS to a
later linked retry job, but it never becomes `DELETED` again.

Only confirmed external success may finalize one action. In one relational transaction the worker
checks the current lease, `state=PURGING`, matching `purge_job_id`, original `table_id`, and expected
`table_meta.deletion_id`; related metadata must also match the same deletion ID.
It then hard-deletes only that metadata generation and commits the action as `PURGED` with its audit
event. A restored, re-dropped, live same-name, or mismatched generation is never deleted. A
finalization mismatch remains visible for manual repair.

### Source-of-truth boundary

`entity_deletion` is authoritative for lifecycle, generation, retention, and purge ownership.
`table_meta.deleted_at/deletion_id` is authoritative for the original Gravitino metadata tombstone.
`iceberg_deletion_context` holds immutable Iceberg recovery/cleanup input. The append-only lifecycle
audit explains history but never authorizes a transition. `entity_change_log` remains
cache-invalidation history, not lifecycle or audit state.

## Metadata model

```text
table_meta                                  entity_deletion
┌──────────────────────┐   nullable FK      ┌──────────────────────────────┐
│ table_id (PK)        │                    │ deletion_id (PK)             │
│ deletion_id ─────────┼───────────────────>│ entity_type, entity_id       │
│ deleted_at           │                    │ state, revision, retention   │
└──────────────────────┘                    │ cleanup fields, purge_job_id │
                                            └───────────┬───────────┬──────┘
                                                        │ 1:1       │ N:1
                                                        v           v
                                         iceberg_deletion_context  iceberg_purge_job
                                         metadata/UUID/FileIO      lease/progress/retry
```

| Record / key                           | Meaning                                                                                                                                        |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `entity_deletion.deletion_id`          | Opaque global primary key for one drop generation; it encodes no entity or handler type.                                                       |
| `entity_type`, `entity_id`             | `TABLE` and the immutable original `table_meta.table_id`; retained after metadata hard deletion.                                               |
| name lookup                            | Indexed catalog ID + namespace snapshot + table name + deletion time; discovery only, never mutation identity.                                 |
| active-name key                        | Unique canonical name reservation held by `DELETED` and `PURGING`; terminal receipts no longer reserve it.                                     |
| `state`, `revision`                    | `DELETED`, `RESTORED`, `PURGING`, `PURGED` and the optimistic semantic revision.                                                               |
| PRD fields                             | `deleted_at`, `retention_expires_at`, `cleanup_status`, `cleanup_attempt_count`, `cleanup_last_error`.                                         |
| audit fields                           | Actor, request ID, correlation ID, completion timestamps, and accepted replay ETag.                                                            |
| `purge_job_type`, `purge_job_id`       | `ICEBERG_REST_PURGE` and the durable bounded-batch job association.                                                                            |
| `iceberg_deletion_context.deletion_id` | One-to-one key to the action; contains metadata location, table UUID/identifier, FileIO implementation, and protected configuration/reference. |

`table_meta.deletion_id` points to `entity_deletion.deletion_id`; it is an indexed logical FK and
must not cascade action deletion. The same deletion ID stamps the table version, columns, owner,
securable-object, tag, policy, and statistic rows changed by this drop, so restore and hard-delete do
not select older tombstones for the same table. All of those logical references point from metadata
to the action and use non-cascading lifetime. `entity_deletion.entity_id` is an origin/audit value,
not a back-FK, because `RESTORED` and `PURGED` receipts must outlive physical deletion of the
metadata rows.
Legacy relational GC predicates exclude rows with a deletion ID; the Iceberg lifecycle alone
restores or removes them.

The Iceberg context is internal. It contains no mutable job progress and is never returned by the
deletion GET. FileIO configuration may include credentials, so it must be encrypted or replaced by
a protected reference and never appear in API output, logs, or audit events.

## Lifecycle and expiry

```text
                    successful UNDROP
       DELETED  ─────────────────────────> RESTORED
          │
          │ retention_expires_at <= server_now; GC claim wins
          v
       PURGING  ───── successful item finalization ─────> PURGED
          │
          └──── cleanup_status=FAILED; retry/manual repair stays PURGING
```

`DELETED` means logically deleted and retained; `RESTORED` and `PURGED` are terminal receipts.
There is no public `RESTORING` or `PURGE_PENDING` state. The generic relational metadata retention
must not remove the table tombstone before its persisted Iceberg retention deadline; startup and
configuration validation enforce that floor.

The operational mapping is `DELETED/PENDING`; `PURGING/PENDING`, `PURGING/RUNNING`, or
`PURGING/FAILED`; and `PURGED/SUCCEEDED`. `PURGING` is the irreversible lifecycle boundary even when
the individual item is waiting in its batch.

Terminal action and job receipts remain long enough to return an explained `410`, support exact
replay, and satisfy audit requirements. Receipt/context cleanup runs separately from physical purge,
never cascades into append-only audit, and only then lets an old lookup become `404`.

## Reference implementation: current R1 executor

The current `iceberg_cleanup_job` is one row per table with
`PENDING/RUNNING/SUCCEEDED/FAILED`, attempts, latest error, heartbeat CAS, stale-lease reclaim, and
root-metadata-last retry behavior. Those worker mechanics are useful POC infrastructure.

They are not yet this lifecycle: R1 has no deletion generation, original `table_id`, retention,
UNDROP, batch header, action revision, or append-only correlation. Its request path removes the
Iceberg registration before inserting the cleanup row, and its helper's best-effort file deletion is
not sufficient evidence for normative `PURGED`. A worker that loses R1 heartbeat ownership can also
continue physical deletion even though its terminal database update is fenced. The implementation
must bridge those gaps rather than treating the existing row ID, heartbeat token, or terminal state
as the new product contract.

## Audit

Minimum lifecycle events are delete accepted, UNDROP claimed/restored/compensated, purge batch
created, purge started/retried/completed/failed, manual repair required, and receipt expired. Each
records deletion ID, original table ID, actor or worker, request/correlation ID, job ID, timestamp,
prior/new state, and a bounded sanitized reason. Credentials, tokens, raw FileIO properties, and
metadata payloads are excluded.

## Appendix A: ETags, concurrency, and replay

| Situation                                 | Resolution                                                                                    |
| ----------------------------------------- | --------------------------------------------------------------------------------------------- |
| Two UNDROP requests use one ETag          | One transition runs; the second bounded-waits or gets retryable `503`, then replays to `200`. |
| UNDROP races GC                           | Both CAS the same action revision; one wins. A GC winner makes UNDROP `410`.                  |
| The `200` response is lost                | Replay the identical POST with the same deletion ID and `If-Match`; never switch generations. |
| Action-relevant state changed before POST | `412`; reread and proceed only if it is still the same deletion ID.                           |
| Worker crashes                            | The durable job heartbeat expires; another replica reclaims with a new fencing epoch.         |
| Stale worker finishes                     | Its lease/action CAS fails, so it cannot hard-delete metadata or commit `PURGED`.             |

## Appendix B: Retry contract

| Outcome                                          | Client behavior                                                                            |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| Connection loss, timeout, `408`, `502`, or `504` | Outcome may be unknown; replay the exact POST first.                                       |
| `429` or `503`                                   | Honor `Retry-After`, then replay exactly within a bounded attempt/time budget.             |
| `412`                                            | Do not replay stale input; reread and use a fresh ETag only for the unchanged deletion ID. |
| `428`                                            | Read the deletion first; never repeat an unconditional request.                            |
| `400`, `403`, `404`, `409`, or `410`             | Do not retry unchanged automatically.                                                      |

## Appendix C: Edge cases

| Situation                                            | Outcome                                                                         |
| ---------------------------------------------------- | ------------------------------------------------------------------------------- |
| A different live object occupies the name            | `409`; never overwrite it.                                                      |
| A newer same-name deletion exists                    | Old ETag cannot select it; rediscover explicitly.                               |
| Retention expires or GC wins                         | `410` while the receipt remains, eventually `404`.                              |
| Cleanup partially fails                              | Remains `PURGING/FAILED`; no UNDROP and no name release.                        |
| Object was restored and deleted again                | The old deletion ID cannot mutate or purge the new generation.                  |
| Metadata predicate mismatches after external cleanup | No unrelated row is hard-deleted; manual repair is required.                    |
| Auto-import sees the tombstone                       | It must refuse or use this recovery path; it must not mint a new base identity. |

## Appendix D: POC sequence and review decisions

1. Add the generic deletion action, `table_meta.deletion_id`, and Iceberg context with the PRD field
   names and non-cascading lifetime.
2. Add conditional management GET/POST and exact replay receipts for table-only UNDROP.
3. Add bounded GC claim, durable purge-job header, action-item progress, and generation-predicated
   metadata finalization while reusing R1 worker mechanics.
4. Validate the metadata-retention floor and cover drop, UNDROP, expiry, restart, name reservation,
   stale ETag, partial failure, and same-name re-drop end to end.

Review is needed on the canonical active-name reservation representation, terminal receipt/audit
retention periods, and the migration path from R1 per-table jobs to the bounded batch header.
