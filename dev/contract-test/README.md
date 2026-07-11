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

# Gravitino OpenAPI contract test (WIP / proof-of-concept)

Property-based contract testing of a running Gravitino server against the
OpenAPI spec under `docs/open-api`, using [Schemathesis](https://schemathesis.readthedocs.io/).
It generates requests **from the spec** for every operation and checks each
response against the documented contract — no per-endpoint test authoring.

This answers a question nothing else in the repo does: **does the server
actually obey its own published contract?** The Java/Python clients share DTOs
(they can't drift by construction) and doc renderers don't validate, so the
spec has had no consumer holding it honest at runtime.

## Run it locally

```bash
# 1. build + start a server (from the same commit as the spec)
./gradlew compileDistribution -PskipWeb=true -x test
distribution/package/bin/gravitino.sh start        # defaults to H2, no auth

# 2. run the contract test
dev/contract-test/run.sh                            # MAX_EXAMPLES=25 by default
```

| File | Purpose |
|---|---|
| `run.sh` | bundle spec → venv → seed fixtures → `schemathesis run` |
| `fixtures.sh` | create the metalake/catalog/schema the hooks pin to |
| `hooks.py` | pin hierarchy path params to the fixtures |
| `requirements.txt` | pinned Schemathesis |

## Findings from the first investigation run

Against a default (H2, no-auth) server, 489 requests. The server is
measurably non-compliant with its own spec in three real ways:

- **Bug 1 — any operation on a nonexistent metalake returns `500`, not `404`.**
  The auth path (`AuthorizationUtils.checkCurrentUser` → `UserGroupManager.addUser`)
  hits `NoSuchEntityException` on the missing metalake and it surfaces as a raw
  `RuntimeException` ("Failed to validate user"). One root cause, ~100 of the
  134 observed 500s. Should map to `NoSuchMetalakeException` → 404.
- **Bug 2 — wrong-catalog-type operations return `500`, not a 4xx.** e.g.
  creating a topic/model under a `FILESET` catalog ("Failed to operate object …").
- **Bug 3 — the vendor media type is never emitted.** The spec documents
  `application/vnd.gravitino.v1+json` on 223 responses; the server returns
  `application/json` on every response. The contract claims a content type the
  server never sends — spec or server must change.

Both 5xx bugs are the runtime confirmation of the "bug and dependency-down both
→ 500" exception-discipline gap. Documentation gaps (29 write ops missing a
documented `400`, sparse `401`) also show up as `status_code_conformance`
failures.

### De-noising: Bug 1 dominates until routed around

Bug 1 is not just a bug, it degrades the *observability* of everything else: a
500 short-circuits the other checks (a crashed response can't be validated for
schema/content-type), and it fires on every metalake-scoped path. Isolating it
took two moves, both now baked into `hooks.py` / `run.sh`:

- pin **both** `{name}` (metalake CRUD) and `{metalake}` (sub-resources) to the
  fixture metalake, and
- restrict to `--phases fuzzing` (the `coverage` phase injects `"0"`, bypassing
  the pin and re-triggering Bug 1).

With those, the 500 count drops from ~121 to ~12 — and the residual 12 are all
genuine (Bug 2's "Failed to operate object" class, plus an
`Authorization failed due to system internal error` variant). That is the
actual API-validation signal, previously buried.

### Test-environment artifacts (not real defects)

- **Auth checks** ("accepts invalid auth", "missing header not rejected") — the
  dev server runs with no authenticator, so it accepts everything by design.
  Excluded in `run.sh` (`ignored_auth`, `missing_required_header`,
  `unsupported_method` not in the check list). Not meaningful until an
  auth-configured server is tested.
- **409s** from re-created fixtures; **405s** from probing undocumented methods.

## Intended shape (not yet wired into CI)

Integration-test tier (needs a built server), so `schedule:`/on-demand, not
per-PR — matching `.github/workflows/cron-integration-test.yml`. Two jobs:

1. **Blocking** — `not_a_server_error` + `response_schema_conformance`, seeded,
   positive generation. Catches Bugs 1/2 and any response-shape drift (e.g. a
   wire variant missing from a schema).
2. **Warn-only dashboard** — all checks, including `content_type_conformance`
   and `status_code_conformance`, as the systemic-gap picture.

Pair with hand-written "golden journey" tests (deterministic CRUD via a
generated client) for behavioural coverage: Schemathesis proves the wire
protocol, journeys prove the behaviour.
