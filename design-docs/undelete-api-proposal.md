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

# Metadata Undelete Semantics

Status: Table, function, model, fileset, view, topic, policy, user, group, role,
job template, tag, schema, catalog, and metalake API/store POCs complete; the
planned metadata-root and Java recovery-client rollouts are complete.

This is the reviewable API proposal. See
[Soft-Delete Retention and Metadata Recovery](soft-delete-retention.md) for
storage, transaction, GC, and implementation details. Retry and edge cases are
appendices here so the main contract stays concise. Non-blocking semantic
follow-ups are tracked in
[Metadata Undelete Open Questions](metadata-undelete-open-questions.md).

## Background

Gravitino already soft-deletes relational metadata: live rows have
`deleted_at = 0`, deleted rows have a timestamp, and GC later removes them.
Normal reads hide those rows. Connector deletion is independent and may have
already removed the downstream object.

## Gap

There is no public API to discover or recover retained metadata. The API must
select one exact deletion safely despite reusable names, concurrent requests,
newer deletion generations, parent replacement, and GC.

## General API

Every supported metadata object uses the same protocol. Only its immutable
parent scope and inverse metadata transaction vary. Existing collection and
item resources are extended; no `/deleted`, `/restore`, or `/undelete` action
routes are added.

| Operation | Route | Result |
| --- | --- | --- |
| Discover | `GET <collection>?include=deleted[&name=<name>][&id=<id>]` | Deleted-only results, newest first; no match is an empty list. |
| Read exact | `GET <item>?include=deleted&id=<id>` | One tombstone plus a strong HTTP `ETag`. |
| Undelete | `PATCH <item>?include=deleted&id=<id>` | With `If-Match` and `{"deleted":false}`, returns the normal live response. |

The item remains the existing readable name URI, such as `.../tables/orders`.
Name alone is ambiguous because a live object and several retained deletions
can share it. The path supplies the original name, `id` pins the immutable
object, and the ETag pins its deletion generation and representation. Query
parameters are part of the resource URI, so this remains resource-oriented.
A future canonical ID path could make the name path an alias, but that is not
required for this POC.

Hierarchical schemas keep the existing collection scope:
`GET .../schemas?include=deleted` lists top-level deleted roots, while
`parentSchema=A` lists roots immediately below `A`. The item path carries the
full logical schema name, such as `A:B`. A descendant stamped only because an
ancestor cascade deleted it is not an independent list or PATCH target.

Metalake recovery uses the same conditional flow on the existing metalake
collection and item resources. Its exact GET and PATCH select the immutable
metalake ID and one root deletion generation; catalog, schema, and leaf rows
stamped by that generation are not independent recovery targets.

Policy recovery likewise extends the existing policy collection and item
resources. Unfiltered requests and the existing `application/json` PATCH for
enable/disable remain unchanged; recovery alone uses
`application/merge-patch+json` with `include=deleted`, exact ID, and
`If-Match`.

User, group, and role recovery use the same existing collection and item
resources. Their normal list, get, add/create, and remove/delete forms remain
unchanged. Deleted discovery and recovery are temporarily `SERVICE_ADMIN`
only. A user or group additionally fails with `409 EXTERNAL_ID_OCCUPIED` when
another live principal holds its external ID.

Job-template recovery extends the existing `/jobs/templates` collection and
item resources; `/jobs/runs` gains no recovery route. `details=true` remains a
live-list option and is rejected with `include=deleted`. One standalone
template generation contains its root and the terminal job runs captured by
that template deletion. A live run reusing a captured `job_execution_id`
fails with `409 EXTERNAL_ID_OCCUPIED`. Deleted discovery and recovery are
temporarily `SERVICE_ADMIN` only.

Tag recovery likewise extends the existing tag collection and item resources.
`details=true` remains a live-list option and is rejected with
`include=deleted`. A standalone tag generation contains the tag root and the
live tag-to-metadata-object relations captured by that deletion; those
relations have no independent recovery route. Deleted discovery and recovery
are temporarily `SERVICE_ADMIN` only.

`include` defaults to `non-deleted`; `deleted` selects tombstones only. `id` is
optional for discovery and required for exact GET and PATCH. Recovery PATCH
consumes `application/merge-patch+json`; `If-Match` must contain the current
strong ETag from an exact read of the same path and ID. Clients see, but never
select by, `deletionId`.

### Critical user journey: undelete a table

Assume `orders` was deleted from `demo.hive.sales`.

1. Discover retained generations by name:

```http
GET /api/metalakes/demo/catalogs/hive/schemas/sales/tables?include=deleted&name=orders

HTTP/1.1 200 OK
{
  "code": 0,
  "deletedEntities": [{
    "deleted": true,
    "id": "984273",
    "deletionId": "77192",
    "name": "orders",
    "type": "table",
    "deletedAt": 1784800000000,
    "expiresAt": 1785404800000,
    "deletedBy": "alice",
    "version": 17,
    "latestForName": true,
    "restorable": true,
    "etag": "deletion-77192-representation-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }]
}
```

2. Read the selected immutable ID to obtain its current precondition:

```http
GET /api/metalakes/demo/catalogs/hive/schemas/sales/tables/orders?include=deleted&id=984273

HTTP/1.1 200 OK
ETag: "deletion-77192-representation-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
{
  "code": 0,
  "deletedEntity": {
    "deleted": true,
    "id": "984273",
    "deletionId": "77192",
    "name": "orders",
    "type": "table",
    "deletedAt": 1784800000000,
    "expiresAt": 1785404800000,
    "deletedBy": "alice",
    "version": 17,
    "etag": "deletion-77192-representation-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "latestForName": true,
    "restorable": true
  }
}
```

3. Apply the conditional state change to that same item URI:

```http
PATCH /api/metalakes/demo/catalogs/hive/schemas/sales/tables/orders?include=deleted&id=984273
If-Match: "deletion-77192-representation-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
Content-Type: application/merge-patch+json

{"deleted": false}

HTTP/1.1 200 OK
```

The response body is the normal `TableResponse`. The original metadata ID and
deletion-scoped related rows are live again. No connector was read or changed,
so this says nothing about downstream data.

If the `200` response is lost, replay the exact PATCH with the same ETag. The
durable receipt recognizes the completed operation and returns the same current
live table without applying recovery twice. A later rename, replacement,
parent change, or deletion ends that replay guarantee.

### ETag and conflicts

The server computes, rather than leases, each ETag. It hashes the canonical
tombstone and action-relevant receipt state with SHA-256:

```text
deletion-{deletionId}-representation-{digest}
```

Exact GET holds no lock. During PATCH, a short-lived parent row lock, unique
constraints, and receipt compare-and-set protect the database transaction. A
successful transaction stores the accepted ETag in the `RESTORED` receipt so
an identical second request can return idempotent `200`.

A later GET recomputes the ETag. Changes to `latestForName`, `restorable`, its
reason, or receipt revision change the digest. Typed outcomes take precedence
over generic staleness: name collision and a newer generation are `409`,
expiry is `410`, and only an otherwise-eligible stale representation is `412`.
A replaced parent may leave no representation at the old URI, so GET returns
`404` while PATCH can explain `409 PARENT_CHANGED` from the retained receipt.

### Errors

| HTTP | Meaning |
| ---: | --- |
| `400` | Invalid filter, ID, media combination, or patch body. |
| `403` | With authorization enabled, caller is outside the temporary service-admin gate. |
| `404` | Exact GET cannot find the path and ID, or PATCH finds neither a tombstone nor a retained replay receipt. |
| `409` | PATCH knows the target but recovery is unsafe: not latest, ID reused, name occupied, an identity or job-execution external ID is occupied, parent changed, purge in progress, legacy tombstone, or an incomplete recorded generation. |
| `410` | PATCH still knows the exact receipt, but it expired or was purged. |
| `412` | PATCH sees an otherwise-eligible changed representation; reread the same ID. |
| `415` | PATCH uses the wrong media type. |
| `428` | PATCH is missing `If-Match`. |

## Cases and semantics

### Delete

- Existing DELETE routes, connector behavior, flags, and responses do not
  change. A metadata deletion that changes live rows records one generation.
- Job-template deletion keeps its current guard: if any associated job is
  active, the delete is rejected and no recoverable generation is created.
- The deleted name is reusable immediately. Each later deletion is a new
  generation; only the newest same-parent/type/name generation is eligible.
- Connector `purge` keeps its current meaning. Retained metadata can still be
  recovered, but downstream data cannot.

### Undelete

- One transaction restores extant rows stamped by the exact deletion ID and
  restores the base entity last. A detected conflict or SQL failure rolls back.
- The completed leaf POCs validate the base row and required current-version
  state. Function, model, fileset, view, topic, and policy recovery revives
  only owned rows stamped by the same deletion, leaving earlier retention
  tombstones deleted. A standalone policy generation consists of
  `policy_meta` plus the policy versions that were live when it was deleted;
  version rows transition before the base row during delete, restore, and
  purge. Its owner, grants, and associations deliberately remain outside that
  standalone generation. Fileset, view, topic, and policy recovery are
  metadata-only; downstream state may be missing or different. Optional
  relationship counts are generally not persisted, so manual corruption may
  require repair before recovery. Policy is stricter: its receipt records the
  exact base-plus-version row count. A missing or mismatched captured policy
  version therefore returns `409 INCOMPLETE_GENERATION`; the transaction stays
  rolled back until an operator repairs the metadata and deliberately retries.
- Internal metadata relationships owned by the deletion are restored. No
  connector or external authorization plugin is called.
- A standalone user generation is the user root, its role memberships, and
  ownership rows where that user is the owning principal. A group generation
  is symmetric. A role generation is the role root, user/group memberships,
  securable grants, and ownership rows for the role itself. Each receipt
  records the exact affected-row count; a missing or mismatched captured row
  returns `409 INCOMPLETE_GENERATION`, rolls back, and requires manual repair.
- Membership and ownership rows can belong to two supported roots. The first
  delete that changes a live shared row owns it; a later delete never retags
  the tombstone. Either root may be restored first. The exact-token row remains
  join-hidden until both roots are live, then becomes visible without a partial
  or cross-generation restore.
- A standalone job-template generation contains `job_template_meta` plus the
  terminal `job_meta` rows that were live when that template was deleted. Job
  rows transition before the template root during delete, restore, and purge,
  and the receipt records the exact affected-row count. A missing or mismatched
  captured row returns `409 INCOMPLETE_GENERATION`, rolls back, and requires
  manual metadata repair. Job runs are never independent recovery targets.
- Autonomous finished-job cleanup is a separate deletion. A run already
  tombstoned with no template deletion token remains owned by that first
  deletion; a later template delete neither adopts nor restores it. If another
  live run occupies a captured `job_execution_id`, restore fails closed with
  `409 EXTERNAL_ID_OCCUPIED`.
- A standalone tag generation contains `tag_meta` plus the live
  `tag_relation_meta` rows captured when the tag was deleted. Relations
  transition before the tag root during delete, restore, and purge, and the
  receipt records the exact affected-row count. The first delete that changes
  an overlapping live relation owns it; later tag or object cascades never
  retag it. Either endpoint root may be restored first, and normal joins hide
  the relation until both are live. Prior independent or null-token relation
  tombstones remain deleted. A missing or mismatched captured relation returns
  `409 INCOMPLETE_GENERATION`, rolls back, and requires manual metadata repair.
- Schema recovery applies the same rule to one deletion generation for an
  entire nested metadata tree. Only the explicitly deleted schema root is
  discoverable; descendants removed by its cascade return with that root.
- Catalog recovery extends that transaction to the catalog root, all schemas,
  primary metadata objects, versions, and owned relationships stamped by its
  force deletion. The immediate response omits stored connector properties;
  a later normal GET applies the catalog's usual property filtering.
- Metalake recovery extends the same exact-token transaction across the root,
  catalogs, schemas, leaf metadata, versions, and metalake-scoped users,
  groups, roles, tags, policies, job templates, job runs, and their stamped
  internal relationships. The metalake root is restored last.
- Exact successful replay is idempotent. Clients never switch entity or
  deletion IDs automatically.

### Metadata-only boundary

Gravitino is the system of record for restored control-plane metadata;
connector state is observed downstream state. Leaving downstream systems
untouched is safer than recreating, overwriting, registering, or deleting data
from a stale snapshot. Future reconciliation may diff the two states and apply
explicit connector-specific repair, but it must not run inside PATCH.

For users, groups, and roles, the same boundary excludes an identity provider
or any upstream identity source and excludes Ranger or another external
authorization system. Recovery revives only Gravitino relational metadata; it
does not recreate an external principal or replay external grants.

For job templates, PATCH restores relational template and captured terminal-run
history only. It does not contact an executor; resubmit, poll, or cancel a job;
recreate staging files; or replay external authorization. Ownership, grants,
and job metrics are not part of the standalone template generation.

For tags, PATCH restores only the tag root and captured Gravitino association
rows. Ownership, grants, connector state, and external authorization are not
read or replayed.

Auto-import is not reconciliation. Before production rollout to proxied
catalogs, it must detect a tombstone and refuse import or use this recovery
protocol instead of reviving only the base row.

### Container cascades

Schema still requires `cascade`; catalog and metalake still require `force`.
The explicitly deleted root owns one generation, and only live descendants
changed by that operation receive its deletion ID. Previously deleted children
remain deleted. PATCH restores the recorded tree atomically; a descendant
conflict rolls everything back for manual repair. Schema, catalog, and
metalake implement this contract for their complete retained metadata trees.
Only the latest root generation for the name is eligible. A missing required
row, broken stamped relationship, name conflict, or other inconsistency rolls
back the entire transaction with `409 INCOMPLETE_GENERATION` and requires manual
metadata repair before a deliberate retry.
No connector or external authorization plugin is initialized or replayed.

## Reference systems

- [OpenMetadata](https://docs.open-metadata.org/v1.12.x/api-reference/data-assets/tables/delete)
  exposes deleted entities and restores by immutable ID. Gravitino keeps those
  concepts but PATCHes the existing item resource.
- [Snowflake](https://docs.snowflake.com/en/user-guide/data-time-travel)
  exposes retained history and restores only the latest eligible generation,
  failing on name collision.
- [Apache Polaris](https://polaris.apache.org/releases/1.5.0/configuration/configuration-reference/)
  has no undelete API but separates logical drop from destructive purge.

## Coverage

Table, function, model, fileset, view, topic, policy, user, group, role, job
template, tag, schema, catalog, and metalake are the reference implementations.
Whole-model recovery proves exact aggregate restore for a version/URI/alias
tree; fileset proves the connector-backed metadata-shell contract for managed
and external objects without filesystem recreation; view proves the same
contract for versioned JSON metadata without recreating the downstream view;
topic proves the metadata-shell contract when the downstream Kafka delete is
irreversible. Schema proves that one independently deleted root can restore a
nested, heterogeneous metadata tree without reviving older child tombstones.
Catalog proves the same exact-tree rule across a force-deleted catalog while
never initializing its connector. Metalake proves the rule across its full
force-deleted metadata tree, including metalake-scoped identity,
authorization, classification, policy, and job metadata, without connector or
external authorization replay. Standalone policy recovery proves a versioned
control-plane aggregate without connector or external authorization replay;
its associations, owner, and grants are intentionally not revived. A metalake
root still owns cross-tree policy relations stamped by that force deletion.
Standalone identity recovery proves exact, count-checked shared relationship
semantics without contacting an identity provider or external authorization
plugin. Standalone job-template recovery proves exact, count-checked terminal
history recovery without contacting an executor or recreating staging state.
Standalone tag recovery completes the planned metadata-root rollout and proves
exact, count-checked association recovery without ownership, grants,
connector, or external authorization replay. Standalone model versions remain
deferred because they lack a stable entity ID and are not advertised as a
recovery wire type. Standalone table statistics are also deferred: their
public deletion is bulk-shaped and there is no existing statistic item
resource for exact GET and conditional PATCH. Partitions have no relational
tombstone.

The Java client exposes this same deleted-list, exact deleted-entity load, and
restore interaction for every supported recovery root. Restore accepts the
previously read `DeletedEntity`, keeping its immutable ID and opaque ETag bound
together; it returns typed recovery failures and never automatically
substitutes a newer generation after `412`. Python client parity remains
outside this POC.

## Appendix A: Retry contract

Safe to replay does not mean useful to retry. Automatic PATCH retries are
bounded by attempts and elapsed time, use backoff and jitter, and never run in
parallel. An exact replay never changes path, ID, body, or `If-Match`; the new
conditional request allowed after `412` may use a fresh ETag only for the same
deletion ID.

| Outcome | Client behavior |
| --- | --- |
| Connection loss, client timeout, `408`, `502`, `504` | Outcome may be unknown; replay the exact PATCH without rereading first. |
| First ambiguous `500` | One small ambiguity budget is safe; stop when the same deterministic failure repeats. |
| `429`, `503` | Honor `Retry-After`, otherwise back off, then replay exactly within the same bounded budget. |
| `412` | Do not replay stale input. Reread the same ID and issue a new PATCH only for the unchanged deletion ID. |
| `428` | Exact-read first; never repeat the unconditional request. |
| `400`, `401`, `403`, `404`, `409`, `410`, `415`, `501`, `505` | Do not retry unchanged automatically. |

`429` is rate limiting, not an undelete conflict. Receipt-backed replay proves
the committed effect only while the exact accepted ETag, target identity,
latest generation, live row, and terminal receipt still agree. It does not
prove delivery of the original response.

This follows [RFC 5789](https://www.rfc-editor.org/rfc/rfc5789) conditional
PATCH guidance, [RFC 6585](https://www.rfc-editor.org/rfc/rfc6585) rate-limit
semantics, and [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110)
`Retry-After` semantics.

## Appendix B: Edge cases

| Situation | Outcome |
| --- | --- |
| Collection filters match nothing | Empty list, not `404`. |
| Path and ID do not match | `404`; rediscover explicitly. |
| Another live object occupies the name | `409 NAME_OCCUPIED`; never overwrite. |
| A newer same-name deletion exists | `409 NOT_LATEST_TOMBSTONE`; never switch automatically. |
| Immutable ID is active as different state | `409 ENTITY_ID_REUSED`; repair manually. |
| User/group external ID belongs to another live principal | `409 EXTERNAL_ID_OCCUPIED`; resolve the identity conflict manually. |
| Captured job execution ID belongs to another live run | `409 EXTERNAL_ID_OCCUPIED`; resolve the run identity conflict manually. |
| Parent was deleted or replaced | GET commonly `404`; PATCH may return `409 PARENT_CHANGED`. |
| Two clients PATCH the same ETag | One transition; both receive idempotent `200`. |
| Success response is lost | Exact PATCH replay returns the current live object. |
| Object was restored and deleted again | Old request cannot mutate the new generation. |
| Retention expired or GC won | `410` while receipt remains; eventually `404`. |
| Legacy tombstone lacks a receipt | Visible but `restorable:false`; PATCH returns `409`. |
| Required stamped row or relationship is missing or inconsistent | `409 INCOMPLETE_GENERATION`; no partial restore or automatic retry. |
| Two identity deletes overlap on a membership or owner row | The first delete owns the row; no retagging. Either root may be restored first, and joins hide the row until both roots are live. |
| Tag and metadata-object deletes overlap on an association row | The first delete owns the row; no retagging. Either root may be restored first, and joins hide the row until both roots are live. |
| Finished run was autonomously deleted before its template | Its null-token tombstone is not adopted or restored by the template generation. |
| Job template has an active run at delete time | Existing delete guard rejects the request; no recovery receipt is created. |
| Unrelated row was deleted in the same millisecond | Different deletion ID; it remains deleted. |
| Auto-import sees a tombstone | Known POC gap; must not revive only the base row. |
| Optional relationship was manually lost | POC cannot detect it without an affected-row manifest. |
| Existing item already supports PATCH | Before rollout, mark existing mutation as consuming its current JSON type; recovery consumes merge-patch. |
