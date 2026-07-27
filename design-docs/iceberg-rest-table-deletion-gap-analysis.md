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

# [Gap Analysis] Asynchronous Hard Delete and Soft Delete for Iceberg REST Catalog

| Field        | Value                                                                                 |
| ------------ | ------------------------------------------------------------------------------------- |
| Status       | Draft gap analysis; resolution iteration in progress                              |
| Author       | Nevin Zheng                                                                           |
| Created      | 2026-07-26                                                                            |
| Last updated | 2026-07-26                                                                            |
| Scope        | Iceberg REST table deletion, retained UNDROP, asynchronous purge, and current R1  |
| Related      | [API semantics](iceberg-rest-table-deletion-lifecycle.md); [metadata and purge-job design](iceberg-rest-table-deletion-metadata-design.md); [current R1 design](async-iceberg-rest-hard-deletion.md) |
| PRD source   | Mark Hoerth, _Asynchronous Hard Delete and Soft Delete for Iceberg REST Catalog_, May 12, 2026; repository location TBD |

## Purpose

This document checks the two proposed Iceberg REST table-deletion designs against Mark Hoerth's
_Asynchronous Hard Delete and Soft Delete for Iceberg REST Catalog_ PRD, dated May 12, 2026, and
against the current R1 implementation. It is a requirements traceability matrix and failure-mode
review, not another lifecycle proposal.

The review distinguishes:

- the PRD product contract;
- normative behavior proposed by the API and metadata drafts;
- POC implementation choices; and
- behavior already present in R1.

The source documents should not be treated as implementation-ready until every blocking conflict
and safety-critical omission below is resolved or explicitly accepted as a product delta.

## Review rubric

| Status             | Meaning                                                                           |
| ------------------ | --------------------------------------------------------------------------------- |
| `PASS`             | Explicitly answered, internally consistent, and testable                         |
| `PARTIAL`          | Addressed but missing an invariant, failure behavior, operational rule, or test  |
| `CONFLICT`         | The PRD, proposal, or current implementation gives an incompatible answer        |
| `MISSING`          | Not addressed                                                                     |
| `N/A`              | Explicitly outside scope with a valid justification                              |
| `PRODUCT DECISION` | Cannot be closed technically without an approved product choice                  |

## Overall result

**Verdict: `INSUFFICIENT`**

| Status             | Count |
| ------------------ | ----: |
| `PASS`             |    38 |
| `PARTIAL`          |    25 |
| `CONFLICT`         |    10 |
| `MISSING`          |     8 |
| `PRODUCT DECISION` |     2 |
| `N/A`              |     1 |
| **Total**          | **84** |

The central table-generation model is strong: deletion identity, exact UNDROP replay, name
reservation, UNDROP-versus-GC compare-and-set, job fencing, and generation-predicated purge
finalization are well specified. The package is incomplete because several explicit PRD
requirements conflict with the proposed scope or API, and much of the required test and operator
contract is absent.

## PRD requirements traceability

| PRD requirement                         | Status     | Finding                                                                                                                                                                 |
| --------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1: asynchronous hard delete            | `PARTIAL`  | The target drafts define durable asynchronous cleanup, but current R1 remains header-gated and auxiliary-only, unregisters before persisting its job, and lacks the PRD timing proof. |
| R2: retained soft delete and UNDROP      | `CONFLICT` | The table path is detailed, but views, schema cascade, authorization, HTTP statuses, and the proposed multi-record architecture differ from the PRD.                    |
| R3: scale and concurrency testing        | `PARTIAL`  | Races are mentioned, but required Locust/Cucumber workloads, file-count tiers, timing assertions, feature combinations, and execution cadence are absent.               |

## Detailed verification

### 1. PRD alignment and DELETE behavior

| #   | Status     | Evidence and finding                                                                                                                                                                 |
| --: | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `PARTIAL`  | PRD §§1.1 and 2–4; API Appendix D; metadata POC sequence. The product flows are designed, but the R3 program is not incorporated.                                                    |
| 2   | `PASS`     | PRD §3.2; API **Delete**; metadata **DELETE saga**. All five effective configuration cases agree.                                                                                   |
| 3   | `CONFLICT` | PRD §§1.1–1.2 versus both metadata models. The PRD calls for an intentionally simple reused tombstone/executor; the proposal adds action, context, batch job, audit, ETags, and receipts. |
| 4   | `PARTIAL`  | Metadata **Goals** and **Reference implementation** explain technical reasons for stronger invariants, but there is no explicit PRD-delta approval table.                           |
| 5   | `PARTIAL`  | Headers, **Scope**, and **Reference implementation** distinguish target behavior from R1, but normative requirements and POC choices remain interleaved.                             |
| 6   | `CONFLICT` | The proposal does not preserve all PRD non-goals and deferred V2 items, particularly the no-additional-task-state simplification.                                                   |
| 7   | `PASS`     | API **Delete** and metadata **DELETE saga** require a stable durable action, context, tombstone, and reservation before `204`.                                                       |
| 8   | `PARTIAL`  | External cleanup is removed from the request path, but the five-second bound and required test are absent; unregister latency is not bounded.                                      |
| 9   | `PASS`     | Disabled soft delete plus `purgeRequested=true` creates immediate asynchronous hard cleanup in the target design.                                                                  |
| 10  | `PASS`     | Disabled soft delete plus `purgeRequested=false` removes registration and metadata while leaving files.                                                                            |
| 11  | `PASS`     | Enabled soft delete retains both purge values; `purgeRequested` becomes audit-only.                                                                                                 |
| 12  | `PASS`     | Retention zero is immediately GC-eligible and offers no reliable recovery window.                                                                                                  |
| 13  | `PARTIAL`  | The DELETE saga handles ambiguity safely, but lost-response retry has no stable request-id/receipt contract; management discovery is required afterward.                           |
| 14  | `CONFLICT` | PRD §2.2 requires tables and views; both drafts explicitly support tables only.                                                                                                    |
| 15  | `CONFLICT` | PRD §2.2 defines independently tombstoned schema contents; the drafts exclude cascade and provide no partial schema-drop semantics.                                                 |

### 2. Tombstone identity, lifecycle, and concurrency

| #   | Status             | Evidence and finding                                                                                                                                                        |
| --: | ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 16  | `PASS`             | Metadata **Metadata model** and **External hard cleanup** retain the metadata root, UUID, identifier, FileIO reconstruction reference, progress, and checkpoint.            |
| 17  | `PASS`             | Every accepted drop receives a new opaque immutable `deletion_id`.                                                                                                          |
| 18  | `PASS`             | Mutations use deletion ID, source table ID, revision, and tombstone-generation predicates rather than the reusable name.                                                    |
| 19  | `PASS`             | `active_name_key` remains held throughout `DELETED`, `PURGING`, failed cleanup, and reconciliation.                                                                         |
| 20  | `PASS`             | Same-name create or register is rejected with `409`.                                                                                                                        |
| 21  | `PASS`             | Reservation clears transactionally only on `RESTORED` or exact successful `PURGED` finalization.                                                                            |
| 22  | `PASS`             | The root and related metadata rows are stamped with the same deletion generation.                                                                                           |
| 23  | `PASS`             | Legacy metadata GC must exclude action-managed rows, and configuration validation must preserve the retention floor.                                                        |
| 24  | `PASS`             | UNDROP restores the original `table_id`, D1-scoped related rows, and saved Iceberg metadata location.                                                                        |
| 25  | `PASS`             | Public, cleanup, internal saga, retry, reconciliation, and terminal transitions are enumerated.                                                                             |
| 26  | `PASS`             | Both drafts consistently use `DELETED`, `RESTORED`, `PURGING`, and `PURGED`; neither persists `PURGE_PENDING` or exposes `RESTORING`.                                        |
| 27  | `PASS`             | UNDROP and GC claim the same action revision and stable-context boundary.                                                                                                   |
| 28  | `PASS`             | Multiple schedulers may inspect candidates, but only one can CAS an action into a job.                                                                                       |
| 29  | `PASS`             | A stale epoch cannot update progress, hard-delete metadata, or commit `PURGED`; already-issued I/O is constrained to immutable old-generation targets.                      |
| 30  | `PASS`             | Durable pending jobs are reclaimable after restart.                                                                                                                         |
| 31  | `PASS`             | Restart uses immutable context and durable progress. The checkpoint representation remains implementation work, but the safety invariant is explicit.                       |
| 32  | `PASS`             | External-success/finalization failure remains nonterminal; uncertain proof requires retry or manual repair, never false `PURGED`.                                           |
| 33  | `PARTIAL`          | Retry is exact-generation-safe, but permanent deletion on versioned stores and exact-target checkpointing remain unresolved.                                               |
| 34  | `PARTIAL`          | Failed cleanup is durable and sanitized, but there is no concrete authenticated operator status or redrive surface.                                                        |
| 35  | `PRODUCT DECISION` | Linked redrive versus status plus manual storage repair is proposed but not approved.                                                                                        |

### 3. UNDROP, retention, and deployment

| #   | Status             | Evidence and finding                                                                                                                                             |
| --: | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 36  | `PASS`             | Discovery returns D1; UNDROP addresses D1 explicitly.                                                                                                             |
| 37  | `PASS`             | A discovery read is required to obtain the deletion ID and ETag.                                                                                                  |
| 38  | `PASS`             | GET returns the immutable ID, strong action ETag, and `Cache-Control: private, no-store`.                                                                          |
| 39  | `PASS`             | Exact `If-Match` is mandatory.                                                                                                                                    |
| 40  | `PARTIAL`          | Missing and stale validators are defined, but wildcard and validator-list behavior is not explicitly enumerated.                                                 |
| 41  | `PASS`             | `accepted_restore_etag` plus the retained receipt makes lost-success replay safe.                                                                                 |
| 42  | `PASS`             | Successful UNDROP returns the ordinary live Iceberg table response.                                                                                                |
| 43  | `CONFLICT`         | The proposal is internally precise, but the PRD specifies `409` for running or failed cleanup; the proposal returns `410` for all `PURGING`, including failed items. |
| 44  | `PASS`             | An out-of-band object occupying the name returns `409` and is never overwritten.                                                                                  |
| 45  | `PARTIAL`          | Parent conflict is defined, but catalog recreation, credential rotation, and incompatible storage changes are not fully handled.                                 |
| 46  | `MISSING`          | There is no UNDROP integrity policy for metadata that is readable while manifests or data files have disappeared out of band.                                    |
| 47  | `MISSING`          | The drafts do not explicitly require current authorization to be reevaluated against the exact deletion generation.                                              |
| 48  | `CONFLICT`         | The PRD reuses `DROP_TABLE`; the API draft tentatively makes management operations admin-only.                                                                    |
| 49  | `PASS`             | `retention_expires_at` is fixed at delete time.                                                                                                                    |
| 50  | `PASS`             | Later configuration changes affect only later drops.                                                                                                              |
| 51  | `N/A`              | The actual PRD also persists `retention_expires_at`; it does not require recomputing it from generic metadata-GC configuration.                                   |
| 52  | `PARTIAL`          | A metadata-GC floor is asserted, but the formula, migration behavior, and invalid-configuration failure mode are absent.                                          |
| 53  | `CONFLICT`         | UNDROP uses `server_now < retention_expires_at` and GC uses `<=`; the PRD scheduler says `< now()`. The inclusive proposal closes the equality gap but requires explicit PRD-delta approval. |
| 54  | `PARTIAL`          | The 0–90-day range is stated, but invalid startup or reload behavior is not defined.                                                                               |
| 55  | `CONFLICT`         | PRD §3.3 requires standalone and auxiliary operation. Current R1 is auxiliary-only, and the drafts provide no standalone bridge.                                  |
| 56  | `PARTIAL`          | No supported non-database per-table force-cleanup operation is defined.                                                                                            |
| 57  | `PRODUCT DECISION` | The PRD permits direct DB modification, but that bypasses revision, ETag, and audit invariants unless a safe procedure is specified.                               |

### 4. Cleanup execution, operations, and audit

| #   | Status     | Evidence and finding                                                                                                                                                           |
| --: | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 58  | `PARTIAL`  | Bounded batches and streaming exist, but suitability for approximately 500,000 files is unproven and checkpoint storage is unresolved.                                       |
| 59  | `PARTIAL`  | Memory and concurrency are bounded, but no fairness quantum prevents a huge table from occupying a worker or dominating the shared delete pool.                              |
| 60  | `PARTIAL`  | Durable backoff and limits exist, but the backoff function and retryable/permanent error taxonomy are unspecified.                                                            |
| 61  | `CONFLICT` | The question and PRD assume restart from only the tombstone; the proposal requires a durable action, context, checkpoint, and job. The safer replacement is explicit but needs PRD-delta approval. |
| 62  | `PASS`     | The design explains why a job header is needed for batch ownership, lease/reclaim, aggregate progress, and retry lineage.                                                     |
| 63  | `PASS`     | The job is coordination state, not a parallel table lifecycle; only the action can become `PURGED`.                                                                           |
| 64  | `PARTIAL`  | States and counts are stored, but no concrete operator inspection or repair endpoint exists.                                                                                  |
| 65  | `PARTIAL`  | Errors are bounded to 2,048 characters and must be sanitized, but redaction rules and tests are absent.                                                                       |
| 66  | `CONFLICT` | R1 documentation claims heartbeat-time credential refresh, but current code only reuses FileIO properties snapshotted at enqueue; the new drafts do not define re-resolution. |
| 67  | `PASS`     | The proposed append-only audit covers delete, claim, retry, failure, restore, purge, reconciliation, and receipt expiry.                                                      |
| 68  | `PARTIAL`  | The action stores original `purgeRequested`, but the audit schema does not explicitly copy it into each retained audit history.                                              |
| 69  | `PASS`     | Credentials, tokens, raw FileIO properties, metadata payloads, and authorization material are excluded.                                                                      |
| 70  | `MISSING`  | The new drafts contain no metrics contract matching the PRD's required metrics.                                                                                               |
| 71  | `PARTIAL`  | Durable fields can calculate queue age, retries, and failure counts, but no metric names, endpoint, or alert contract exposes them.                                           |
| 72  | `PASS`     | Request and correlation IDs connect DELETE, deletion action, purge job, and lifecycle audit.                                                                                  |

### 5. Test sufficiency

| #   | Status    | Evidence and finding                                                                                                                                       |
| --: | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 73  | `PARTIAL` | Generic race and restart tests are mentioned, but the PRD's small-table concurrency and large-file tiers are not defined.                                 |
| 74  | `MISSING` | No explicit 6,000-table, 30-concurrent-drop, approximately 50,000-file, or approximately 500,000-file scenarios.                                          |
| 75  | `MISSING` | No requirement that every large-table DELETE test assert completion within five seconds.                                                                  |
| 76  | `MISSING` | No explicit verification of data files, manifests, manifest lists, and metadata JSON deletion.                                                            |
| 77  | `PARTIAL` | Drop, UNDROP, expiry, reservation, and re-drop appear; list/load, repeated DELETE, recreate, and retention-zero assertions are incomplete.                 |
| 78  | `PARTIAL` | Partial failure is covered conceptually, but throttling, network or 500 errors, and permission-error injection are not enumerated.                         |
| 79  | `PARTIAL` | Restart, reclaim, and race semantics exist, but explicit multiple-scheduler and durable-retry cases are incomplete.                                       |
| 80  | `PARTIAL` | Persistent transitions are defined, but the plan does not require a storage assertion for every transition.                                               |
| 81  | `MISSING` | The PRD's authorization, cache, and credential-vending feature combinations are absent.                                                                   |
| 82  | `MISSING` | Medium pre-merge and large weekly execution assignments are absent.                                                                                       |
| 83  | `PARTIAL` | Only confirmed success may purge is normative, but no explicit test proves that failed cleanup never finalizes.                                           |
| 84  | `PASS`    | Same-name reuse and re-drop are explicitly tested using exact deletion-generation predicates.                                                            |

## Blocking gap tracker

This tracker is the working queue for resolving the review. Its state progression is
`OPEN -> DECIDED -> DOCUMENTED -> VERIFIED`:

- `DECIDED` means the responsible product or technical owner approved one resolution;
- `DOCUMENTED` means every affected normative source incorporates that resolution; and
- `VERIFIED` means the matrix was rescored and the required test or review evidence exists.

A gap closes only when the decision, invariant, error behavior, implementation bridge, and tests
are recorded in the target sources.

| Gap | Questions | Owner | Target sources | Blocking issue and proposed direction | Closure condition | Status |
| --- | --------- | ----- | -------------- | ------------------------------------- | ----------------- | ------ |
| G1 | 14–15 | Product | PRD; API; metadata | The PRD includes views and schema cascade, while the drafts are table-only. Keep this document table-specific, but obtain a PRD scope amendment or add separately scoped view/cascade requirements. | Release scope and follow-on ownership are explicit; Q14–15 no longer conflict. | `OPEN` |
| G2 | 3–6, 61–63 | Product + architecture | PRD delta; metadata; API | The action/context/job model supersedes the PRD's tombstone-only model. Map every added record to a correctness invariant and record approval. | The model is approved, normative versus POC choices are labeled, and Q3–6/61 are rescored. | `OPEN` |
| G3 | 40, 43, 47–48 | API + security | PRD delta; API | Conditional request grammar, authorization, and `409` versus `410` differ. Preserve deletion ID plus ETag; decide authorization and approve the irreversible `PURGING => 410` boundary if retained. | All validators, privileges, exact-generation checks, and HTTP outcomes are normative and tested. | `OPEN` |
| G4 | 52–57 | Runtime + storage | API; metadata; deployment docs | Standalone support, validation, metadata-GC floor, and force cleanup are incomplete. Define one deployment-independent store, validation rules, and an audited force-expire operation. | Every G4 sub-item below is documented and has a validation or integration test. | `OPEN` |
| G5 | 13, 33, 45–46, 66 | Storage + security | API; metadata | Lost DELETE responses, external loss, versioned storage, drift, and credentials lack complete replay rules. Define stable request correlation, recovery validation, credential re-resolution, and exact checkpoints. | Every external side-effect boundary has a deterministic replay/repair result and crash test. | `OPEN` |
| G6 | 34–35, 57, 64–65 | Operations + security | API; metadata | Permanently failed items lack an approved workflow. Define read-only inspection, then deletion-ID-scoped redrive or repair with authorization, ETag, audit, and fencing. | Operators can inspect and safely resolve an exhausted item without direct unsafe row edits. | `OPEN` |
| G7 | 67–71 | Observability | API; metadata; operations docs | Audit is strong, but metrics, alerts, sanitization, and operator exposure are incomplete. Add PRD metrics plus queue age, retries, lease reclaim, restore outcomes, and redaction rules. | Required metrics/events are named, bounded, secret-safe, and mapped to tests and alerts. | `OPEN` |
| G8 | 1, 8, 58–60, 73–84 | QA + performance | API; metadata; test plan | The PRD acceptance program is absent. Add timing, file tiers, feature combinations, failure injection, restart, multi-replica races, and storage verification. | The complete PRD matrix is assigned to pre-merge or weekly suites with persistent assertions. | `OPEN` |
| G9 | 7, 19, 21, 29–34, 55, 66 | Implementation | Metadata; implementation plan | R1 does not satisfy several target invariants. Reuse only worker mechanics; specify durable enqueue, failed-name reservation, epoch checks, credential refresh, and strict success evidence. | Every known R1 delta has a migration task and a target invariant test. | `OPEN` |

### Bundled-gap closure checklists

G4 closes only when all of the following are resolved:

- G4a: standalone and auxiliary deployments use equivalent durable lifecycle semantics;
- G4b: the metadata-GC retention-floor formula and migration behavior are explicit;
- G4c: invalid startup and dynamic-reload configuration behavior is defined; and
- G4d: per-generation immediate cleanup is audited and does not require unsupported direct DB edits.

G5 closes only when all of the following are resolved:

- G5a: a lost DELETE response has stable request correlation and deterministic discovery/replay;
- G5b: UNDROP validates the saved metadata graph and defines outcomes for partial out-of-band loss;
- G5c: workers re-resolve protected credentials without persisting or exposing secrets; and
- G5d: versioned-object hard delete has an exact, bounded, restart-safe target checkpoint.

G8 closes only when all of the following are resolved:

- G8a: 6,000-table, 30-concurrent-drop, 50,000-file, and 500,000-file workloads exist;
- G8b: every large DELETE asserts the five-second response bound;
- G8c: object-store verification covers data, manifests, manifest lists, and metadata JSON;
- G8d: throttling, service errors, permission failures, and partial deletes are injected;
- G8e: restart, reclaim, stale-worker, multi-scheduler, and UNDROP-versus-GC races are tested; and
- G8f: feature combinations and pre-merge versus weekly ownership are assigned.

## Current R1 implementation deltas

Current R1 is useful infrastructure, but it does not implement the target lifecycle:

- asynchronous purge requires `X-Gravitino-Async-Purge: true`; without it purge remains
  synchronous;
- standalone mode falls back to synchronous purge;
- the request unregisters the table before inserting its cleanup job, leaving a crash gap;
- only `PENDING` and `RUNNING` jobs reserve the name, so `FAILED` releases it;
- a stale worker is fenced from database completion but may continue physical deletion;
- FileIO properties are snapshotted at enqueue, and heartbeat does not refresh credentials;
- some deletion failures can be suppressed, so `SUCCEEDED` is not sufficient proof of complete
  removal; and
- there is no deletion generation, original Gravitino table ID, UNDROP, lifecycle audit, or
  generation-predicated metadata finalization.

These are implementation-bridge findings, not reasons to weaken the normative target invariants.

## Product decisions required

The following decisions block convergence:

1. Approve the advanced durable lifecycle/job model, or constrain the design back to the PRD's
   tombstone-only approach.
2. Decide whether V1 includes views and schema cascade, and choose the public UNDROP contract:
   `DROP_TABLE` versus admin-only, plus PRD `409` versus proposed `410` once purge owns the action.
3. Define operator and compliance behavior: force cleanup, failed-item redrive/manual repair,
   versioned-store hard-delete meaning, and receipt/audit retention.

## Required test package

At minimum, the design must require:

- approximately 6,000 tables and 30 concurrent drops;
- medium and large tables around 50,000 and 500,000 files;
- `DELETE < 5s` assertions at every table size;
- direct verification of data files, manifests, manifest lists, and metadata JSON;
- S3 or GCS throttling, network or 500 failures, permission failure, and partial cleanup;
- restart before cleanup, during cleanup, and after external success but before finalization;
- two schedulers, lease reclaim, stale-worker completion, and UNDROP versus GC;
- persistent-state assertions for every transition;
- all required authorization, cache, and credential-vending combinations;
- medium pre-merge and large weekly execution; and
- proof that failed cleanup never silently reaches `PURGED`.

## Resolution order

Resolve the gaps in this order:

1. **G2 — authoritative architecture.** Until the action/context/job model is approved, later API
   and operational details have no stable storage contract.
2. **G1 and G3 — product scope and public semantics.** These decide what V1 promises and what
   clients may rely on.
3. **G4 through G7 — deployment, recovery, operations, and observability.** These close the
   safety-critical failure paths.
4. **G9 — R1 migration bridge.** Map current mechanics into the approved target without inheriting
   known gaps.
5. **G8 — acceptance matrix.** Make every approved invariant executable and release-gating.

The document may move from `INSUFFICIENT` to `SUFFICIENT WITH REQUIRED EDITS` only after all
`CONFLICT` and safety-critical `MISSING` results are closed. It reaches `SUFFICIENT` only when every
PRD requirement has an explicit normative treatment and test, with no unresolved correctness
conflict.
