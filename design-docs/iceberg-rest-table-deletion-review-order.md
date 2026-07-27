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

# Iceberg REST Table Deletion Review Order

| Field | Value |
| --- | --- |
| Status | Review index; companion drafts remain non-normative until reconciled |
| Scope | Iceberg REST table DELETE, retained UNDROP, and asynchronous hard purge |
| Server stack | `codex/iceberg-rest-delete-stack-10-storage` through `stack-70-review-guide` |
| Test stack | `codex/iceberg-rest-delete-tests-stack-10-support` through `stack-60-review-guide` |

## Read this first

The three companion documents are preserved as review drafts:

1. [API semantics](iceberg-rest-table-deletion-lifecycle.md)
2. [Metadata and purge-job design](iceberg-rest-table-deletion-metadata-design.md)
3. [Gap analysis](iceberg-rest-table-deletion-gap-analysis.md)

Use the [subforest review ledger](iceberg-rest-table-deletion-subforest-review-ledger.md) to review
each incremental implementation branch and record an explicit decision. A pushed branch is a
review snapshot, not an approval.

They capture the design exploration, but several passages predate the simplified implementation.
Until those passages are reconciled, implementation behavior and the decisions below are the
current review baseline.

## Accepted baseline

- DELETE is one short relational transaction. It creates an opaque deletion ID, tombstones the
  exact table generation and related rows, captures Iceberg cleanup context, reserves the name,
  and appends audit. It does not unregister Iceberg or delete files on the request thread.
- UNDROP is one relational transaction addressed by deletion ID and a strong ETag. It reactivates
  only rows stamped with that deletion ID and returns the ordinary live table response. It does not
  re-register the table.
- Point table mutations should use optimistic compare-and-set predicates rather than explicit
  schema/table pre-locks. A shared atomic name claim is still required across create, register,
  rename, DELETE, UNDROP, and purge.
- The public action states are `DELETED`, `RESTORED`, `PURGING`, and `PURGED`. Purge eligibility is
  derived; `PURGE_PENDING` and `RESTORING` are not persisted public states.
- GC creates one bounded durable batch job and atomically claims eligible actions directly into
  `PURGING`. Workers record per-target progress, use lease epochs for fencing, and finalize each
  action independently.
- The first implementation is table-only, hard-delete-only, and uses
  `purge_job_type=ICEBERG_REST_PURGE`.

## Known stale material

The following draft concepts are not the intended target and must not be treated as normative:

- DELETE `DROP_PREPARED` or unregister/reconciliation sagas;
- UNDROP `UNDROP_CLAIMED`, leases, or Iceberg re-registration;
- provider-soft-delete or alternate purge-policy negotiation;
- pending-job cancellation during UNDROP; and
- generic recovery behavior for views, schemas, or other entity types.

The current server snapshot still uses explicit row locks in DELETE and UNDROP. Replacing those
pre-locks with optimistic point updates is the next accepted implementation change, not evidence
that the saga design should return.

## Recommended review sequence

1. Confirm the four-state lifecycle and the PRD configuration matrix in the API draft.
2. Review immutable identity, deletion-generation pointers, and exact-row restoration in the
   metadata draft and server storage stack.
3. Review the optimistic-concurrency and atomic-name-claim change before reviewing API edge cases.
4. Review bounded purge jobs, durable target progress, retry/fencing, and exact-generation
   finalization.
5. Review authorization, HTTP error mapping, terminal receipts, and audit retention.
6. Review Cucumber and Locust coverage, then run live restart, multi-node, and large-object tests.

## Priority queue

1. Replace pessimistic point pre-locks with OCC and close the name-reservation race.
2. Correct DELETE/UNDROP error mapping and exact-generation authorization behavior.
3. Resolve hard-delete semantics for Iceberg `gc.enabled`, versioned object stores, and
   conditional registration removal.
4. Define terminal receipt, context, job, and audit retention plus operator redrive.
5. Prove standalone parity and execute the live PRD scale/restart/multi-node suites.
