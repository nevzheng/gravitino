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

# Design: Soft-Delete Retention and Metadata Recovery

| Field   | Value                                          |
| ------- | ---------------------------------------------- |
| Status  | Planned root-resource POCs implemented: table, function, model, fileset, view, topic, policy, user, group, role, job template, tag, schema, catalog, metalake |
| Created | 2026-07-23                                     |
| Module  | `api`, `common`, `core`, `server`, `clients/client-java` |

---

## 1. Background

Gravitino's relational entity store already retains deleted metadata. A
normal drop sets `deleted_at` to an epoch-millisecond timestamp, normal reads
filter on `deleted_at = 0`, and `RelationalGarbageCollector` permanently
deletes expired rows after `gravitino.entity.store.deleteAfterTimeMs` (seven
days by default).

Before this POC, the retained rows were not useful to users. The REST API
could not discover a tombstone, retrieve a particular deletion generation, or
restore it. From the API user's perspective, every drop was immediate and
irreversible.

This design defines a reusable user-facing soft-delete contract, first proven
for tables and now implemented through the planned leaf resources, standalone
policy, the identity trio, job templates, tags, and the schema, catalog, and
metalake containers.
Tables exercise the base row, version, columns, and relationships that one
metadata transaction must restore together. Users, groups, and roles exercise
count-checked aggregates whose membership and ownership rows can be shared by
two independently recoverable roots. The POCs are deliberately metadata-only
and reuse the same exact GET, strong ETag, and conditional PATCH behavior
through existing collection and item resources.

## 2. Goals

1. Preserve every current collection response when no new query parameters
   are supplied.
2. Discover deleted entities through filters on their existing collections;
   do not add a parallel recycle-bin resource hierarchy.
3. Address a deletion by immutable entity ID and immutable deletion ID, not
   by name alone.
4. Restore through an optimistic, conditional `PATCH` operation.
5. Make concurrent modification a normal, retryable precondition failure.
6. Fail closed when Gravitino cannot prove that the same logical entity and its
   required metadata rows will be restored.
7. Reuse one discovery, conditional-update, error, and retry contract across
   supported metadata-object types.

## 3. Non-goals for the first proof of concept

1. Validating, loading, or recreating downstream source objects. PATCH restores
   only Gravitino's relational metadata and does not access the downstream
   catalog.
2. Restoring an older same-name tombstone while a newer tombstone exists.
3. Restore-with-rename, merge, or force semantics.
4. Adding a user-invoked permanent-purge API or changing the existing
   `DROP TABLE PURGE` behavior.
5. Per-metalake or per-catalog retention configuration. The existing global
   retention configuration remains the source of `expiresAt` in the POC.
6. A cross-entity recycle-bin endpoint. Each entity type extends its existing
   collection and item resources instead.
7. Pagination for deleted-object discovery.

## 4. Terminology and invariants

- **Entity ID** (`id`) identifies one logical Gravitino entity across restore.
  It must not be reused for a different logical entity.
- **Deletion ID** (`deletionId`) identifies one durable deletion record and
  one drop generation. Restoring and dropping the same entity again produces
  a new deletion ID.
- **Affected metadata rows** are the owning entity row and related rows stamped
  with the same opaque deletion ID by one deletion operation. For a table,
  these include its version, columns, owner, tags, policies, statistics, and
  securable-object relationships. For a standalone policy, they are
  `policy_meta` and the policy versions that were live at deletion time;
  associations, owner, and grants remain outside that generation. For a user,
  they are its root, role memberships, and ownership rows where the user is the
  principal; a group is symmetric. For a role, they are its root, user/group
  memberships, securable grants, and ownership rows for the role itself. For a
  standalone job template, they are its root and the terminal job runs that
  were live when the template was deleted; ownership, grants, and job metrics
  are excluded. For a standalone tag, they are its root and the live
  tag-to-metadata-object relation rows captured by that tag deletion;
  ownership and grants are excluded.
- **Latest tombstone** is the current recoverable deletion with the greatest
  deletion timestamp for one entity type and name under the same immutable
  immediate-parent ID, with a deterministic deletion-ID tie-break. For a
  hierarchical schema, the immediate parent is the selected parent schema.
- **Legacy tombstone** is a row deleted before deletion records and per-row
  deletion IDs exist. It is discoverable but not restorable.

Entity and deletion IDs are serialized as JSON strings. Entity IDs remain
decimal 64-bit values; deletion IDs are opaque generation identifiers.

An undelete succeeds only when Gravitino can prove all of the following in
the restore attempt:

1. The requested tombstone still exists and has not expired.
2. Its entity ID, deletion ID, version, name, and immutable parent IDs match
   the state observed by the caller.
3. It is the latest tombstone for that entity type and name under the same
   immutable immediate parent.
4. No different live entity occupies the name or entity ID; for users and
   groups, no other live principal occupies the external ID; for a job-template
   generation, no other live run occupies a captured `job_execution_id`.
5. Every required captured row still carries the exact deletion ID observed by
   the caller.
6. Irreversible retention cleanup has not started.

### 4.1 Delete semantics

Recovery does not replace or reinterpret the existing deletion APIs:

1. Existing `DELETE`, `force`, `cascade`, and table `purge` request surfaces
   remain unchanged. A committed relational metadata deletion creates one
   deletion record and stamps exactly the live metadata rows changed by that
   transaction with its deletion ID and timestamp.
2. When no live Gravitino metadata row is changed, no deletion record is
   created. For a proxied catalog, `DropResponse.dropped` reflects the
   connector result and is not itself evidence that a metadata receipt was or
   was not created.
3. Existing connector deletion happens as it does today and may remove the
   downstream object or data irreversibly. A deletion becomes recoverable only
   when its relational metadata transaction commits. Connector/store atomicity
   is not changed by this design.
4. Table `purge=true` keeps each connector's existing behavior, may
   irreversibly remove downstream data, and still fails before metadata
   deletion when unsupported. It does not hard-delete Gravitino's relational
   tombstone, so any committed Gravitino metadata deletion remains eligible
   for metadata-only recovery. PATCH never restores purged downstream data.
5. A deleted name is immediately reusable. Every later drop creates a new
   deletion generation, and only the latest deletion for a name under the same
   immutable parent IDs is eligible for restore.
6. Non-empty schema deletion still requires `cascade`; non-empty catalog or
   metalake deletion still requires `force`. Such a request creates one
   independently recoverable root generation and stamps exactly the descendant
   rows changed by that deletion with the same deletion ID.
7. Restoring a container restores that exact recorded tree atomically.
   Descendants are not independently listed or restored as child tombstones,
   and descendants deleted before the cascade keep their older deletion IDs
   and remain deleted.
8. Job-template deletion retains its existing active-job guard. Only terminal
   runs are eligible for the template's deletion generation. A run tombstoned
   earlier by autonomous finished-job cleanup has no template deletion token;
   the later template delete does not adopt or retag it.
9. Standalone tag deletion changes captured live tag relations before the tag
   root and records their exact affected-row count. A relation already
   tombstoned by an independent disassociation or object cascade is not adopted
   or retagged by the later tag deletion.

## 5. Reusable REST API contract

For the compact end-to-end client interaction, see
[Critical user journey: undelete a table](undelete-api-proposal.md#critical-user-journey-undelete-a-table).

Every supported entity type follows the same shape:

```http
GET <existing-collection>?include=deleted[&name=...][&id=...]

GET <existing-item>?include=deleted&id=...

PATCH <existing-item>?include=deleted&id=...
If-Match: <deletion-generation-token>
Content-Type: application/merge-patch+json

{"deleted": false}
```

The common recovery manager owns tombstone fields, precondition handling,
machine-readable errors, retry classification, and the state transition. Each
REST resource maps its existing path and query shape to that manager, while an
entity-type adapter owns namespace validation, affected-row selection, and the
atomic inverse metadata transaction.

Every supported metadata object follows this same recovery protocol. Response
fields, parameter meanings, ETag behavior, latest-generation rules,
metadata-only boundaries, and error reasons are uniform. Only immutable parent
scope and the object-specific inverse metadata transaction vary.

When a future supported collection already has `details=true`, combining it
with `include=deleted` should return `400` rather than inventing a second
tombstone representation. The table endpoint itself has no `details`
parameter. For resources that already support PATCH, normal mutation continues
to consume the existing JSON media type and recovery consumes
`application/merge-patch+json`, making method selection unambiguous.
Policy is the concrete example: its existing `application/json` PATCH retains
enable/disable semantics, while only merge-patch can request undelete.
User and group `details=true` lists remain unchanged and reject combination
with `include=deleted`; role's existing live list remains a name list. All
three identity resources add only the recovery merge PATCH form.
Job-template `details=true` remains a live-list option and is rejected with
`include=deleted`. Recovery extends only `/jobs/templates`; job runs remain
owned children and `/jobs/runs` gains no deleted GET or PATCH form.
Tag `details=true` likewise remains a live-list option and is rejected with
`include=deleted`; tag relations remain owned children without an independent
deleted GET or PATCH form.

### 5.1 Discover deleted entities (table example)

Deleted-table discovery extends the existing collection:

```http
GET /api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables
    ?include=deleted
    [&name={tableName}]
    [&id={entityId}]
```

Query parameters:

| Parameter | Values | Semantics |
| --------- | ------ | --------- |
| `include` | `non-deleted`, `deleted` | Defaults to `non-deleted`. `deleted` returns tombstone representations. |
| `name` | exact table name | Optional exact-name filter. Multiple deleted generations may match. |
| `id` | decimal string | Optional exact entity-ID filter. At most one current tombstone matches an entity ID. |

The existing request with no new parameters continues to return the current
`EntityListResponse`. A deleted request returns `DeletedEntityListResponse`.
An exact-filter mismatch is a successful empty collection, not a `404`.
Deleted results are ordered by `deletedAt` descending and then entity ID
descending. The POC is intentionally unpaginated.

Example deleted entry:

```json
{
  "deleted": true,
  "id": "984273",
  "deletionId": "77192",
  "name": "orders",
  "deletedAt": 1784800000000,
  "expiresAt": 1785404800000,
  "version": 17,
  "latestForName": true,
  "restorable": true,
  "etag": "deletion-77192-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

`version` is optional and omitted for unversioned metadata. The opaque ETag
covers this complete visible record plus action-relevant deletion-record
identity, state, revision, and terminal receipt fields.

Every complete eligible deletion record created by the feature is
metadata-restorable, which makes no claim about the downstream object or data.
Legacy rows without a complete record remain discoverable with
`restorable: false` and reason `LEGACY_TOMBSTONE`.

The per-item `etag` field identifies that member's deletion generation. The
collection itself does not carry an HTTP `ETag` because it can contain several
independently versioned tombstones.

The server generates the strong ETag by canonicalizing the complete visible
tombstone plus action-relevant deletion-receipt fields and hashing that value
with SHA-256. Its opaque form is
`deletion-{deletionId}-representation-{digest}`. It is a representation
fingerprint, not a lease or secret; exact GET holds no server-side lock while
the client decides whether to recover the entity.

The server does not update an ETag value in place. Each exact GET recomputes
the fingerprint. Action fields such as `latestForName`, `restorable`, the
blocking reason, receipt state, and receipt revision therefore produce a new
ETag when the representation changes. PATCH reports a typed `409` or `410`
before a generic `412` when that change is a semantic conflict or expiry; only
an otherwise-eligible stale representation returns `412` and invites a reread.
A replaced immutable parent commonly leaves no new item representation at the
old URI, so exact GET returns `404` while PATCH can use the retained receipt to
return `409 PARENT_CHANGED`.

Immediately before undelete, the client reads the exact generation from the
existing item URI:

```http
GET /api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables/{table}
    ?include=deleted&id=984273
```

The path name and ID must match one tombstone under the exact live parents; a
mismatch returns `404 TOMBSTONE_NOT_FOUND`. Success returns a singular
`DeletedEntityResponse` and the body token as a strong HTTP `ETag`. This is
the authoritative precondition for PATCH.

### 5.2 Undelete a table

Undelete uses the existing table item URI. The name in the path and the ID in
the query must identify the same tombstone.

```http
PATCH /api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables/{table}?include=deleted&id=984273
If-Match: "deletion-77192-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
Content-Type: application/merge-patch+json

{
  "deleted": false
}
```

The POC accepts only the `deleted` field and only the transition to `false`.
Normal table alterations continue to use the existing `PUT` operation.
`If-Match` is required; omitting it returns `428 Precondition Required`.

On success, the server returns `200 OK` with the normal `TableResponse`. The
restored table keeps its original entity ID. No post-restore HTTP `ETag` is
promised by this POC.

The operation is idempotent for a completed restore when the active row still
records the same entity ID and last-restored deletion ID. The implementation
must retain this restore receipt after `deleted_at` returns to zero. The
operation is not idempotent across a newer drop generation.

### 5.3 Failure contract

| HTTP status | `ErrorResponse.code` / reason | Condition | Client behavior |
| ----------- | ---------- | --------- | --------------- |
| `400` | `1001` | Invalid `include` value, invalid ID, or a patch containing fields other than `deleted: false`. | Fix the request. |
| `403` | `1008` | With authorization enabled, the caller is not a service administrator under the temporary POC gate. | Do not retry unchanged. |
| `404` | `1003` | Exact GET cannot find the path and entity ID; or PATCH finds neither a current tombstone nor a retained matching replay receipt. | Re-run discovery explicitly. |
| `409` | `1015` + `NOT_LATEST_TOMBSTONE` | A newer deletion exists for the same name. | Do not switch generations automatically; return the newer ID to the caller. |
| `409` | `1015` + `ENTITY_ID_REUSED` | The entity ID is active or attached to different logical state without a matching completed restore. | Terminal invariant conflict. |
| `409` | `1015` + `NAME_OCCUPIED` | A different live table uses the original name. | Resolve the live table explicitly. |
| `409` | `1015` + `EXTERNAL_ID_OCCUPIED` | Another live user/group holds the deleted identity's external ID, or another live run holds a captured `job_execution_id`. | Resolve the external identity conflict explicitly; never overwrite or rebind automatically. |
| `409` | `1015` + `PARENT_CHANGED` | PATCH knows the receipt, but the original catalog or schema is deleted, replaced, or has a different immutable ID. Exact GET commonly returns `404` instead. | Restore the parent or stop. |
| `409` | `1015` + `PURGE_IN_PROGRESS` | Irreversible cleanup is observable in progress. This is defensive because normal GC completes claim and purge in one transaction. | Terminal. |
| `409` | `1015` + `LEGACY_TOMBSTONE` | The tombstone predates complete deletion records and cannot be restored safely. | Terminal for this tombstone. |
| `409` | `1015` + `INCOMPLETE_GENERATION` | A required stamped row is missing, a stamped relationship is broken, or the recorded container tree is internally inconsistent. | Roll back the whole tree and require manual metadata repair; do not retry automatically. |
| `410` | `1012` | PATCH still knows the exact receipt, but the deletion expired or was purged. GET can already return `404` after the tombstone row is removed. | Terminal. |
| `412` | `1013` | An otherwise-eligible representation or action-relevant receipt changed concurrently. Typed conflicts, incomplete generations, and expiry take precedence. | Re-read the same ID and retry only if it is still the same deletion generation. |
| `415` | HTTP status | The PATCH media type is not `application/merge-patch+json`. | Fix the request. |
| `428` | `1014` | `If-Match` is absent. | Read the tombstone and retry conditionally. |

The server must return a stable machine-readable error code in addition to a
human-readable message.

While retained, the durable `entity_deletion` record distinguishes an
expired or purged known deletion (`410`) from an ID that was never observed
(`404`). After terminal-receipt retention expires, the record is removed and
the same lookup returns `404`; the server must not infer expiry for an ID
absent from both entity rows and the deletion record.

### 5.4 Retry rules

Undelete is a read-conditional-write sequence:

1. Read the exact tombstone from the item URI with
   `include=deleted&id=...`.
2. Send `PATCH` with the returned `ETag` in `If-Match`.
3. On a connection loss, client/transport timeout, `408`, `502`, or `504`,
   replay the exact PATCH with the same path, entity ID, body, and `If-Match`;
   do not read first because the original request may have committed.
4. On a first or otherwise ambiguous `500`, exact replay is safe but useful
   only within a small ambiguity budget; stop when the same deterministic
   failure repeats. Known temporary unavailability should use `503`.
5. On `429` or `503`, honor `Retry-After` when present, otherwise use bounded
   backoff before exact replay. These statuses may come from infrastructure;
   retry remains bounded even when the header is present.
6. On `412`, do not replay the stale request; read the same entity ID again.
7. Retry only when the entity ID and deletion ID are unchanged and the
   tombstone is still latest and restorable.

Safe to replay and useful to retry are different: receipt-backed replay cannot
apply the restore twice or target a newer generation, but only ambiguous or
plausibly transient outcomes merit another attempt. Recovery clients may
perform bounded exponential retry with jitter, an attempt and
elapsed-time budget, and no parallel retries. They must never switch entity or
deletion IDs. `501` is terminal for the deployment, `505` requires corrected
HTTP-version negotiation, and `400`, `401`, `403`, `404`, `409`, `410`, and
`415` are not automatic retries.

An exact replay is server-verifiable when the durable receipt records `RESTORED` with
the exact accepted ETag, the receipt target still matches path, entity ID,
name, and parent, the generation remains latest, and that entity is live. The
server returns the current live representation without repeating the
transition. This proves the committed effect, not delivery of the prior
response or a byte-identical historical response. The evidence ends after a
later deletion, rename, replacement, parent change, or receipt GC. A separate
idempotency key is unnecessary because `If-Match` plus the receipt identifies
this fixed patch.

## 6. Metadata-only boundary

Restore means restoring Gravitino's relational metadata only. PATCH does not
validate, load, register, or recreate an object in the downstream catalog. A
successful response asserts only that the recorded metadata generation became
live again; it makes no claim that a downstream object exists or matches that
metadata. Downstream reconciliation is a separate concern and is not part of
this API contract. Consequently, `restorable: true` means
metadata-restorable, not downstream-restorable. Internal ownership, grant,
tag, and policy rows stamped by the same deletion are restored as metadata;
external authorization plugins are not replayed.

Identity recovery has the same control-plane boundary. It neither inspects nor
changes an identity provider or another upstream identity source, and it does
not replay Ranger or another external authorization system. A successful user,
group, or role PATCH asserts only that the exact Gravitino relational metadata
generation is live.

Job-template recovery likewise stops at the relational control plane. PATCH
does not contact an executor; resubmit, poll, or cancel a job; recreate deleted
staging files; or replay external authorization. It restores only the template
root and captured terminal-run rows. Ownership, grants, and job metrics remain
outside the standalone generation.

Tag recovery restores only the relational tag root and captured Gravitino
tag-to-metadata-object relations. Relations transition before the root, and
the exact affected-row count detects a missing or corrupt generation.
Ownership, grants, connector state, and external authorization remain outside
the standalone aggregate. Either the tag or metadata-object root may be
restored first; normal joins keep the relation hidden until both roots are
live.

This boundary is an intentional safety choice: leaving downstream data
untouched is safer than automatically recreating, overwriting, registering, or
deleting data from a potentially stale metadata snapshot. Gravitino is the
system of record for the restored control-plane metadata, while connector
state is observed downstream state.

Reconciliation remains a separate design problem. A future control-plane
service may diff live Gravitino metadata against connector-observed state and
classify objects as missing, unexpectedly present, or drifted. Any repair must
be connector-specific and explicitly policy- or human-approved. Reconciliation
is TBD and must not execute inside the atomic undelete transaction.

The existing auto-import path is not that reconciliation layer and must not
implicitly undelete an entity. It can currently observe a surviving downstream
table and overwrite only the base metadata row, bypassing the deletion receipt
and leaving related metadata deleted. Before production rollout to proxied
catalogs, import must detect an active tombstone and either refuse the import
or route through the explicit recovery protocol. This guard is not part of the
table POC.

## 7. Shared storage and concurrency model

Live and deleted discovery have different authorities. Normal table listing
keeps its existing behavior; deleted-table listing reads the relational
store. The POC deliberately does not add an `include=all` merge or pagination.

The shared implementation adds an `entity_deletion` record. It is separate
from the entity row so evidence survives restore and physical entity-row
purge. The logical fields are:

| Field | Purpose |
| ----- | ------- |
| `deletion_id` | Immutable ID for one drop generation. |
| `entity_type`, `entity_id` | Original logical entity. |
| immutable parent IDs and original name | Detect parent replacement and name reuse. |
| `deleted_at`, `expires_at`, `deleted_by` | Deletion time, creation-time retention snapshot, and attribution. |
| `entity_version` | Optimistic-restore precondition. |
| `affected_row_count` | Optional exact generation size for aggregates that can prove completeness. |
| `state` | `DELETED`, `RESTORING`, `RESTORED`, `PURGING`, or `PURGED`. |
| `revision` | Optimistic state-machine revision. |
| `restored_at`, `restore_etag`, `purged_at` | Terminal receipt timestamps and the exact precondition accepted by a successful restore. |

One deletion ID and one deletion timestamp are generated before a drop. Every
affected row receives both values. Restore and purge select rows by the opaque
deletion ID; the timestamp remains the retention and display value. A timestamp
cannot safely identify the affected set because an unrelated grant, tag,
policy, or other relationship can be deleted in the same millisecond.

Pre-feature tombstones have no deletion record or guaranteed shared timestamp.
They are returned with `restorable: false` and reason `LEGACY_TOMBSTONE`; the
server must not revive every historical row for an entity ID.

A table restore performs these steps under the parent write lock and in one
relational transaction:

1. Select the requested tombstone using entity ID and deletion ID.
2. Verify `If-Match`, immutable parents, latest-for-name status, name
   availability, the base row, and current version, then
   recheck current-global expiry inside the transaction before claiming the
   deletion generation.
3. Flip every extant affected row carrying the observed deletion ID back to
   live state.
4. Restore the base `table_meta` row last, so normal reads cannot observe a
   live table with missing required metadata.
5. Mark the deletion record `RESTORED` and persist the exact accepted
   `If-Match` tag in the same transaction, then invalidate local entity and
   relation caches and write the restore change log.

The table POC validates the base row and current version, then restores every
extant column, ownership, grant, tag, policy, and statistic row stamped by the
same deletion. It does not persist expected counts for those optional
relations, so a row manually removed before restore cannot be detected. A
future affected-row manifest is required if strict completeness under manual
corruption becomes a requirement.

Standalone policy recovery validates `policy_meta` and every live policy
version recorded by the deletion. Its receipt records the exact aggregate row
count, so loss of either the base or any captured version is provable. Delete,
restore, and exact-generation purge process versions before the base row; this
also fixes the previous version-delete ordering bug. A missing or mismatched
required version fails closed with `409 INCOMPLETE_GENERATION`, rolls back the
transaction, and requires manual metadata repair before retry. Legacy cleanup
also removes versions left live by the old base-first delete before deleting
the legacy policy root. Standalone policy deletion does not stamp or revive
policy associations, ownership, or grants. Those relations remain independent,
except when a metalake root deletion stamps and restores them as part of its
cross-tree generation. Policy PATCH remains metadata-only and calls neither a
connector nor an external authorization plugin.

Standalone identity recovery also records an exact aggregate row count. A user
generation contains `user_meta`, its live `user_role_rel` memberships, and live
`owner_meta` rows where the user is the owning principal. A group generation is
symmetric. A role generation contains `role_meta`, its live user/group
memberships, live securable-object grants, and live owner rows for the role
itself. Restore validates every captured row, restores relationships first and
the root last, and fails closed with `409 INCOMPLETE_GENERATION` when a required
row is missing or carries a different token. The transaction remains rolled
back until an operator repairs the metadata and deliberately retries.

Membership and ownership rows may overlap two identity aggregates. The first
delete that changes a live shared row stamps and owns it. A later identity
delete sees an existing tombstone and never retags it. Either root can therefore
be restored first: an exact-token shared row stays deleted when owned by the
other generation, or remains hidden by normal joins while its other endpoint
root is deleted. It becomes visible only after both roots are live. Identity
PATCH never contacts an IdP, upstream identity source, Ranger, or another
external authorization plugin.

Standalone job-template recovery records and verifies an exact aggregate row
count. Its generation contains `job_template_meta` and every terminal
`job_meta` row that was live when deletion began. Runs transition before the
template root during delete, restore, and exact-generation purge; the root is
therefore never exposed live before its captured history. A missing or
mismatched captured row fails closed with `409 INCOMPLETE_GENERATION`, rolls
back, and requires manual metadata repair before a deliberate retry.

Job runs are not independent recovery targets. Autonomous finished-job cleanup
creates null-token run tombstones outside a template generation. A later
template delete stamps only still-live terminal runs: the first deletion owns
each row, so the template never adopts or retags those earlier tombstones and
template PATCH never restores them. A live run
that occupies a captured `job_execution_id` fails with
`409 EXTERNAL_ID_OCCUPIED`. No executor, staging state, job metrics, owner,
grant, or external authorization state participates in the transaction.

Entity create, update, drop, restore, and shared relation writers that can
change a schema-owned aggregate take the same database schema-row fence. This
prevents a concurrent writer from adding a live row outside a schema deletion
generation. Shared relation writers now lock owning catalogs before schemas,
matching catalog-tree deletion order. The restore change-log listener handles
every currently recoverable type; container restores conservatively clear the
local entity cache because one change represents a whole tree.

Schema, catalog, and metalake PATCH implement the container rule. The
explicitly deleted container owns the only independently restorable receipt,
and one request restores the whole tree stamped by that cascade in one
transaction. For metalake this exact root token covers the metalake,
catalogs, schemas, leaf metadata and versions, plus metalake-scoped users,
groups, roles, tags, policies, job templates, job runs, and stamped internal
relationships. The metalake root becomes live last. A descendant carrying an
older deletion ID was already deleted and remains deleted. Partial,
best-effort, skipped-row, and child-by-child restore are outside this contract.
Only the latest root generation for the name is eligible. Any detected
validation error, broken required relationship, constraint conflict, changed
required base/version row, or SQL exception rolls the whole restore back; a
persistent invariant failure requires manual metadata repair before retry.

Schema and catalog restore also reject a recorded relationship whose external
owner, role, tag, or policy source is no longer live. Those independently
mutable source rows are validated but not locked by this POC, leaving a narrow
concurrent source-deletion race for later lock-order hardening.

A unique-key violation while restoring `deleted_at = 0` maps to
`409 NAME_OCCUPIED`. A zero-row compare-and-set maps to
`412 TOMBSTONE_CHANGED`, not an internal error.

Garbage-collector deletion predicates must recheck the expected tombstone
state in the outer `DELETE`, including PostgreSQL's ID-subquery variants. A
row restored while a garbage-collector transaction waits must not be
physically deleted after becoming live.

For the POC, effective expiry is always `deleted_at` plus the current global
`gravitino.entity.store.deleteAfterTimeMs` value. The stored `expires_at` is a
creation-time snapshot reserved for a future prospective/per-scope retention
design; it does not override a changed global value after restart. Reads and
garbage collection therefore agree when the global setting changes.

Garbage collection first compare-and-sets each expired recorded deletion
record from `DELETED` to `PURGING`, deletes rows carrying that exact deletion
ID, and writes the `PURGED` receipt in one relational transaction. Restore
claims the same record from `DELETED` to `RESTORING`, so concurrent restore and
purge cannot both
mutate the rows affected by the deletion. Catalog cleanup runs before schema,
leaf, and generic relation cleanup; each container or leaf generation is
drained before legacy collectors can get ahead of it. Only
after bounded recorded-deletion batches are drained does the existing legacy
timeline cleanup run. If a recorded aggregate phase fails, the collector stops
that hard-delete cycle before another collector can remove only part of a
recoverable deletion generation.

Restored and purged receipts remain available for one current global-retention
window after completion, then garbage collection deletes them in bounded
batches. This preserves precise idempotent-replay or `410` behavior for a
useful interval without growing `entity_deletion` indefinitely.

## 8. Authorization

Authorization must be evaluated at a live parent scope because table-owner
resolution currently cannot load a tombstoned table or its deleted owner
relationship. As a temporary POC placeholder when authorization is enabled,
deleted discovery, exact reads, and PATCH are restricted to service
administrators. A later authorization design may delegate recovery at parent
scope, but that is not part of this contract.

## 9. Compatibility with peer systems

The `include=deleted|non-deleted` collection filtering is a deliberately
narrow subset of OpenMetadata's list contract. OpenMetadata restores through
a separate `PUT /v1/tables/restore` action. Gravitino instead uses a
conditional `PATCH` on its existing table item URI so restoration is modeled
as a state transition and does not introduce a parallel restore resource.

The compatibility compromise is resource identity: existing item paths are
name-based, deleted names are reusable, and therefore the tombstone URI uses
path name plus immutable entity ID while ETag binds the deletion generation.
Existing DELETE responses also do not return a tombstone representation or
`Location`. A future canonical ID-based item URI could make the name path an
alias with `Content-Location` or a `self` link, but that migration is outside
the POC.

The latest-tombstone default follows Snowflake's name-based `UNDROP` safety.
Unlike systems that permit restoring an arbitrary dropped generation by ID,
the Gravitino contract rejects a non-latest generation even when its ID is
known. Clients must not switch generations automatically.

References:

- OpenMetadata list tables:
  <https://docs.open-metadata.org/v1.12.x/api-reference/data-assets/tables/list>
- OpenMetadata delete and restore:
  <https://docs.open-metadata.org/v1.12.x-SNAPSHOT/api-reference/data-assets/tables/delete>
- Snowflake `UNDROP TABLE`:
  <https://docs.snowflake.com/en/sql-reference/sql/undrop-table>
- Databricks `SHOW TABLES DROPPED`:
  <https://docs.databricks.com/sql/language-manual/sql-ref-syntax-aux-show-tables-dropped>

## 10. Entity coverage

The API contract is uniform, but transaction coverage remains type-specific:

| Tier | Entity types | Initial behavior |
| ---- | ------------ | ---------------- |
| Complete POC | Table, function, model, fileset, view, topic, policy | Restore each exact tokenized aggregate. Model includes versions, URI rows, and aliases. Policy includes its base and live versions, records their exact aggregate count, but excludes standalone associations, owner, and grants. Fileset, view, topic, and policy restore metadata without touching downstream systems or external authorization; downstream state may be absent or different. Optional relation counts outside the policy version aggregate are not manifested. |
| Complete identity POC | User, group, role | Restore exact count-checked roots plus captured memberships/ownership and, for roles, securable grants. First deletion owns an overlapping relation; later deletion does not retag it. Either root may restore first because normal joins hide the relation until both roots are live. User/group external-ID collisions fail with `409 EXTERNAL_ID_OCCUPIED`. No IdP or external authorization state is read or replayed. |
| Complete operational-metadata POC | Job template | Restore the exact count-checked template root plus captured live terminal job runs. Runs are owned children, transition before the root, and have no independent recovery API. Autonomous null-token run tombstones remain deleted. Executor, staging, metrics, owner, grants, and external authorization are excluded. A live `job_execution_id` collision fails with `409 EXTERNAL_ID_OCCUPIED`. |
| Complete classification POC | Tag | Restore the exact count-checked tag root plus captured live tag relations. Relations transition before the root and have no independent recovery API. The first delete owns an overlapping relation without retagging; either endpoint root may restore first, and joins hide the relation until both are live. Prior independent or null-token relation tombstones remain deleted. Owner, grants, connectors, and external authorization are excluded. |
| Complete container POC | Schema, catalog, metalake | One root receipt restores the nested heterogeneous metadata tree changed by the explicit cascade or force deletion; cascade-only descendants are not independent restore targets. Catalog and metalake restore never initialize connectors. Metalake also restores exact-token users, groups, roles, tags, policies, jobs, and their internal relationships, but does not replay an external authorization plugin. |
| Deferred | Model version | No stable `ModelVersionEntity.id()` exists; a logical version can span multiple relational rows. It is not advertised as a recovery wire type. |
| Deferred | Standalone table statistic | Statistics have stable relational IDs and a public bulk-drop operation, but no existing item resource on which to apply the exact GET and conditional PATCH protocol. Table-root recovery can still restore statistics owned by that table deletion generation. |
| Owned/internal only | Columns, versions, aliases, memberships, ownership, grants, tag/policy relations, job runs | Restore only with the owning deletion generation; never expose an independent undelete API. |
| Unsupported by this mechanism | Partition | There is no relational partition tombstone to restore. |

An entity may support deleted discovery while reporting `restorable: false`
when its tombstone predates complete deletion records.

## 11. Implementation sequence

1. Treat the completed table HTTP-to-relational-store journey as the contract
   checkpoint.
2. Extract its receipt, ETag, latest-generation, error, retry, and REST helper
   behavior behind a reusable entity adapter without changing table behavior.
3. Add function recovery as the first complete second-object vertical slice.
4. Add whole-model recovery, including versions and aliases, without exposing
   individual model-version recovery.
5. Add fileset metadata-shell recovery without connector or filesystem calls.
6. Add view metadata-shell recovery without connector calls.
7. Add topic metadata-shell recovery without Kafka calls.
8. Add schema, catalog, and metalake tree recovery with one root receipt and an
   exact whole-tree inverse transaction per explicit cascade or force
   deletion. This step is complete.
9. Add standalone policy recovery for its base and live versions without
   restoring associations, ownership, grants, connector state, or external
   authorization. This step is complete.
10. Add standalone user, group, and role recovery with count-checked shared-row
    ownership, no retagging, external-ID collision protection, and no IdP or
    external authorization replay. This step is complete.
11. Add standalone job-template recovery with exact count-checked terminal-run
    history, no independent job-run recovery API, and no executor or staging
    side effects. This step is complete.
12. Add standalone tag recovery with exact count-checked relation ownership,
    no retagging, and no connector or external-authorization effects. This
    final planned root-resource rollout step is complete; model-version
    identity and standalone statistics remain separately deferred.
13. Add Java client deleted discovery, exact reads, and conditional restore for
    every supported recovery root. Each typed client passes the selected
    immutable ID and opaque ETag together, exposes typed recovery failures, and
    never silently rereads or changes generations after `412`. This step is
    complete.

Each vertical slice includes all three relational dialects, exact deletion
stamping and inverse transaction, REST/OpenAPI plumbing, and tests. Java client
coverage follows the same protocol for every supported root; Python client
parity remains separate. No new recovery resource path is introduced.

## 12. Test matrix

The table POC must cover at least:

1. Existing unfiltered table-list request and JSON response remain unchanged.
2. Deleted-only listing, exact name filtering, exact ID filtering, ordering,
   and per-item restore tokens.
3. Successful restore preserves the entity ID, validates the base/current
   version, and atomically restores every extant metadata row carrying the
   exact deletion ID without reviving a relationship independently deleted in
   the same millisecond.
4. A repeated request for the same completed deletion is idempotent.
5. An otherwise-eligible stale `If-Match` returns `412`; typed semantic
   conflicts and expiry take precedence. The client re-reads and retries only
   the same deletion ID.
6. A newer same-name tombstone, reused entity ID, live-name collision,
   changed parent, legacy tombstone, and purge-in-progress state each return
   the documented conflict.
7. Restore never calls the downstream catalog and succeeds independently of
   downstream object availability.
8. PostgreSQL garbage collection cannot remove a concurrently restored row.
9. With authorization enabled, non-administrator callers cannot infer
   tombstone details or restore them.
10. Java recovery clients never switch entity IDs or deletion IDs, do not hide
    `412` by rereading automatically, and send merge-patch plus the exact strong
    `If-Match` precondition on PATCH.
11. Legacy tombstones without a deletion record are visible but return
    `restorable: false` with reason `LEGACY_TOMBSTONE`.
12. A lost success response and ambiguous `500`, `502`, or `504` can replay the
    exact conditional PATCH without repeating its effect; permanent failures
    stop after a bounded retry budget.
13. `429` and `503` retries honor `Retry-After`, and no retry changes entity ID
    or deletion ID.

Schema adds container-specific proofs: hierarchy-scoped discovery lists only
independently deleted roots; one root receipt restores nested schemas and every
primary leaf/version family in one transaction; a child tombstone that predates
the cascade remains deleted; a parent replacement, name collision, newer root
generation, or missing required row prevents any partial revival; and exact
successful replay remains idempotent.

Catalog adds the same proofs across all schemas and primary metadata families
owned by one force deletion. Discovery and restore must not initialize or call
the connector, and the immediate response must not disclose raw stored
connector properties.

Metalake adds the same proofs across catalogs, schemas, leaf metadata, and
metalake-scoped users, groups, roles, tags, policies, and jobs. The exact root
token must leave older descendant tombstones deleted, restore the root last,
reject every non-latest generation, avoid connector and external authorization
plugin calls, and roll back the entire tree when any required row or stamped
relationship is inconsistent. Persistent inconsistency requires manual repair
after `409 INCOMPLETE_GENERATION` before the same exact GET, ETag, and
conditional PATCH flow is deliberately retried.

Policy adds exact deleted discovery and GET plus conditional merge PATCH on
the existing routes while preserving its application-JSON enable/disable
PATCH. Tests prove versions-first/base-last delete, restore, and purge; a
missing required policy version returns `409 INCOMPLETE_GENERATION` with no
partial revival; associations, owner, and grants remain outside a standalone
generation; and no connector or external authorization plugin is invoked.

User, group, and role add the same existing-route discovery, exact GET, and
conditional merge PATCH. Tests prove exact aggregate counts, relationships
before root on restore, first-delete ownership and no retagging for overlapping
memberships/owner rows, both restore orders, and join-hidden shared rows until
both roots are live. They also prove user/group `EXTERNAL_ID_OCCUPIED`, complete
rollback and manual repair for `INCOMPLETE_GENERATION`, temporary
`SERVICE_ADMIN` gating, and no IdP, upstream identity, Ranger, or external
authorization replay.

Job template adds deleted discovery and exact GET plus conditional merge PATCH
only on the existing `/jobs/templates` routes. Tests prove active-job deletion
is still rejected; captured terminal runs transition before the root; exact
affected counts detect incomplete generations; autonomous null-token run
tombstones are not adopted or restored; and live `job_execution_id` reuse
returns `EXTERNAL_ID_OCCUPIED`. They also prove temporary `SERVICE_ADMIN`
gating and that restore never contacts the executor, submits, polls, cancels,
recreates staging files, restores job metrics, or replays external
authorization.

Tag adds deleted discovery and exact GET plus conditional merge PATCH only on
the existing tag routes. Tests prove relation-first/root-last delete, restore,
and purge; exact affected counts and full rollback with
`INCOMPLETE_GENERATION`; first-delete ownership without retagging; both root
restore orders with join-hidden relations until both roots are live; and that
prior independent or null-token relation tombstones remain deleted. They also
prove temporary `SERVICE_ADMIN` gating and no owner, grant, connector, or
external-authorization replay.

The Java recovery clients add wire-to-domain projection, PATCH query transport,
typed recovery errors, filtered discovery, exact reads, accepted replay, and a
typed restored entity for every supported root. Their recovery input is the
`DeletedEntity` returned by the exact read, binding ID and ETag in one immutable
caller-visible value.

## 13. Open questions beyond the POC

Object-specific semantic questions are recorded in
[Metadata Undelete Open Questions](metadata-undelete-open-questions.md). They
do not block the metadata-only API and transaction skeleton.

1. Whether future per-scope retention is frozen into `expiresAt` at drop time
   or evaluated from current scope configuration by garbage collection. The
   POC deliberately retains current-global, restart-time semantics.
2. What immutable API identity an individual model version should use if it
   becomes independently recoverable.
3. Whether restored and purged deletion records need a separately
   configurable audit retention beyond the POC's one-global-window cleanup.
4. Which parent-scope privileges should replace the temporary
   service-administrator-only gate.
5. Whether a future operator-only diagnostic API should expose autonomous
   job-run tombstones without making them independently recoverable.
6. How to sequence Python transport and model parity after the Java client
   rollout.
