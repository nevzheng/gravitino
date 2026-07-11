<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements. See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership. The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied. See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Fault Domains & Error Taxonomy — Open Decisions

> Working decision log for the initiative tracked in
> [nevzheng/gravitino#3](https://github.com/nevzheng/gravitino/issues/3).
> Format: each question states the **problem** and the **options**; decisions are
> deferred and recorded in the **Decisions to date** section as they are made.
> Companions: [design doc](gravitino-fault-domains-error-taxonomy.md),
> [survey report](gravitino-fault-domains-survey-report.md),
> [flatten-sites appendix](gravitino-fault-domains-flatten-sites-appendix.md).

---

## Decisions to date

| Q | Decision | Date | Notes |
|---|---|---|---|
| Q3 | **No compensation.** The trajectory for metadata dual-writes is transactional (ACID) semantics; from the end-user perspective operations will be transactional. Interim behavior: never silent success, report typed failures (`PartialApply`/`AmbiguousCommit`); do not build compensating-write machinery that the transactional work would discard. | 2026-07-11 | Supersedes the survey report's compensate-then-PartialApply proposal (D3/B5). The silent-success bug fix stands (return an error, not success); Ranger commit-ordering question remains open under Q3a. |
| Q2 (direction) | **Adopt gRPC conventions** for the error model; the exact wire mechanics remain open below. | 2026-07-11 | Consistent with the design doc's Solution Investigation (Option C). |

All other questions: **OPEN**.

---

## Q1. Taxonomy placement

**Problem.** New taxonomy types must be visible to three parties at once: connectors
(throw them), the `IsolatedClassLoader` (must share, not clone them, or `instanceof`
breaks), and clients (rehydrate them). Wherever they live is therefore de-facto shared
vocabulary; there is no truly "internal" staging area in this architecture — even
core-internal types physically live in a shared artifact, so the API-surface
conversation happens either way.

**Options.**
- **(a) Extend `api/.../exceptions`** next to the existing 72 classes. All three
  visibility properties hold automatically; clients already rehydrate types from this
  package by name; addition is small (≈1 interface + ~6 classes covering the
  infrastructure side — the caller-error side already exists). Cost: published API —
  renames after release are compat events; Apache API review will scrutinize.
  Mitigation: `@Evolving` stability annotation, minimal surface.
- **(b) New module** (e.g. `gravitino-errors`) as incubation area, graduate to `api/`
  later. Buys iteration room; costs a new dependency edge for every connector, client,
  and aux gateway, and fragments the vocabulary across three artifacts.
- **(c) Core-internal first.** Fastest, but clients cannot rehydrate → the taxonomy
  stops at the server edge, deferring most of the client-side payoff; and per the
  problem statement, still a shared-artifact change in practice.

## Q2. Wire mechanics for the gRPC-convention error model

**Direction decided: adopt gRPC conventions** (canonical codes; `ErrorInfo`-style
`{reason, domain, metadata}`; `RetryInfo`-style retry hints). Open: how the existing
`ErrorResponse` evolves to carry them.

**Problem.** `ErrorResponse` is `{code, type, message, stack}`; ~20 client switch sites
`default:` to a generic error; deployed clients must not break. Verified facts that
bound the risk: client-java disables `FAIL_ON_UNKNOWN_PROPERTIES`
(`ObjectMapperProvider.java:42`) and client-python uses dataclasses-json (ignores
unknown keys) — so additive fields do not break existing SDKs. Clients dispatch on the
body `code`, not the HTTP status line, so status-code corrections mainly affect raw
curl/LB integrations. Gravitino already has Accept-header API versioning
(`VersioningFilter`) if a versioned envelope were wanted.

**Options.**
- **(a) Additive evolution:** optional `status` (canonical code name),
  `errorInfo{reason,domain,metadata}`, `retryInfo`, `causeChain`, `correlationId`
  fields + new `ErrorConstants` codes; status-code corrections (500→502/503/504)
  gated by a server config flag, default-off for one release while clients gain
  consumption support. One shape that gets richer; no permanent dual-shape burden.
  Natural first slice: status-code corrections alone (most of the operator value,
  none of the payload work).
- **(b) Versioned v2 error envelope** via the existing Accept-version machinery.
  Cleanest contract; but old clients never send the new version, so two shapes must be
  maintained indefinitely, and every resource/mapper needs dual paths.
- **(c) Status codes only, defer payload.** Smallest upstream PR, immediate
  alerting value; clients still cannot machine-read retriability, so the client
  phases stay blocked. (Note: this is also just the first slice of (a).)

## Q3. Dual-write failure semantics — **DECIDED: no compensation**

**Problem.** Every create/drop/alter is a connector-write plus a store-write with no
transaction across them. Today a store failure after a successful connector write
yields silent success (`TableOperationDispatcher.java:688-696`) or a generic 500 with
an orphaned backend object. The Ranger post-commit policy push is the same defect on
the control plane.

**Decision (2026-07-11).** No compensating writes. The end-state is **transactional
(ACID) semantics** for metadata operations — from the end user's perspective the
operation will be transactional. Interim contract: never report success when the
dual-write did not fully apply; report typed `PartialApply`/`AmbiguousCommit` failures;
leave reconciliation to the transactional design rather than building interim
compensation/outbox machinery that it would discard.

**Q3a (still open) — Ranger/authz commit ordering.** Push-before-commit (`strict`
consistency: Ranger-down blocks grants) vs commit-then-push (availability: grant
durable, push failure reported as `PartialApply`) vs configurable per deployment. This
interacts with the transactional design and can be deferred to it.

## Q4. Sequencing — what ships first?

**Problem.** Two forces pull in opposite directions. Credibility: the 8 standalone bug
fixes and Phase 0 (server safety nets) need zero design consensus, fix things any
maintainer welcomes, and give #11982 concrete code. Coherence: the wire contract is
the vocabulary every other layer (server registry, converters, clients) targets;
building downstream layers before it means rework, and per Q1 there is no ordering
that avoids the public-API conversation — only orderings that have it deliberately or
accidentally.

**Options.**
- **(a) Bugs + Phase 0 first**, wire contract as the first consensus piece, then
  server registry → store converters → adapter SPI → clients → transactional
  dual-write design → bulkheads.
- **(b) Wire contract first** (survey report's ordering): everything downstream
  migrates incrementally against a stable vocabulary; the very first PR requires
  maintainer consensus.
- **(c) Bugs only until upstream buy-in materializes** on #11982; design work waits.

## Q5. testConnection 200-inversion

**Problem.** Expected failures return HTTP 200 with an embedded error body
(`ExceptionHandlers.handleTestConnectionException`), inverting the wire contract;
callers must parse bodies to detect failure. Fixing it changes observable behavior for
anyone parsing the embedded body.

**Options.** (a) fix in place behind the same classification config flag; (b) v2
endpoint, deprecate old behavior; (c) leave as documented quirk.

## Q6. Auto-retry ownership for `Conflict`-class store transactions

**Problem.** DB serialization failures/deadlocks (40001/1205/1213) are retry-safe for
pure store transactions, but never safe when a connector side effect shares the
operation. Interacts with the Q3 transactional trajectory.

**Options.** (a) core auto-retries pure store transactions with bounded attempts,
surfaces `Conflict` otherwise; (b) no auto-retry anywhere — always surface `Conflict`
to the client; (c) defer entirely to the transactional design.

## Q7. Slow-dependency defense (bulkheads)

**Problem.** A hung backend occupies shared Jetty workers and LockManager paths; one
slow HMS degrades the whole server. Classification alone cannot help if no thread is
free to emit the 503.

**Options.** (a) deadlines on `doWithCatalog` (per-provider configurable timeout);
(b) per-catalog thread pools (true isolation, higher complexity); (c) both, deadlines
first.

## Q8. Event-listener failure policy

**Problem.** Pre-event listeners are fail-open except `ForbiddenException` vetoes;
async listeners drop events on queue-full, silently. Some listeners are enforcement
(must not fail open), others observability (may drop with a metric).

**Options.** (a) per-listener fail-open/fail-closed config + drop counters wired to
health; (b) keep global fail-open, add metrics only; (c) classify listeners into two
declared tiers with fixed policies.

## Q9. Health semantics for embedded aux services and pollers

**Problem.** `/health` reflects the main server; an embedded Iceberg REST aux service
can be dead, or `EntityChangeLogPoller` silently stale (stale caches on multi-node),
while health reports OK.

**Options.** (a) per-component health tree (main, aux services, poller, listener
queues) with liveness = process, readiness = main; (b) fold aux/poller state into the
single readiness bit; (c) separate health endpoints per aux service only.

## Q10. Taxonomy → dialect projections (Gravitino REST / Iceberg / Lance / OpenLineage / errno)

**Problem.** Four inbound wire dialects plus FUSE's errno space must express the same
classified failure with equivalent semantics; today N=2 has already drifted and N=4 is
the trajectory.

**Options.** (a) one registry, N renderers, conformance tests asserting equivalence;
(b) per-gateway independent mappings with review discipline; (c) canonical dialect
(Gravitino REST) + documented translation tables for the rest.

## Q11. Stack-trace exposure

**Problem.** `ErrorResponse.stack[]` ships full server stack traces to any caller
(including 401s) — an information leak that doubles as the only (machine-unreadable)
cause channel.

**Options.** (a) redact by default, operator debug flag to restore, add structured
`causeChain[{type,message}]` as the machine-readable replacement; (b) redact only on
auth errors; (c) keep, document as trusted-network assumption.

## Q12. Enforcement of the no-bare-flatten rule

**Problem.** 762 sites need reclassification; without CI enforcement the count grows
back. A full-repo gate on day one blocks unrelated PRs; no gate invites regression.

**Options.** (a) ArchUnit/lint rule as a ratchet — enforce on changed files from day
one, full-repo gate after Phase 2; (b) full gate immediately with a baseline exemption
file; (c) advisory-only report in CI.
