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

# Iceberg REST Deletion POC Storage Boundary

| Field | Value |
| --- | --- |
| Status | Storage foundation only |
| Scope | Relational metadata required before the Iceberg REST deletion API is implemented |
| Branch | `codex/iceberg-rest-delete-stack-10-storage` |

## Implemented in this stack

This stack establishes deletion-generation identity and exact relational visibility:

- `table_meta.deletion_id` is a nullable pointer to the opaque
  `entity_deletion.deletion_id`.
- The table row and its current version, columns, owner, securable object, tag, policy, and
  statistic relations can be stamped with the same deletion ID in one relational transaction.
- `entity_deletion` retains the original table identity, namespace/name snapshots, lifecycle and
  retention fields, optimistic revision, audit correlation, and future purge association fields.
- `active_name_key` is the single unique reservation for a currently deleted name. Historical
  actions are addressed by deletion ID or immutable entity history; there is no second
  `name_lookup_key`.
- `entity_deletion_audit` stores append-only lifecycle events separately from authoritative action
  state.
- Storage services provide exact-generation tombstone and restore predicates for a later API
  layer. Existing relational reads continue to hide rows whose normal `deleted_at` marker is set.

The model is intentionally generic at its core and currently exercised for tables. It contains
enough identity, retention, status, and job-association metadata to support a future Iceberg purge
implementation without making that implementation part of this stack.

## Deferred beyond this stack

This stack does not persist an Iceberg-specific deletion context. Capturing immutable Iceberg UUID,
metadata location, FileIO reconstruction input, and exact physical targets belongs with the purge
implementation that defines and consumes those values.

The following are also deferred:

- durable purge-job headers, leases, heartbeats, reclaim, retry, and aggregate progress;
- GC selection and atomic claims from `DELETED` to `PURGING`;
- per-action cleanup execution and `PURGED` finalization;
- registration removal and external Iceberg file deletion; and
- a restart-safe per-file or per-target ledger for large and partially completed purges.

Therefore, the metadata in this stack supports future purge, but no purge worker claims deletion
actions or performs cleanup here. API and worker stacks must not present `PURGING` or `PURGED` as
implemented behavior until the corresponding durable job and execution system is added and tested.
