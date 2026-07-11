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

# Design: Fault Domains and Error Taxonomy for Apache Gravitino

> Status: DRAFT for discussion — incorporates the 11-subsystem fault-domain survey
> (2026-07-11); headline claims spot-verified against source (file:line cited inline).
> The full workflow verification pass later confirmed 9/12 load-bearing claims and
> partially-confirmed 3 (no refutations).
> Companions: [survey report](gravitino-fault-domains-survey-report.md) (verified
> fault-domain model, refined 9-category taxonomy incl. `AmbiguousCommit`/`PartialApply`/
> `Overloaded`) and [flatten-sites appendix](gravitino-fault-domains-flatten-sites-appendix.md)
> (**all 1,332 sites enumerated**: file:line, what each wraps, boundary, proposed code —
> 570 judged fine as-is, 762 to reclassify; worst boundary: core→metadata-DB with 262).
> Related: discussion [apache/gravitino#11982](https://github.com/apache/gravitino/discussions/11982),
> issue #11943, PR #11959.

---

## Background

Gravitino is a **gateway**: it federates heterogeneous catalog backends (Hive Metastore,
JDBC databases, Iceberg/Paimon/Hudi catalogs, Kafka, object stores) behind a unified
metadata API consumed by clients, query engines, and UIs. A gateway's core error-handling
obligation is to tell its callers *whose* fault a failure is and *whether retrying can help*.
Gravitino currently cannot do either:

- **Adapter flattening.** Catalog adapters routinely do
  `catch (Exception e) { throw new RuntimeException(e); }` — exactly 127 such throw
  sites in `catalogs/*/src/main` (verified), a substantial fraction wrapping
  *entity-store* IOExceptions rather than backend dialects. A backend being *down*
  (retriable, not our fault) is indistinguishable from a Gravitino *bug* (not retriable,
  our fault) — both surface as HTTP 500. Converter coverage is trimodal — JDBC
  (`*ExceptionConverter`), Hive (`HiveExceptionConverter`), Glue
  (`GlueExceptionConverter`), plus contrib ClickHouse/Hologres; Iceberg-operational,
  Paimon, Hudi, Kafka, Fileset, and Model adapters have none.
- **Server-side mapping is per-entity and incomplete.**
  `server/src/main/java/org/apache/gravitino/server/web/rest/ExceptionHandlers.java`
  contains 25 per-entity handlers with 114 `instanceof` branches that drift
  independently. `ConnectionFailedException → 502` fires from exactly one handler
  (catalog); the testConnection path returns HTTP **200 with an embedded error body**;
  every other handler falls through to 500. There is no `ExceptionMapper<Throwable>`
  safety net (Jetty serves HTML error pages with `setShowStacks(true)`). `ErrorConstants`
  defines no retriable/transient code.
- **Clients get nothing actionable.** `clients/client-java/.../ErrorHandlers.java` has no
  retry, no backoff, no `Retry-After` handling; `INTERNAL_ERROR` reverse-maps to a bare
  `RuntimeException`.
- **Every backend ships a retriability signal that we discard**: JDBC
  `SQLTransient*`/`SQLRecoverable*` and SQLState class `08`, Iceberg REST 5xx + transport
  `IOException` causes, Hive `TTransportException`, Kafka `RetriableException`.

The consequence for operators: a Prometheus alert on Gravitino 500s cannot distinguish
"MySQL backend is restarting" from "Gravitino has a bug". The consequence for users:
clients either never retry (missing easy recoveries) or blindly retry (amplifying
outages and, in the worst case, double-committing).

### Survey: where errors get flattened (per subsystem, main sources only)

An 11-subsystem survey (2026-07-11) counted `throw new RuntimeException` + broad
`catch (Exception` sites and audited every error converter. Repo-wide: **508 literal
`RuntimeException` throws across 158 files; ~1,260 flatten/broad-catch sites total.**

| Subsystem | Flatten sites | Converters present | Worst confirmed finding |
|---|---|---|---|
| `core/` | **485** | 3 metadata-DB `SQLExceptionConverter`s (H2/MySQL/PG) — each maps exactly **one** condition (duplicate key); everything else → `IOException` → re-wrapped `RuntimeException` | `TableOperationDispatcher` swallows a failed `store.put` after backend create and **returns success** (silent metadata divergence); PG converter switches on a nullable SQLState → NPE masks the real error |
| clients | 198 | `ErrorHandlers.java` (1,444 lines, 22 per-domain handlers) | code 1007 → `ConnectionFailedException` only in `CatalogErrorHandler` (Python maps it in *all* domains — Java is behind Python); code 1011 dead in both; **zero `Retry-After` references** under `clients/`; HTTP status discarded once body parses |
| server + server-common | 168 | `ExceptionHandlers` (25 handlers, 114 instanceof branches) + 3 Json mappers | no `ExceptionMapper<Throwable>` safety net; `ConnectionFailedException`→502 only on catalog routes; JWKS/IdP outage (transient) reported as 401 bad-credentials; authz interceptor maps `NoSuchMetalakeException`→403 |
| aux gateways (iceberg/lance/lineage) | 144 | `IcebergExceptionMapper` (app-wide `@Provider`), `LanceExceptionMapper` (**`@Provider` registration dead** — never scanned) | Iceberg mapper uses **exact-class** lookup (`ex.getClass()`), so wrapper types (`RuntimeMetaException`, `UncheckedSQLException`) → 500; **commit-channel poisoning**: pre-commit connection-refused → 500, which Iceberg clients interpret as `CommitStateUnknown` |
| engine connectors | 103 | Trino `GravitinoErrorCode` (26 codes, all `ErrorType.EXTERNAL`) + per-method Spark/Flink catches | no "Gravitino unavailable" code in any engine; dead catch: Trino `createSchema` catches `TableAlreadyExistsException` but the client throws `SchemaAlreadyExistsException` (verified `CatalogConnectorMetadata.java:213`) |
| catalog-misc (fileset/kafka/model) | 51 | **none** | Kafka discards its own `RetriableException` marker; fileset/model `testConnection` are no-ops; conflict-on-rename flattened to 500 |
| catalog-hive-glue | 44 | `HiveExceptionConverter` (simple-name/keyword match), `GlueExceptionConverter` (typed) | Glue never consults AWS SDK `retryable()`/throttling/status; `SdkClientException` (DNS/timeout) escapes unconverted; Glue `testConnection` cannot detect a down endpoint |
| catalog-lakehouse | 31 | none for operational errors (Iceberg "converter" package is types-only) | typed `org.apache.iceberg.exceptions.*` carrying status + retriability flattened to `RuntimeException`; `CommitFailed` vs `CommitStateUnknown` both → 500 |
| authz plugins | 17 | `JdbcAuthorizationPlugin.toAuthorizationPluginException` (classifies nothing) | core commits entity store **before** the authz plugin call, no rollback/outbox: Ranger outage → 500 but the grant is already durable; `AuthorizationPluginException` has no server handler branch |
| catalog-jdbc | 13 | 7 `*ExceptionConverter`s (best coverage in repo) | PG/Hologres constructor-overload bug binds cause to a format-arg `(String, String)` overload — **drops the cause and can crash the converter**; JDBC `SQLTransient*`/`SQLRecoverable*` never consulted by any converter |
| api + common | 8 | `ErrorResponse` factories + `ErrorConstants` (13 codes) | none of the 72 exception classes declares its HTTP meaning; `ErrorResponse` = `{code, type, message, stack}` — **no slot for a retriable bit to survive serialization** |

The exhaustive enumeration pass (see the
[flatten-sites appendix](gravitino-fault-domains-flatten-sites-appendix.md)) finalized
the counts: **1,332 sites** total (row count == raw rg hit count in all 15 scopes),
of which **570 are legitimate as-is** (e.g. deliberate listener isolation) and **762
need reclassification**; the single worst boundary is core→metadata-DB (262 sites).

The load-bearing structural facts (each verified in-repo):
- `org.apache.gravitino.exceptions.*` (72 classes) is **shared across the
  `IsolatedClassLoader` boundary**, so `instanceof` survives — taxonomy is lost only
  because adapters choose to flatten.
- `ConnectionFailedException` → 502 exists but is honored on **catalog routes only**;
  the identical HMS-down during `loadTable` is 500.
- The metadata-DB error path is the least classified in the repo: converters map
  duplicate-key and nothing else, and the conversion gate runs only on write paths.

---

## Fault-Domain Model

The organizing model is **hexagonal architecture** (ports & adapters), with fault domains
as the unit of error-contract ownership. Two distinct axes matter and must not be
conflated:

- **Contract ownership**: who defines the error vocabulary at a port.
- **Failure correlation**: which components fail together (e.g. same JVM/process).

```
                         EXTERNAL USERS (fault domain: theirs)
                              │  ▲
              ┌───────────────┘  └──────────────────┐
              ▼                                     │
   ┌─ CLIENT DOMAIN ────────────────────────────────┴─┐
   │ client-java / client-python / CLI / GVFS /       │  own fault domain:
   │ Trino / Spark / Flink connectors                 │  version skew, retry storms,
   └───────────────┬──────────────────────────────────┘  serialization defects
                   ▼  (wire: Gravitino REST error contract)
   ┌─ SERVER (inbound-port) DOMAIN ───────────────────┐
   │ server/ REST + JAX-RS mappers                    │   sibling inbound ports,
   │ iceberg-rest-server (Iceberg REST spec errors) ──┼── separate CONTRACT domains,
   │ lance-rest-server / lineage / mcp-server         │   often the SAME process
   └───────────────┬──────────────────────────────────┘   fault domain (aux services)
                   ▼  (in-process: shared exception taxonomy)
   ┌─ GRAVITINO CORE DOMAIN ─────────────────────────┐
   │ core/: dispatchers, CatalogManager, EntityStore │──► metadata DB (MySQL/PG/H2)
   │ event bus, IsolatedClassLoader boundary         │    = core's OWN dependency
   └───────────────┬─────────────────────────────────┘
                   ▼  (SPI: CatalogOperations, classloader-isolated)
   ┌─ CONNECTOR DOMAINS (one template, N instances) ─┐
   │ hive │ glue │ jdbc-* │ iceberg │ paimon │ hudi  │
   │ kafka │ fileset │ model │ ...                   │
   └───────────────┬─────────────────────────────────┘
                   ▼  (backend-native protocols)
     EXTERNAL BACKENDS (fault domain: theirs — HMS, DBs, brokers, object stores)
```

### Rules

1. **Contracts attach to ports, not to the system.** Every fault-domain crossing is a
   deliberate translation into the contract of the port being crossed. Calling the
   Iceberg REST server directly returns Iceberg-spec errors unfiltered — that is *valid*,
   because the caller chose that port and that protocol defines its own error model.
   What is invalid is unfiltered-by-accident (`RuntimeException` leaking through a
   boundary that never declared it).
2. **Domain sovereignty.** Inside a connector domain, any exception style is fine.
   Classification is required only at the crossing into core.
3. **Hub-and-spoke translation.** N backend converters + M port mappers, never N×M.
   The hub is the shared error taxonomy (`org.apache.gravitino.exceptions.*`, which
   already crosses the `IsolatedClassLoader` boundary as a shared package, so
   `instanceof` survives).
4. **Aux gateways are separate contract domains but may share a process fault domain**
   with the core server (embedded auxiliary services). Their wire contracts stay
   protocol-native; their *classification* of backend failures must agree with the hub
   (the same backend outage must be "unavailable + retriable" on every port).

### Fault domains (D1–D10)

| # | Domain | One-line contract summary |
|---|---|---|
| D1 | External catalog backends (one template, N instances: HMS, JDBC DBs, Iceberg/Paimon/Hudi services, Kafka, object stores + STS) | Not ours; rich native transient/permanent/ambiguous signals exist here and nowhere downstream — classify at first catch or lose them. Caveat: fail-fast failures are per-backend, but a *hung* backend correlates through shared Jetty/LockManager threads → needs deadlines/bulkheads, not just classification |
| D2 | Catalog connector adapters (anti-corruption layer behind `IsolatedClassLoader`) | Converter coverage trimodal (JDBC/Hive/Glue + contrib); zero for iceberg-operational/paimon/hudi/kafka/fileset/model. The 127 throw-sites largely wrap *store* IOExceptions, so the fix is two-pronged: backend converter SPI + metadata-store wrap |
| D3 | Gravitino core (dispatch, locking, events, dual-write composition) | The layer that structurally *knows* which dependency it called and erases that knowledge (3 dispatcher funnels + ~70 manager wraps); becomes the classify-then-preserve backstop |
| D4 | Metadata persistence (`storage/relational` + pool + DB + change-log poller) | One **correlated** domain — when it fails, every entity and API fails together; converters classify only duplicate-key today; `/health` already knows the right answer on exactly one code path |
| D5 | Main REST server boundary (`ExceptionHandlers` + auth filters + wire vocabulary) | 25 drifting instanceof ladders → one declarative registry + `ExceptionMapper<Throwable>` + JSON Jetty error handler |
| D6 | Auxiliary inbound gateways (one template, three instances: Iceberg REST, Lance REST, lineage source) | Own wire dialects by design (valid, chosen ports); classification must agree with the hub; `IcebergExceptionMapper` is the in-repo reference implementation |
| D7 | Control-plane consumers (client-java/python, CLI, engine connectors, MCP server, web UIs) | Own fault domain: rehydration, retry policy (gRPC conventions: backoff + jitter + retry budget), version skew |
| D8 | Data-plane consumers & credential vending (GVFS Java/Python, FUSE, STS chain) | Failures land at *data time*, possibly mid-stream; FUSE needs a documented taxonomy→errno projection |
| D9 | Security sidecars (IdPs: JWKS/KDC; authz backends: Ranger) | IdP outage currently = 401 bad-credential; Ranger outage currently = 500 with the grant already durable |
| D10 | Autonomous internal actors (pollers, async listeners, sinks, job executors, cache refresh) | No request to fail — need a health contract ({last-success, failure counter, degraded flag} wired to `/health`), not an HTTP one; today they fail silently |

### Boundary contract table (current → proposed)

| # | Boundary | Current error flow (verified) | Proposed contract |
|---|---|---|---|
| B1 | Backends → connector adapters | Native dialects arrive with transient/permanent/ambiguous signals; JDBC/Hive/Glue(+contrib) convert some; the rest convert nothing; retriable markers discarded | Mandatory converter at first catch → taxonomy, cause preserved. Iceberg: `CommitFailedException`→`ABORTED`/Conflict (retriable), `CommitStateUnknownException`→`UNKNOWN`/AmbiguousCommit. Kafka: `RetriableException`→`UNAVAILABLE` (minus the `UnknownTopicOrPartition`→`NOT_FOUND` trap) |
| B2 | Connector adapters → core (SPI, classloader-isolated) | Exceptions classloader-shared so types survive; `OperationDispatcher.java:89,106,129` wraps all checked exceptions bare; `withClassLoader` preserves exactly one type | `doWithCatalog` = classification backstop: non-Gravitino escapee → `INTERNAL_DEP domain=catalog.<p>` (502); per-call deadline/bulkhead so a hung backend can't starve other catalogs; `InterruptedException` re-interrupts |
| B3 | Metadata DB → persistence adapter | Converters classify only duplicate-key; connection-broken/deadlock/pool-exhausted → opaque `IOException(se)`; cause-chain walked one level only | Full classification: 08*/pool-timeout → `UNAVAILABLE domain=core.storage`; 40001/40P01/1205/1213 → `ABORTED`/Conflict; 23xxx → typed constraints; post-COMMIT death → `UNKNOWN`/AmbiguousCommit. Full cause-chain walk everywhere |
| B4 | Persistence → core | ~70 `catch(IOException){throw new RuntimeException(ioe)}` manager sites → 500; Gravitino's own DB down is bug-shaped on every endpoint while `/health` says 503 | One shared wrap helper: transient → `UNAVAILABLE domain=core.storage` (503+`Retry-After`); else typed `MetadataStoreFailure`; `NoSuchEntityException` translated before leaving managers |
| B5 | Core dual-write (connector-write + store-write) | Store failure after successful connector write → silent success (`TableOperationDispatcher.java:688-696`) or generic 500 with orphaned backend objects | **Decided ([Q3](gravitino-fault-domains-open-decisions.md)): no interim compensation.** Never silent success; typed `PartialApply`/`AmbiguousCommit` naming committed backend state; end-state is transactional (ACID) dual-write semantics |
| B6 | Core → main REST server | 25 drifted instanceof ladders; `ConnectionFailedException`→502 from one handler only; testConnection returns HTTP 200 + embedded error body; unmatched → 500 + stack; Jetty HTML errors with stacks | Single declarative registry (instanceof-matched, the `IcebergExceptionMapper` pattern) + `ExceptionMapper<Throwable>` + JSON Jetty error handler; strict status projection per the canonical-code table |
| B7 | Main server → control-plane clients (wire) | `ErrorResponse{code,type,message,stack}`; no retriable bit; zero `Retry-After`; stack-trace leak; codes 1000/1100 client-only; 1011 dead | Additive `status`/`errorInfo{reason,domain,metadata}`/`retryInfo`/`causeChain`/`correlationId` (mechanics = [open decisions Q2](gravitino-fault-domains-open-decisions.md)); 503+`Retry-After` for dependency-down; **500 ⇒ Gravitino bug invariant**; stacks redacted by default |
| B8 | Clients → engines/applications | Java: 1002 → bare `RuntimeException` at 20 sites; 1007 typed only for catalogs (Python maps it everywhere); `TokenExpired` collapsed (kills auto-refresh); no retry logic | Typed exception hierarchy mirroring canonical codes; gRPC-convention retry (backoff+jitter, retry budget, honor `retryInfo`); TokenExpired → refresh + single replay |
| B9 | Engine connectors → engines (Trino/Spark/Flink) | Trino: 26 codes all `EXTERNAL`, no unavailable code, unanticipated exceptions escape untyped; Spark `tableExists` swallows `Forbidden` → `false` | Canonical codes → engine-native error types; add `GRAVITINO_SERVER_UNAVAILABLE`-class codes; retriable bit drives engine retry policy |
| B10 | External users → aux gateways (direct IRC/Lance/lineage calls) | Pass-through of protocol-native errors **by design (valid — chosen port)**; but exact-class mapping drops subclasses to 500; commit-channel poisoning; Lance mapper registration dead; lineage bare-429 | Keep wire dialects; align classification: instanceof matching, backend-down → 503 + `Retry-After`, pre-commit failures classified to true status so commit-500 stays reserved for genuinely-unknown outcomes; per-gateway taxonomy→dialect projection tables with conformance tests |
| B11 | IdPs (JWKS/KDC) → auth filters | Infrastructure failure → `UnauthorizedException` → 401 code 1011; clients re-auth in a loop against a down IdP | IdP infra failure → `UNAVAILABLE` (503+`Retry-After`); only real rejection → 401; `TokenExpired` keeps its wire type |
| B12 | Ranger → core (authz push) | `AuthorizationPluginException` for both outage and conflict; no server handler branch → 500; store may already be committed → silent policy drift; some paths swallow entirely | Ranger-down → `UNAVAILABLE domain=authz.ranger`; conflict → CallerError (409); post-commit push failure → typed `PartialApply` (no interim compensation; ordering strict-vs-available = open [Q3a](gravitino-fault-domains-open-decisions.md)) |
| B13 | STS/credential endpoints → vending → data-plane clients | Vend-time throttle/outage and mid-read token expiry surface as untyped data-plane errors; no errno discipline in FUSE | Vend failures classified (`UNAVAILABLE` on throttle → client backoff); `CredentialCache` early-refresh window; documented taxonomy→errno projection (UNAVAILABLE→EAGAIN/EIO, AuthError→EACCES, CallerError→ENOENT/EEXIST) |

Note on correlation vs contract: aux gateways embedded as auxiliary services share the
core server's **process** fault domain (JVM heap, thread pools) while owning separate
**contract** domains — health/readiness must reflect aux-service and poller state
(D10), and the two axes must not be conflated.

---

## Goals

1. **Explicit boundary contracts**: every fault-domain boundary has a declared error
   contract; no raw `RuntimeException` crosses a declared boundary unclassified.
2. **Dependency vs defect distinction**: at every port, a failed or unavailable backend
   is mechanically distinguishable from a Gravitino bug (status code + error payload),
   verifiable by curl.
3. **Machine-actionable retriability**: a client can decide retry / don't-retry / retry-
   whole-transaction without parsing message strings.
4. **Uniform connector template**: one shared converter contract that every catalog
   connector implements/uses, so a new connector inherits classification by default.
5. **Failure attribution**: error payloads name the fault domain that failed
   (`ErrorInfo.domain`-style), enabling per-domain alerting and SLOs.

## Non-Goals

1. **Changing aux-gateway wire protocols**: the Iceberg REST server keeps Iceberg-spec
   errors (pass-through is a valid, chosen contract); we align its *classification*, not
   its wire format.
2. **Server-side automatic retries**: the server classifies; the client decides. Auto-
   retry inside the gateway risks double-writes (see `CommitStateUnknownException`).
3. **Breaking existing error codes**: current `ErrorConstants` codes and response shape
   remain valid during a deprecation window; new fields are additive.
4. **Data-path (query execution) error semantics** in engines: Trino/Spark/Flink query
   *execution* failures stay engine-native; only Gravitino-metadata call failures are in
   scope.

---

## Solution Investigations

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| A. Status-quo++: keep patching per-entity `instanceof` ladders | No design work; incremental | N×M growth; each new entity/backend re-introduces the bug class; no retriability story | Rejected |
| B. Bespoke Gravitino taxonomy (classify-then-preserve, as first sketched in #11982): 5 buckets (CallerError / AuthError / DependencyUnavailable / DependencyFailure / InternalError) + `retriable` bit | Minimal, tailored to gateway role | Reinvents conventions; no ecosystem familiarity/tooling; every client must learn a one-off model | Subsumed by C |
| C. **Google API / gRPC error model** (`google.rpc.Code` canonical codes + `Status`-style payload with `ErrorInfo`/`RetryInfo` details) | Battle-tested at scale; canonical HTTP↔code mapping already defined; `ErrorInfo.domain` natively expresses fault domains; `RetryInfo` is the standard retriable bit; familiar to users of every Google/gRPC API | Larger code enum than strictly needed; discipline required in mapping tables | **Preferred direction** (adopt gRPC conventions; wire mechanics tracked in [open decisions Q2](gravitino-fault-domains-open-decisions.md)) |
| D. RFC 9457 `application/problem+json` | IETF standard for HTTP APIs | Defines an envelope, not a taxonomy — no canonical codes, no retriability convention; we'd still need B or C on top | Rejected (may borrow `type` URI idea for docs links) |

Option C strictly subsumes option B: the five buckets are a partition of the canonical
code enum (see mapping below), so we get B's simplicity in our internal model *and* C's
interoperability on the wire.

---

## Proposal

### Canonical codes (the hub)

Internal taxonomy hierarchy stays coarse (the five B-buckets as base classes /
interfaces), each carrying a canonical `google.rpc.Code`-style code that drives the wire
mapping:

| Bucket (internal) | Canonical code | HTTP | Retriable | Typical producers |
|---|---|---|---|---|
| CallerError | `INVALID_ARGUMENT` | 400 | no | validation failures |
| CallerError | `NOT_FOUND` | 404 | no | `NoSuchXxxException`; Kafka `UnknownTopicOrPartitionException` (backend says "retriable", it is not) |
| CallerError | `ALREADY_EXISTS` | 409 | no | `XxxAlreadyExistsException` |
| CallerError | `FAILED_PRECONDITION` | 400 | no | non-empty schema drop, `NotInUseException` |
| CallerError | `UNIMPLEMENTED` | 405/501 | no | `UnsupportedOperationException` on optional capability |
| AuthError | `UNAUTHENTICATED` | 401 | no | missing/expired credentials |
| AuthError | `PERMISSION_DENIED` | 403 | no | authz denial (incl. backend "Access denied") |
| Concurrency | `ABORTED` | 409 | retry the **whole transaction** | Iceberg `CommitFailedException` — never replay just the HTTP request |
| DependencyUnavailable | `UNAVAILABLE` | 503 + `Retry-After` | **yes** | HMS `TTransportException`; JDBC `SQLTransient*`/SQLState `08`; Iceberg REST 503/transport `IOException`; Kafka `RetriableException` (minus the 404 trap above) |
| DependencyUnavailable | `DEADLINE_EXCEEDED` | 504 | usually | backend call timeout |
| DependencyFailure | `INTERNAL` + `domain=<backend>` | 502 | no | backend returned a permanent, unclassifiable error (e.g. Hive `MetaException` catch-all) |
| InternalError | `INTERNAL` + `domain=gravitino` | 500 | no | *only* our bugs — the invariant that makes 500 alertable |
| Unknown outcome | `UNKNOWN` | 500 | **NEVER** | Iceberg `CommitStateUnknownException` — retry risks double-write; must surface as terminal |
| Quota | `RESOURCE_EXHAUSTED` | 429 | with backoff | rate limiting (lineage today) |

Notes:
- **502 vs 503**: HTTP distinguishes "backend broke" (502) from "backend unavailable,
  try later" (503); the canonical code + `domain` carries the same distinction in the
  payload so gRPC-style consumers don't need the HTTP nuance.
- **The 500 invariant** is the operational payoff: after this design, `HTTP 500 ⇒ file a
  Gravitino bug` becomes a true statement, and alerts can be scoped accordingly.

### Wire format (Gravitino REST port)

Additive extension of the existing `ErrorResponse` (legacy `code`/`type`/`message`
retained for the deprecation window):

```json
{
  "code": 1003,
  "type": "ConnectionFailedException",
  "message": "Failed to connect to Hive Metastore at thrift://hms:9083",
  "status": "UNAVAILABLE",
  "errorInfo": {
    "reason": "BACKEND_CONNECTION_FAILED",
    "domain": "catalog.hive",
    "metadata": { "catalog": "prod_hive", "backendHost": "hms:9083" }
  },
  "retryInfo": { "retryDelayMs": 5000 }
}
```

- `status`: canonical code name (string, stable enum).
- `errorInfo.domain`: **the fault domain that failed** — `catalog.<provider>`,
  `core.storage` (metadata DB), `core`, `server`, `authz.ranger`.
- `errorInfo.reason`: machine-readable reason within the domain (SCREAMING_SNAKE).
- `retryInfo`: present ⇔ retriable; clients honor it or fall back to `Retry-After`.

Extension point confirmed: `common/src/main/java/org/apache/gravitino/dto/responses/ErrorResponse.java`
currently carries exactly `{code, type, message, stack}` — the new fields are purely
additive, and `ErrorConstants` (13 codes today) gains no breaking change. The OpenAPI
spec (`docs/open-api/*.yaml`) gains optional fields on the shared error schema; validate
with `./gradlew :docs:build`.

**Changed-API note (old vs new):** no request/response field is removed or retyped.
The observable changes are (a) new optional response fields, and (b) corrected status
codes for classified dependency failures (some current 500s become 502/503/504 with the
same body shape plus new fields). Callers that switch on `code` are unaffected; callers
that treat any non-2xx as fatal are unaffected; only callers that specifically
pattern-match "500" for backend outages (a bug-workaround pattern) need migration.

### Connector template (spokes, backend side)

One shared entry point in the connector SPI path:

- `CatalogExceptionConverter` (shared, in the hub library): consults, in order —
  (1) backend *retriable markers* (`SQLTransient*`, Kafka `RetriableException`, transport
  exceptions), (2) backend-specific converter contributed by the connector,
  (3) conservative default = `INTERNAL` + `domain=catalog.<provider>` (502), never 500.
- Connectors keep raising whatever they like internally; the dispatcher applies the
  converter at the SPI boundary (single choke point instead of ~90 catch sites).

### Port mappers (spokes, user side)

- Gravitino REST: taxonomy → status + `ErrorResponse` above, via **one**
  `BaseExceptionMapper` + `ExceptionMapper<Throwable>` safety net (replacing fall-through
  paths of the per-entity ladders; ladders shrink to entity-specific 4xx niceties).
- Iceberg REST server: taxonomy → Iceberg-spec `ErrorResponse` (wire format unchanged;
  classification aligned).
- MCP server: taxonomy → MCP error objects.
- Trino connector: taxonomy → existing `GravitinoErrorCode` `TrinoException`s (already
  structured; needs only the retriable/unavailable codes added).

### Client domain (its own fault domain)

Clients follow gRPC retry conventions: retry only on `UNAVAILABLE`/`RESOURCE_EXHAUSTED`
(and idempotent `DEADLINE_EXCEEDED`), exponential backoff + jitter, honor
`retryInfo`/`Retry-After`, bounded **retry budget** (token bucket) so client fleets
cannot amplify a backend outage into a retry storm. `ABORTED` is surfaced to the
application (transaction-level retry). `UNKNOWN` is never auto-retried.

### User process

1. Operator misconfigures a catalog URI → `curl` returns 400 `INVALID_ARGUMENT`,
   `domain=catalog.hive`, no retry hint.
2. HMS restarts → same call returns 503 `UNAVAILABLE`, `Retry-After: 5`,
   `retryInfo.retryDelayMs=5000`; client-java retries transparently within its budget;
   Trino shows a retriable error code.
3. Gravitino NPEs → 500 `INTERNAL`, `domain=gravitino` → pager fires, bug filed. 500s no
   longer contain backend weather.
4. Metadata DB down → 503 `UNAVAILABLE`, `domain=core.storage` → operator knows the
   *whole gateway* is degraded, distinct from a single-catalog outage.

### Implementation process

```
backend error ──► connector (native) ──► CatalogExceptionConverter   ── hub taxonomy ──►
core dispatcher (annotates domain=catalog.<p>) ──► port mapper (REST / IRC / MCP / Trino)
──► wire contract of the chosen port ──► client SDK (retry policy) ──► user
```

Side-channel semantics (from the survey):
- **Event listeners** (B5) are already correctly isolated: async/post-event listener
  exceptions are caught, logged, and swallowed by `EventBus`; only a sync pre-event
  veto (`ForbiddenException`) may affect the caller. This is the reference pattern for
  "observer failures must not fail the operation."
- **Authorization plugins** (B4) are the opposite and need repair: core commits the
  entity store *first*, then invokes the plugin, so a Ranger outage yields HTTP 500
  while the Gravitino-side change is already durable — a retry re-runs the store write.
  Per the no-compensation decision ([open decisions Q3](gravitino-fault-domains-open-decisions.md)
  — the trajectory is transactional dual-write semantics), the honest interim contract
  is to return `UNKNOWN`/`PartialApply` (outcome uncertain / partially applied) rather
  than `INTERNAL`, and to classify Ranger-down as `UNAVAILABLE domain=authz.ranger`.
  Commit ordering (push-before-commit strict mode vs commit-then-push) stays open as Q3a.
- **Commit endpoints** (B10) have a poisoning hazard: Iceberg clients interpret a
  commit-500 as `CommitStateUnknown`. Any *pre-commit* failure (connection refused,
  auth, validation) that defaults to 500 therefore makes clients believe the table
  state is unknown. Pre-commit failures must be classified to their true status so the
  500 channel stays reserved for genuinely-unknown outcomes.

### Backward compatibility

- New `ErrorResponse` fields are additive; old clients ignore them.
- Status-code corrections (500→502/503/504 where classification applies) are the one
  observable behavior change; gated by a server config flag
  (`gravitino.server.error.classification.enabled`, default on in the release after
  introduction) for one release.
- `org.apache.gravitino.exceptions.*` classes remain; new base
  types/interfaces slot into the existing hierarchy.

---

## Task Breakdown

### Phase 0: Stop the bleeding (no API change)
- [ ] Hoist `ConnectionFailedException`/`Forbidden`/`Unauthorized` handling from per-entity ladders into `BaseExceptionHandler`
- [ ] Add JAX-RS `ExceptionMapper<Throwable>` safety net in `server/`
- [ ] Unit tests: every `org.apache.gravitino.exceptions.*` type maps to a non-500 where defined

### Phase 1: The hub
- [ ] Define canonical code enum + bucket base classes in the shared exceptions package (`api/`)
- [ ] Add `status`/`errorInfo`/`retryInfo` to `ErrorResponse` DTO in `common/` (additive)
- [ ] Update OpenAPI spec (`docs/open-api/*.yaml`) and validate with `./gradlew :docs:build`
- [ ] `CatalogExceptionConverter` skeleton with retriable-marker detection (JDBC/Thrift/Kafka/Iceberg-transport)

### Phase 2: The spokes (parallel per connector)
- [ ] Wire converter at the dispatcher SPI boundary in `core/`
- [ ] Iceberg connector converter (`handleRestException`) — reference implementation
- [ ] Kafka, Fileset, Paimon, Hudi, Model converters (parallel)
- [ ] Migrate JDBC/Hive converters to marker-first classification
- [ ] Align `iceberg-rest-server` `IcebergExceptionMapper` classification with hub
- [ ] Trino connector: add retriable/unavailable `GravitinoErrorCode`s

### Phase 3: Client domain
- [ ] client-java: honor `retryInfo`/`Retry-After`; bounded exponential backoff + retry budget
- [ ] client-python parity
- [ ] CLI: human-readable rendering of `domain`/`reason`/retry hints
- [ ] Integration tests (Docker): kill backend mid-call, assert 503+retryInfo end-to-end

### Standalone bug fixes surfaced by the survey (shippable independently, any order)
- [ ] Fix silent partial success: `TableOperationDispatcher` (and Schema/Topic siblings) must not return success when `store.put` fails after backend create (`core/.../catalog/TableOperationDispatcher.java:688-696`)
- [ ] Fix `PostgreSQLExceptionConverter` NPE: `switch` on nullable `getSQLState()` (`core/.../storage/relational/converters/PostgreSQLExceptionConverter.java:39`)
- [ ] Fix PG/Hologres catalog converter constructor-overload bug: `new XxxAlreadyExistsException(message, cause)` binds the `(String, Object...)` format overload, dropping the cause
- [ ] Fix Trino dead catch: `CatalogConnectorMetadata.createSchema` catches `TableAlreadyExistsException`; client throws `SchemaAlreadyExistsException` (`trino-connector/.../CatalogConnectorMetadata.java:213`)
- [ ] Register `LanceExceptionMapper` (its `@Provider` package is never scanned by `LanceRESTService`)
- [ ] `IcebergExceptionMapper`: replace exact-class `getOrDefault(ex.getClass(), 500)` with hierarchy-aware matching
- [ ] Fix `GravitinoErrorCode.toSimpleErrorMessage` NPE on exceptions with null message
- [ ] Restore interrupt flag in Hudi `HudiHMSBackendOps` `InterruptedException` handlers

Cross-link when circulating: discussion
[apache/gravitino#11982](https://github.com/apache/gravitino/discussions/11982)
(problem statement), issue #11943 / PR #11959 (Iceberg REST fail-fast precursor).
