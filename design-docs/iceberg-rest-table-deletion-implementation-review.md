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

# Iceberg REST Table Deletion Implementation Review

| Field | Value |
| --- | --- |
| Status | Review snapshot; correctness follow-ups remain |
| Documentation | `codex/iceberg-rest-deletion-lifecycle-design` |
| Test umbrella | `codex/iceberg-rest-delete-tests-integration` in `gravitino-test` |
| Scope | Iceberg REST tables, retained UNDROP, and asynchronous hard purge |

## Stack

Review the cumulative branches in order:

| Order | Branch | Focus |
| ---: | --- | --- |
| 1 | `codex/iceberg-rest-delete-stack-10-storage` | Generic deletion records, Iceberg context, table-generation pointers, and exact metadata transactions |
| 2 | `codex/iceberg-rest-delete-stack-20-lifecycle-api` | Transactional DELETE, discovery, strong ETags, and UNDROP |
| 3 | `codex/iceberg-rest-delete-stack-30-purge-jobs` | Durable bounded batch jobs, leases, target ledger, and per-action finalization |
| 4 | `codex/iceberg-rest-delete-stack-40-correctness` | Exact-generation authorization, namespace identity, and legacy-GC protection |
| 5 | `codex/iceberg-rest-delete-stack-50-runtime-observability` | Purge scheduling, metrics, retry, and lifecycle wiring |
| 6 | `codex/iceberg-rest-delete-stack-60-prd-tests` | Server test hooks and final repeated-DELETE behavior |
| 7 | `codex/iceberg-rest-delete-stack-70-review-guide` | This review guide only |

The historical non-`stack-*` branches are superseded development lanes and are not part of the
review sequence. `codex/iceberg-rest-delete-integration` points to the complete code snapshot at
stack 60.

## Implemented behavior

- DELETE uses one relational transaction to create an opaque deletion generation, tombstone the
  table graph, capture Iceberg cleanup context, reserve the name, and append audit. It does not
  unregister Iceberg or remove files on the request thread.
- UNDROP uses the explicit deletion ID plus a strong ETag and one relational transaction to
  reactivate only that generation's rows. It does not re-register Iceberg.
- Public action states are `DELETED`, `RESTORED`, `PURGING`, and `PURGED`. Expiry eligibility is
  derived rather than represented as `PURGE_PENDING`.
- GC atomically creates one bounded batch job and claims winning actions directly into `PURGING`.
- Workers persist a target plan, checkpoint each verified deletion, heartbeat a fenced lease, and
  finalize an action only after every target succeeds.
- Relational hard deletion is predicated on the original immutable `table_id` plus `deletion_id`,
  so an old purge cannot remove a later same-name generation.

## Accepted follow-up: optimistic point transactions

The snapshot still uses explicit schema/table/action pre-locks. The accepted target is optimistic
concurrency control:

- capture and validate the Iceberg cleanup snapshot before the short write transaction;
- condition DELETE on immutable table ID, expected entity version, and live/deletion predicates;
- condition UNDROP on deletion ID, expected action revision, `DELETED`, unexpired retention, and no
  purge job;
- require exact affected-row counts and roll the transaction back on any mismatch; and
- use one atomic name claim shared by create, register, rename, DELETE, UNDROP, and purge.

This changes transaction coordination, not the lifecycle or deletion-generation model.

## Required correctness follow-ups

1. Replace pessimistic point pre-locks with OCC and close the create/register/rename name-claim race.
2. Map an ordinarily missing or already-purged DELETE to the normal Iceberg `404`, not `500`.
3. Map known UNDROP parent/name/generation conflicts to `409`, not `500`.
4. Authorize exact deletion generations without exposing a `404`/`409`/`403` existence oracle.
5. Preserve a terminal authorization rule after purge removes table-owned owner rows.
6. Make Iceberg registration removal conditional at the backend boundary or explicitly constrain
   out-of-band mutation.

## Product and operational decisions

- Decide whether hard purge honors or overrides Iceberg `gc.enabled=false`.
- Define hard-delete guarantees for versioned object stores where an ordinary DELETE creates a
  delete marker rather than permanently removing a version.
- Define how cleanup survives catalog deletion or incompatible FileIO configuration changes.
- Define retention and expiry for terminal actions, contexts, audits, and job headers.
- Confirm whether the first release is auxiliary-only or requires standalone parity.

## Validation evidence

Focused lifecycle, storage, purge, authorization, configuration, and formatting checks passed on
the snapshot. Store tests cover concurrent claim, lease reclaim, stale-worker fencing, partial
batch failure, target replay, and exact-generation finalization. The following remain to be proven
in a live environment:

- real process restart during cleanup;
- multiple Gravitino servers claiming and reclaiming work;
- the approximately 500,000-object workload; and
- end-to-end management API routing, authorization, headers, replay, and error mapping.

