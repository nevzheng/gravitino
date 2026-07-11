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

# Apache Gravitino — Fault-Domain Architecture Report

**Purpose.** Model where failures originate, how error information flows (and dies) across boundaries, and what each boundary's contract should become. Every load-bearing claim below was adversarially verified against the repo; PARTIALLY_TRUE verdicts are corrected in place and the corrections are flagged.

**The proposed taxonomy (used throughout).** A small closed set of exception categories, each carrying a `retriable` bit and the original cause:

| Category | Meaning | HTTP projection | Retriable |
|---|---|---|---|
| `CallerError` | Request is wrong (validation, not-found, already-exists, semantic refusal) | 400/404/409 | no |
| `AuthError` | Credential genuinely bad/expired/forbidden | 401/403 | no (except TokenExpired → refresh+replay) |
| `Conflict` | Concurrent-modification conflict: DB serialization failure (40001/1205/1213), entity CAS/version conflict, Iceberg `CommitFailedException` | 409 | **yes** (retry whole operation) |
| `DependencyUnavailable` | A dependency is down/throttling, transiently | 503 + `Retry-After` | **yes** |
| `DependencyFailure` | A dependency misbehaved non-transiently (bad response, permanent auth-to-backend failure) | 502 | no |
| `AmbiguousCommit` | Outcome unknown (Iceberg `CommitStateUnknownException`, post-COMMIT connection death) | 500-class, distinct code | **never** blind-retry |
| `PartialApply` | Local commit succeeded, downstream push failed (Ranger, dual-write) | 200/202 + warning, or typed error naming committed state | reconcile, don't retry blindly |
| `Overloaded` | Gravitino itself is saturated (LockManager tree full, queue full) | 429 + `Retry-After` | yes |
| `InternalError` | A Gravitino bug — and now *only* that | 500 | no |

---

## 1. Component / module inventory (grouped)

**Shared contracts (the port vocabulary)**
- `api/src/main/java/org/apache/gravitino/exceptions/` — `GravitinoRuntimeException` and all typed exceptions (`NoSuchTableException`, `ConnectionFailedException`, `TokenExpiredException`, `AuthorizationPluginException`, …)
- `common/src/main/java/org/apache/gravitino/dto/responses/` — `ErrorResponse` (`{code, type, message, stack[]}`), `ErrorConstants` (13 codes, 1000–1011 + 1100)

**Core (the hexagon)** — `core/src/main/java/org/apache/gravitino/`
- Dispatch: `catalog/OperationDispatcher.java` + per-entity `*OperationDispatcher` chains (Hook→Event→Normalize→Operation, wired in `GravitinoEnv.java`)
- Managers: `CatalogManager`, `metalake/MetalakeManager`, tag/policy/job/model managers
- `lock/LockManager.java` (in-process tree lock), `listener/` (EventBus, `AsyncQueueListener`)
- `storage/relational/` — `RelationalEntityStore`, `JDBCBackend`, meta services, `converters/` (H2/MySQL/PostgreSQL SQL exception converters), `EntityChangeLogPoller`
- `cache/` — `CaffeineEntityCache` and friends (in-process entity cache)
- `credential/` — `CredentialOperationDispatcher`, `CatalogCredentialManager`, `CredentialCache` (data-plane credential vending)
- `job/` — `JobManager`, executors (background job execution)
- `utils/IsolatedClassLoader.java` — connector classloader isolation
- `auxiliary/` — embedded auxiliary-service lifecycle (e.g., embedded Iceberg REST)

**Main REST server (primary inbound adapter)**
- `server/src/main/java/org/apache/gravitino/server/web/rest/` — resources + `ExceptionHandlers.java` (25 handlers, 114 `instanceof` branches)
- `server/src/main/java/org/apache/gravitino/server/web/mapper/` — exactly 3 Jackson JSON `ExceptionMapper`s; no `ExceptionMapper<Throwable>`
- `server-common/` — `AuthenticationFilter`, `JwksTokenValidator`, `KerberosAuthenticator`, `Utils.java` (response helpers), `JettyServer.java`
- Authz interception: `JcasbinAuthorizer`, `GravitinoInterceptionService`

**Catalog connector adapters (driven)** — `catalogs/` (current module names)
- `catalog-hive` + `hive-metastore-common` (holds `hive/client/HiveExceptionConverter.java`), `hive-metastore2/3-libs`
- `catalog-jdbc-common` (`converter/JdbcExceptionConverter.java`) + `catalog-jdbc-{mysql,postgresql,doris,starrocks}`
- `catalog-lakehouse-{iceberg,paimon,hudi,generic}`
- `catalog-kafka`, `catalog-fileset` + `hadoop-common` + `hadoop-auth`, `catalog-model`, `catalog-glue` (`GlueExceptionConverter`)
- `catalogs-contrib` (ClickHouse/Hologres, with converters)

**Auxiliary inbound gateways (three, not one)**
- `iceberg/iceberg-rest-server/` — `RESTService`, `IcebergExceptionMapper` (`ExceptionMapper<Exception>`), `IcebergCatalogWrapper` path bypassing core dispatchers
- `lance/lance-rest-server/` — `LanceExceptionMapper.java:45` (`ExceptionMapper<Exception>`), own `LanceJettyServer`
- `lineage/` — OpenLineage HTTP source (`source/rest/LineageOperations.java`), async processors, sinks; **the only 429 emitter in the codebase** (line 69)

**Security sidecars & their adapters**
- IdP consumers: `server-common/.../authentication/` (JWKS/OAuth2, Kerberos), `plugins/idp-basic`
- `authorizations/authorization-ranger/` (`RangerAuthorizationPlugin`), `authorization-chain`, `authorization-common`
- Data-path auth: `catalogs/hadoop-auth` (Kerberos to HDFS on fileset ops — flows through connector adapters, not the auth filter)

**Control-plane consumers**
- `clients/client-java` (`HTTPClient`, `ErrorHandlers.java` — 20 per-entity handlers), `clients/client-python`, `clients/cli`
- Trino/Spark/Flink connectors built on client-java; `mcp-server` (Python, on client-python); `web/`, `web-v2/` UIs

**Data-plane consumers**
- `clients/filesystem-hadoop3` (GVFS Java), `clients/filesystem-fuse` (errno-constrained), client-python GVFS
- `bundles/{aws,gcp,aliyun,azure,tencent}` credential providers (STS et al.)

**Autonomous internal actors** (no request to attach an error to)
- `AsyncQueueListener` (audit/event delivery), `EntityChangeLogPoller` (cross-node cache invalidation), maintenance modules (optimizer/updaters/jobs), `JobManager` executors, `CredentialCache` refresh, lineage sinks

---

## 2. Hexagonal architecture view

- **Core hexagon:** entity managers + dispatcher chains + locking + events + entity cache. Owns lifecycle and composes store-writes with connector-writes (the dual-write saga — see Domain 3).
- **Driven (outbound) adapters:** one catalog connector per provider (behind `IsolatedClassLoader`); the relational entity store; the Ranger authz adapter; credential providers/STS; lineage sinks.
- **Driving (inbound) adapters — four, not one:** the main REST server; the Iceberg REST gateway; the Lance REST gateway; the lineage OpenLineage source. The three auxiliary gateways each run their own JAX-RS stack with their own `ExceptionMapper` and **bypass** `ExceptionHandlers` and (for Iceberg/Lance) the core dispatcher chain.
- **External actors:** catalog backends (HMS, JDBC DBs, Iceberg/Paimon/Hudi services, Kafka, object stores + STS), the metadata DB, IdPs (JWKS/KDC), Ranger, and consumers (engines, pipelines, humans).

Key structural fact (CONFIRMED): `org.apache.gravitino.exceptions.*` is a **shared class** across the connector classloader boundary (`IsolatedClassLoader.java:227-234`, `isSharedClass: !isCatalogClass(name)`), so typed exceptions survive in-process. Every flatten downstream is a *choice*, not a classloader necessity.

---

## 3. Fault domains

### D1. External Catalog Backends — one template, instantiated per backend
- **Contains:** HMS (Thrift), JDBC DBs (MySQL/PG/Doris/StarRocks/OceanBase), Iceberg/Paimon/Hudi catalog services (HMS/JDBC/REST/Glue), Kafka brokers, object stores + STS endpoints.
- **Role:** external actor (driven side).
- **Failure modes:** unreachable/timeout (transient), credential rejection (permanent), semantic refusal, **ambiguous commit** (Iceberg `CommitStateUnknownException`), throttling, corrupt responses, and — critically — **hung** (slow, not down).
- **Correlation caveat (corrected from draft):** failures are uncorrelated across backends *only for fail-fast errors*. A hung backend occupies Jetty worker threads and LockManager paths shared by all catalogs, so one slow HMS can degrade the whole server. The proposed contract therefore includes per-catalog **timeouts/bulkheads** (deadline inside `doWithCatalog`), not just classification.
- **Current contract:** rich native dialects (SQLState class 08, `SQLTransientException`, Kafka `RetriableException`, Iceberg `CommitFailedException` vs `CommitStateUnknownException`) exist at this boundary and nowhere downstream.
- **Proposed:** unchanged (not ours); the obligation moves to the adapter: classify at first catch or lose the signal forever.

### D2. Catalog Connector Adapters — one template per provider
- **Contains:** the modules in the inventory; `IsolatedClassLoader` hosting.
- **Role:** outbound adapter / anti-corruption layer.
- **Converter coverage (corrected — at least trimodal, not bimodal):** JDBC (`JdbcExceptionConverter` + dialect subclasses), Hive (via `catalogs/hive-metastore-common/.../hive/client/HiveExceptionConverter.java`), **Glue** (`GlueExceptionConverter`, used throughout `GlueCatalogOperations`), plus catalogs-contrib ClickHouse/Hologres. **Zero** converters for: lakehouse-iceberg (operational path), paimon, hudi, kafka, fileset/hadoop-common, model.
- **The 127 sites, correctly attributed:** `rg 'throw new RuntimeException' catalogs/*/src/main` = 127 (verified). Top offenders — `FilesetCatalogOperations` (19), `KafkaCatalogOperations` (15), `ModelCatalogOperations` (14) — largely wrap **entity-store IOExceptions** (these catalogs persist to Gravitino's own store), not backend dialects. So the fix is two-pronged: a backend `ExceptionConverter` SPI for dialect exceptions, *and* the `MetadataStore{Unavailable,Failure}` wrap for the store-IOException sites inside catalog modules.
- **Proposed contract:** mandatory per-adapter converter (part of `CatalogOperations`): classify into `CallerError / AuthError / Conflict / DependencyUnavailable / DependencyFailure / AmbiguousCommit`, cause preserved. Default for unknown backend exceptions = `DependencyFailure`, never `InternalError` — inside an adapter's catch, "unknown" means the dependency misbehaved.

### D3. Gravitino Core (dispatch, orchestration, locking, events, dual-write saga)
- **Contains:** dispatcher chains, managers, LockManager, EventBus, entity cache, credential dispatcher, job manager.
- **Failure modes:** genuine bugs (POConverters ser/de); overload (`LockManager` tree full → `IllegalStateException`); propagated store IOExceptions; propagated connector exceptions; **dual-write partial failure** (new — see below); silent event loss; cache staleness after failed invalidation.
- **Dual-write saga (added per critique — the biggest unmodeled risk):** every create/drop/alter is connector-write + store-write. The wrap sites at `TableOperationDispatcher.java:390,447`, `SchemaOperationDispatcher.java:355`, `TopicOperationDispatcher.java:253`, `ViewOperationDispatcher.java:315` wrap *store* failures occurring **after** the connector call — a backend table created but store-put failed leaves an orphan visible to engines but invisible to Gravitino (and inversely on drop, where compensation via `store.delete` can itself fail). Today this surfaces as a generic 500 with no admission that backend state changed.
- **Current contract:** `OperationDispatcher.java:89,106,129` wraps every checked exception in bare `RuntimeException` (CONFIRMED — the three funnels are exhaustive; all expected-exception types passed today are unchecked). ~70 manager sites wrap `EntityStore` IOException the same way (`MetalakeManager.java:132`, `CatalogManager.java:455,481,1445`). Core is the layer that structurally *knows* which dependency it called, and erases that knowledge. Note: the wrap preserves the cause chain, but downstream dispatch is type-based, so the cause is machine-invisible.
- **Proposed:** core becomes the classify-then-preserve backstop. `doWithCatalog` wraps unclassified escapees as `DependencyFailure(catalogIdent, cause)` with a per-call deadline; manager IOException wraps become `MetadataStore{Unavailable,Failure}`; LockManager overload → `Overloaded`; dual-write gets an explicit contract: compensation attempted, and on compensation failure a typed `PartialApply` error naming the orphaned backend object (plus an orphan-reconciliation hook). Only ser/de and assertion failures mint `InternalError`.

### D4. Metadata Persistence (correlated-failure domain)
- **Contains:** `storage/relational` + SQL converters + dbcp2 pool + the external MySQL/PG/H2 itself + `EntityChangeLogPoller`.
- **Role:** outbound adapter + its DB, modeled as ONE domain: when it fails, every entity type and every API fails together.
- **Current contract (CONFIRMED):** converters classify exactly one condition — duplicate key (H2 23505/1062 at `H2ExceptionConverter.java:38-46`, MySQL 1062 at `MySQLExceptionConverter.java:39-45`, PG "23505" at `PostgreSQLExceptionConverter.java:38-45`) → `EntityAlreadyExistsException`; everything else → opaque `IOException(se)` → RuntimeException → 500. (Nuance: MySQL/H2 switch on vendor `getErrorCode()`, only PG uses SQLState.) No deadlock/`SQLTransient`/retry handling anywhere in `storage/relational`. DB-down → 500 "internal error" on every endpoint while `/health` correctly reports the same condition — the server demonstrably knows the right answer on exactly one code path.
- **Failure modes:** DB down / pool exhausted (transient); deadlock/serialization conflict (retry-safe); duplicate key (the only classified one); ambiguous commit; change-log poll failure → silently stale caches on multi-node; also **entity CAS/version conflicts** on concurrent alters through two instances (unmodeled today, needs `Conflict`).
- **Proposed:** classify the full space — 08*/pool-timeout → `MetadataStoreUnavailable(retriable)`; 40001/40P01/1205/1213 → `Conflict` (core may auto-retry the transaction); 23xxx → typed constraint violations; post-COMMIT death → `AmbiguousCommit`. Cause-chain walk (MyBatis `PersistenceException` nesting) mandatory at every meta-service site. Poller failure feeds health.

### D5. Main REST Server Boundary
- **Contains:** resources + `ExceptionHandlers` + 3 JSON mappers + auth filters + authz interception + `ErrorResponse`/`ErrorConstants` wire vocabulary.
- **Current contract:** 25 hand-maintained instanceof ladders that drift: `ConnectionFailedException` is tested in exactly two places (`ExceptionHandlers.java:172,398`) — **only the CatalogExceptionHandler path yields HTTP 502/1007; the testConnection path returns HTTP 200 with an embedded 1007 body** (corrected per verdict). Forbidden missing from ~10 ladders; UnsupportedOperation in 6/22. Anything unmatched → `Utils.internalError` → 500 code 1002 + full stack trace. No `ExceptionMapper<Throwable>` (CONFIRMED — `GravitinoServer.java:165-167` registers only the 3 Jackson mappers; `JettyServer.java:106-110` installs stock Jetty `ErrorHandler` with `setShowStacks(true)`, so Errors/filter exceptions produce **HTML with stack traces**).
- **Retriability (corrected):** the *JSON taxonomy* cannot carry retriability — 13 codes, no retriable field, zero `Retry-After` anywhere. The HTTP status line carries coarse retriability at the margins (502 from `Utils.connectionFailed`; 429 exists but is used only by lineage; 503 only on health endpoints). `Utils.tooManyRequests()` has **one** call site — `LineageOperations.java:69` — not zero as previously drafted.
- **Proposed:** one declarative registry (exception → status/code/retriable), instanceof/nearest-ancestor matching, shared by all entities; `ExceptionMapper<Throwable>` + JSON-emitting Jetty ErrorHandler as safety nets; new codes DEPENDENCY_UNAVAILABLE (503+Retry-After), DEPENDENCY_FAILED (502), CONFLICT_RETRIABLE (409), RATE_LIMITED (429), AMBIGUOUS_COMMIT; `ErrorResponse` v2 gains `{retriable, causeChain[{type,message}], correlationId}` with stack redaction by default.

### D6. Auxiliary Inbound Gateways — one template, three instances (generalized per critique)
- **Instances:** Iceberg REST (`IcebergExceptionMapper`), Lance REST (`LanceExceptionMapper.java:45`), Lineage source (`LineageOperations`). All share the shape: own JAX-RS stack, own mapper, bypass `ExceptionHandlers`, own wire dialect (Iceberg spec / Lance / OpenLineage).
- **Iceberg instance (CONFIRMED, the reference implementation):** declarative map preserving retriability the main server destroys — `CommitFailedException`→409, `CommitStateUnknownException`→500, `ServiceUnavailableException`→503, `TokenExpiredException`→419 (`IcebergExceptionMapper.java:57-82`). Weaknesses: **exact-class** `getOrDefault(ex.getClass(), 500)` (`:90-93,128-132`) so subclasses fall to 500 (silent to the *client*; the server does `LOG.warn` with stack); no `Retry-After`.
- **Lineage instance:** the only backpressure-aware endpoint (429 on queue-full) — but a bare 429 status with no body/Retry-After; sink failures lose lineage events silently (see D9).
- **Proposed:** keep the declarative-map pattern; switch to instanceof matching; add Retry-After; define an explicit **taxonomy→dialect projection table per gateway** so the same classified failure gets equivalent semantics in all four wire dialects — otherwise today's N=2 drift becomes N=4.

### D7. Control-plane Consumers (client-java/python, engine connectors, CLI, MCP server, web UIs)
- **Current contract (CONFIRMED):** `ErrorHandlers.java` has **20** per-entity sites with `case INTERNAL_ERROR_CODE: throw new RuntimeException(errorMessage)` (lines 361…1424); none inspect `type` first, and the server hardcodes `type="RuntimeException"` for 1002 anyway (`ErrorResponse.java:205-210`). Stringly-typed dispatch on `type`; `TokenExpiredException` collapsed to `UnauthorizedException` (refresh signal destroyed); only CatalogErrorHandler maps 1007 (`ErrorHandlers.java:562-563`); no retry/backoff/Retry-After handling; unparseable bodies → code 1100 → bare RuntimeException. The MCP server propagates this degradation into LLM-agent workflows; the web UIs are the main human exposure of the stack-trace leak.
- **Proposed:** one shared registry-driven handler rehydrating `{code, retriable}` into a typed `GravitinoServerException` hierarchy; Retry-After honored with capped backoff on idempotent ops; TokenExpired → refresh + single replay; never bare RuntimeException for a well-formed ErrorResponse.

### D8. Data-plane Consumers & Credential Vending (split out per critique)
- **Contains:** GVFS Java (`filesystem-hadoop3`), GVFS Python, FUSE (`filesystem-fuse`), and the vending chain `core/credential/CredentialOperationDispatcher` → `bundles/*` → cloud STS → `CredentialCache` → client.
- **Why distinct:** failures land at *data time* (open/read/write), possibly mid-stream on vended-token expiry; Gravitino is bypassed after path resolution; FUSE must project the entire taxonomy into **errno** values — a fundamentally narrower contract the Java hierarchy doesn't address. `hadoop-auth` Kerberos failures surface here through the no-converter fileset adapter.
- **Failure modes:** STS throttle/outage at vend time; token expiry mid-read; cache-serving-expired races; object-store errors after successful vend.
- **Proposed:** vending endpoint classified like any backend (`DependencyUnavailable` on throttle, with client backoff); early-refresh window in `CredentialCache`; a documented taxonomy→errno projection for FUSE (e.g., DependencyUnavailable→EAGAIN/EIO, AuthError→EACCES, CallerError→ENOENT/EEXIST).

### D9. Security Sidecars (IdPs + authorization backends)
- **IdP flattening (CONFIRMED with operational caveats):** `JwksTokenValidator.java:173-178` blanket-catches and rethrows `UnauthorizedException` (JWKS outage = bad credential = 401 code 1011); `KerberosAuthenticator.java:146-148` same for GSS failures. Caveats: JWKS keys are cached ~5 min with refresh-ahead so only longer outages surface; SPNEGO validates against the local keytab so KDC outages mostly fail client-side — but any server-side manifestation is flattened.
- **Ranger (CONFIRMED):** `RangerAuthorizationPlugin` throws `AuthorizationPluginException` for *both* backend outage (wrapping `RangerServiceException`, ~10 sites) and policy conflicts (line 218); no server ladder maps it → 500 code 1002; and the entity-store write may already be committed → silent policy drift. Two best-effort paths swallow it entirely (`OwnerManager.java:214-219,260-268`).
- **Proposed:** authenticators separate `AuthError`(401) from IdP `DependencyUnavailable`(503+Retry-After); Ranger adapter classifies down vs conflict; post-commit push failure → `PartialApply` + reconciliation queue.

### D10. Autonomous Internal Actors (new domain per critique)
- **Contains:** `AsyncQueueListener` (drops events on queue-full), pre-event listeners (fail-open: only `ForbiddenException` vetoes), `EntityChangeLogPoller`, `CaffeineEntityCache` invalidation, `JobManager` executors, maintenance optimizer/updaters, lineage sinks, `CredentialCache` refresh.
- **Why a domain:** these have **no request to fail** — their only legitimate outputs are metrics, health-state transitions, and reconciliation queues. Today they fail silently, so a 200 does not guarantee audit delivery, cache coherence, lineage capture, or job progress.
- **Proposed contract type (distinct from the HTTP taxonomy):** every autonomous actor exposes {last-success timestamp, failure counter, degraded flag} wired into `/health`; enforcement-relevant listeners get a fail-open/fail-closed config; drops are counted, never silent.

---

## 4. Boundary contract table

| # | Boundary | CURRENT error flow | PROPOSED contract |
|---|---|---|---|
| B1 | Backends → Connector Adapters | Native dialects arrive with transient/permanent/ambiguous signals. JDBC/Hive/Glue(+contrib) convert some; iceberg-operational/paimon/hudi/kafka/fileset/model convert nothing; 127 `throw new RuntimeException` sites in `catalogs/*/src/main` (a substantial fraction wrap store-IOExceptions, not dialects). | Mandatory converter at first catch → taxonomy, cause preserved. Iceberg: `CommitFailedException`→`Conflict`(retriable), `CommitStateUnknownException`→`AmbiguousCommit`. Kafka: `RetriableException`→`DependencyUnavailable`. Store-IOException sites inside catalogs use the B3 wrap instead. |
| B2 | Connector Adapters → Core | In-process; `org.apache.gravitino.exceptions.*` classloader-shared so types survive (`IsolatedClassLoader.java:227-234`). `OperationDispatcher.java:89,106,129` wraps all checked exceptions bare; `withClassLoader` preserves exactly one exception type (`IsolatedClassLoader.java:102-112`). | `doWithCatalog` = classification backstop: non-Gravitino escapee → `DependencyFailure(catalogIdent)`; per-call deadline/bulkhead so a hung backend can't starve other catalogs; `withClassLoader` widened to the taxonomy; `InterruptedException` re-interrupts. |
| B3 | Metadata DB → Persistence adapter | Converters classify only duplicate-key; connection-broken/deadlock/pool-exhausted → opaque `IOException(se)`; `checkSQLException` unwraps one cause level at ~25 sites, so nested `PersistenceException` chains skip even that. | Full classification: 08*/pool-timeout → `MetadataStoreUnavailable`; 40001/40P01/1205/1213 → `Conflict` (auto-retriable transaction); 23xxx → typed constraints; post-COMMIT death → `AmbiguousCommit`. Full cause-chain walk, mandatory everywhere. |
| B4 | Persistence → Core | ~70 `catch(IOException){throw new RuntimeException(ioe)}` manager sites → HTTP 500; Gravitino's own DB down is bug-shaped on every endpoint while `/health` says 503. | One shared wrap helper: transient → `MetadataStoreUnavailable`(503+Retry-After); else `MetadataStoreFailure` (typed, log/metric-distinguishable from `InternalError`). `NoSuchEntityException` translated to entity-specific type before leaving managers (ArchUnit rule). |
| B5 | Core dual-write saga (connector + store) | Store-write failure after successful connector-write → RuntimeException wrap (`TableOperationDispatcher.java:390,447` etc.) → 500; orphaned backend objects; compensation absent or itself unchecked. | Explicit saga contract: compensate on failure; compensation failure → typed `PartialApply` naming committed backend state; orphan-reconciliation job; drop-path inverse handled symmetrically. |
| B6 | Core → Main REST server | 25 drifted instanceof ladders; `ConnectionFailedException` tested at `ExceptionHandlers.java:172,398` only — 502/1007 from the catalog handler; testConnection returns **HTTP 200 + embedded 1007 body**; unmatched → 500/1002 + stack; Errors/filter exceptions → Jetty **HTML** with stacks (`JettyServer.java:106-110`, showStacks=true). | Single declarative registry (the IcebergExceptionMapper pattern, instanceof-matched) + `ExceptionMapper<Throwable>` + JSON Jetty ErrorHandler. `DependencyUnavailable`→503+Retry-After, `DependencyFailure`→502, `Conflict`→409(retriable), `Overloaded`→429, `InternalError`→500 strictly. Fix testConnection's 200-inversion (or version it). |
| B7 | Main server → Control-plane clients | `ErrorResponse{code,type,message,stack[]}`; no retriable bit; zero Retry-After; stack leak; client: 1002 → bare `new RuntimeException(errorMessage)` at 20 sites; TokenExpired collapsed; proxy 429/503 → code 1100 → bare RuntimeException; no retry logic. | ErrorResponse v2 `{retriable, causeChain, correlationId}`, stack redacted by default; Retry-After on 503/429; client rehydrates taxonomy, refreshes on TokenExpired, backs off on retriable idempotent ops. |
| B8 | Clients → Engines/users | Engine connectors get bare RuntimeException for every 500 — cannot choose fail/retry/degrade; GVFS Python raises untyped errors; MCP server forwards the degradation to agents. | Typed hierarchy → engine-native mapping (e.g., Trino EXTERNAL for DependencyUnavailable); retriable bit drives engine retry policy. |
| B9 | Iceberg backends → Iceberg REST gateway | Declarative map preserves retriability (409/500/503/419) but exact-class matching drops subclasses to 500 (logged server-side, silent on wire); unmapped Gravitino-classified types from shared code → 500. | Keep pattern; instanceof matching; Retry-After on 503; add taxonomy entries so classified failures keep status across this edge. |
| B10 | Aux gateways (Lance, Lineage) → their clients | Lance: own `ExceptionMapper<Exception>`, unaudited, divergent by default. Lineage: 429 on queue-full (only such site in repo) as bare status; sink failures drop events silently. | Per-gateway taxonomy→dialect projection table; lineage sink failures → metrics + health degradation (D10 contract), never silent. |
| B11 | IdPs (JWKS/KDC) → auth filters | Infrastructure failure → `UnauthorizedException` → 401 code 1011 (`JwksTokenValidator.java:173`, `KerberosAuthenticator.java:146`); clients re-auth in a loop against a down IdP. (Caveat: JWKS cache ~5 min masks short outages; KDC mostly fails client-side.) | Infra failure → `DependencyUnavailable`(503+Retry-After); only real rejection → 401; TokenExpired keeps its wire type. |
| B12 | Ranger → Core (authz push) | `AuthorizationPluginException` for both outage and conflict; unmapped → 500/1002; local commit may already be durable → hidden policy drift; some paths swallow entirely (`OwnerManager.java:214-219`). | Ranger-down → `DependencyUnavailable`; conflict → `CallerError`(409); post-commit failure → `PartialApply` + async reconciliation. |
| B13 | STS/credential endpoints → vending → GVFS clients | Vend-time throttle/outage and mid-read token expiry surface as untyped data-plane errors; no errno mapping discipline in FUSE. | Vend failures classified (`DependencyUnavailable` on throttle); CredentialCache early-refresh; documented taxonomy→errno projection for FUSE. |

---

## 5. Verified evidence base

**CONFIRMED (adversarially verified):**
1. Three bare-RuntimeException funnels for all checked connector exceptions — `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:89,106,129`; all expected-exception types passed today are unchecked (`GravitinoRuntimeException.java:25`).
2. Exceptions are classloader-shared — `core/src/main/java/org/apache/gravitino/utils/IsolatedClassLoader.java:227-234` (barrier lists empty in production, `CatalogManager.java:1175`); dispatch already relies on typed exceptions crossing (`TableOperationDispatcher.java:127,273,552`).
3. client-java degrades code 1002 to `new RuntimeException(errorMessage)` at **20** sites — `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:361,411,466,541,581,636,743,794,848,889,945,998,1031,1077,1123,1159,1213,1274,1337,1424`; server hardcodes `type="RuntimeException"` for 1002 (`ErrorResponse.java:205-210`).
4. Iceberg gateway bypasses core dispatch and preserves retriability — `iceberg/iceberg-rest-server/.../IcebergExceptionMapper.java:57-82` (409/500/503/419), registered `RESTService.java:105`; main server has no mapping for these types.
5. Exact-class matching in that mapper — `IcebergExceptionMapper.java:90-93,128-132` (`getOrDefault(ex.getClass(),500)`); subclass fall-through is client-silent but LOG.warn'd.
6. SQL converters classify only duplicate-key — `core/.../storage/relational/converters/{H2,MySQL,PostgreSQL}ExceptionConverter.java` (H2:38-46, MySQL:39-45, PG:38-45); no transient/deadlock handling anywhere in `storage/relational`.
7. IdP flattening — `server-common/.../JwksTokenValidator.java:173-178`, `KerberosAuthenticator.java:146-148`; 401/1011 via `AuthenticationFilter.java:139-143`, `ErrorConstants.java:58`.
8. Ranger — `authorizations/authorization-ranger/.../RangerAuthorizationPlugin.java` (outage wraps at 321-327, 545, 561-562, …; conflict at 218); zero references in `ExceptionHandlers.java` → 500/1002.
9. No `ExceptionMapper<Throwable>` — `server/.../GravitinoServer.java:165-167` (3 Jackson mappers only); `JettyServer.java:106-110` stock HTML ErrorHandler with `setShowStacks(true)`.
10. 127 `throw new RuntimeException` sites in `catalogs/*/src/main` (top: FilesetCatalogOperations 19, KafkaCatalogOperations 15, ModelCatalogOperations 14 — many wrapping store IOExceptions).
11. (This session) `Utils.tooManyRequests()` has exactly one call site: `lineage/src/main/java/org/apache/gravitino/lineage/source/rest/LineageOperations.java:69`. Lance gateway exists with its own catch-all mapper: `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/LanceExceptionMapper.java:45`.

**Corrected from the draft (PARTIALLY_TRUE verdicts):**
- `ConnectionFailedException` → 1007 in two places, but **502 at only one** (`ExceptionHandlers.java:398`); the testConnection path (`:172`) returns HTTP **200** with a 1007 body. Dependency-down on schema/table/fileset/topic/model ops → 500: fully accurate.
- Retriability: the **JSON taxonomy** carries none (13 codes, no field, zero Retry-After — all confirmed); the HTTP status line carries coarse retriability at the margins (502; 429 in lineage; 503 health).
- Converter coverage: JDBC + Hive + **Glue** (+contrib), not bimodal; Hive converter lives at `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveExceptionConverter.java` (not under catalog-hive).
- Module naming: fileset code is `catalogs/catalog-fileset` + `catalogs/hadoop-common` (+`hadoop-auth`); there is no `catalog-hadoop`.
- Per-backend failure independence holds for fail-fast errors only; hung backends correlate via shared Jetty/LockManager substrate.

**Unverified / needs re-check before relying on it:** the draft claim that `CatalogManager.java:653-661` testConnection preserves only `GravitinoRuntimeException` (making checked connect failures 500 instead of 1007) was not adversarially verified; re-verify before citing in the design doc.

---

## 6. Top problems (ranked)

1. **Dependency-down is indistinguishable from a Gravitino bug end-to-end.** Four consecutive layers each destroy the signal the layer below still had: adapters flatten (most of 127 sites), core re-flattens (3 dispatcher funnels + ~70 manager wraps), the server maps unmatched → 500/1002 + stack, the client degrades 1002 → bare RuntimeException (20 sites). An HMS restart, a PG failover, an STS throttle, and a real NPE are byte-identical to every consumer.
2. **Gravitino's own DB down returns 500 "internal error" on every API while `/health` simultaneously and correctly says 503.** SQL converters classify only duplicate-key; deadlock and pool exhaustion — retry-safe, transient — are bug-shaped.
3. **Dual-write partial failure is unmodeled**: connector-write success + store-write failure (or the inverse on drop) leaves orphaned backend objects behind a generic 500, with compensation absent or unchecked. The Ranger variant of this (post-commit policy-push failure → hidden drift) is the same defect on the control plane.
4. **The JSON wire taxonomy cannot express "retry later"**: no retriable code in ErrorConstants, no field in ErrorResponse, zero Retry-After; 429 exists only on the lineage endpoint; 503 only on health. Even a perfectly classifying server couldn't tell a client to back off.
5. **Retriability traps one flatten away from data corruption**: Iceberg `CommitStateUnknownException` (never retry) and `CommitFailedException` (safe to retry) both become RuntimeException→500 on the main path; a client adding naive retry-on-500 risks double-commit. The in-repo Iceberg gateway proves the correct mapping is already understood.
6. **N=4 inbound-gateway drift**: 25 drifted ExceptionHandlers ladders (ConnectionFailed→502 in 1 of 22, Forbidden missing from ~10) *plus* three auxiliary gateways each with their own mapper and dialect (Iceberg exact-class matching; Lance unaudited; lineage bare-429). The same fault gets a different contract depending on which door it exits.
7. **Auth conflations causing active harm**: IdP outage → 401 makes clients hammer a down IdP with re-auth loops; TokenExpired typed on the wire but collapsed client-side (kills auto-refresh); authz-path failures → 500 on every protected endpoint.
8. **No safety net at the main server's own edge**: no `ExceptionMapper<Throwable>`; Errors and filter exceptions return Jetty HTML *with stack traces enabled* — the JSON contract is unenforced exactly where it matters most.
9. **Fail-silent autonomous actors**: pre-event listener crashes swallowed, audit events dropped on queue-full, change-log polling can die silently (stale caches on multi-node), lineage sink failures lose events — a 200 guarantees neither audit integrity nor cache coherence.
10. **No slow-dependency defense**: a hung backend ties up shared Jetty workers and lock paths, converting one catalog's outage into whole-server degradation — no per-catalog timeout or bulkhead exists, and the taxonomy alone won't fire if the server can't get a thread to emit the 503.
11. **ErrorResponse ships full server stack traces to any caller** (including 401s) with no redaction — an information leak doubling as the only (machine-unreadable) cause channel.

## 7. Open design questions

1. **Taxonomy placement**: do the new types live in `api/.../exceptions` (client-visible, next to existing types) or a new module? They must be classloader-shared and client-rehydratable; does adding them to `api` constitute an API-compat event?
2. **Wire versioning**: can `ErrorResponse` gain `{retriable, causeChain, correlationId}` additively (old clients ignore unknown fields — Jackson default?), and can new ErrorConstants codes be introduced without breaking the 20 client switch statements that `default:` to RESTException?
3. **testConnection's 200-inversion**: fix in place (breaking change for callers that parse the embedded body) or introduce a v2 endpoint?
4. **Dual-write policy**: compensate-and-report vs report-PartialApply-without-compensation vs background orphan reconciliation — and is the answer different for create vs drop? Does Gravitino want a durable outbox for compensations?
5. **Auto-retry ownership**: should core auto-retry `Conflict`-class store transactions (serialization failures) internally, and if so with what idempotency guarantees for non-transactional connector side effects?
6. **Bulkheads**: per-catalog thread pools vs deadlines-on-calls vs both; what default timeout for connector operations, and is it per-provider configurable?
7. **Fail-open vs fail-closed listeners**: which event listeners are enforcement (must veto on failure) vs observability (may drop with a metric)? Config surface per listener?
8. **Multi-listener health semantics**: what does `/health` mean when the main server is up but an embedded aux gateway (Iceberg REST via `core/auxiliary`) is dead, or the change-log poller is stale? Per-component health tree?
9. **Errno projection**: what is the canonical taxonomy→errno table for FUSE, and taxonomy→OpenLineage/Lance projections for the aux gateways? Who owns keeping the four projection tables in sync (suggest: one registry, four renderers, conformance tests)?
10. **Stack-trace redaction default**: flip to redacted-by-default with an operator debug toggle — is any existing tooling parsing `stack[]`?
11. **Enforcement**: ArchUnit/lint rules to ban bare `throw new RuntimeException` in catalogs/core and require converter coverage per adapter — gate in CI from day one, or after migration?
12. **Sequencing**: recommended order is (a) wire contract additive fields + new codes, (b) server registry + Throwable safety net, (c) store converters + manager wrap helper, (d) adapter converter SPI, (e) client rehydration + backoff, (f) saga/PartialApply, (g) bulkheads — does the maintainer agree the wire contract goes first, since every other layer can then migrate incrementally?