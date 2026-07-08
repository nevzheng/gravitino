<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Iceberg V3 Variant — E2E Validation Plan

Validate native `variant` support (issue #11949, PR #11932 + follow-ups) end to end using
the Gravitino e2e playground. See the `gravitino-e2e-playground` skill for the environment
details, the two-surfaces note, and hot-load / version-swap mechanics.

Branch: `iceberg-v3-type-e2e` (full variant stack + the skill).

## The constraint we design around

The pinned engines **cannot read a variant value** — Trino 435 and Spark 3.5 predate Open
Variant (Spark 4.0 / Trino ~471). So verification splits into: (1) the native-API fix, (2)
cross-engine *metadata* alignment on today's versions, (3) true cross-engine variant *data*
read, which needs a newer engine via the direct-to-IRC escape hatch. We do only what each
version actually supports — no pretending an old engine reads variant.

## Milestone 1 — Prove the fix (native API loads variant)

This is PR #11932's actual deliverable.

- **Env:** one Gravitino container built from this branch; embedded IRC (:9001) with an
  in-memory Iceberg backend + local-FS warehouse. No engines, no object store.
- **Flow (Mark's Steps 1–3):**
  1. `curl` create a format-version-3 table with a `variant` column via IRC :9001.
  2. `curl` round-trip via IRC :9001 (baseline; worked pre-fix).
  3. Register the IRC as a native catalog (REST backend, **no `warehouse` property**, per
     #11943), `curl` load via **:8090**.
- **Success:** step 3 returns the table with `payload` as `variant` — no
  `UnsupportedOperationException`. That single assertion is the fix.

## Milestone 2 — Cross-engine metadata alignment (pinned versions)

- **Env adds:** MinIO (shared S3 warehouse), Postgres (IRC JDBC backend so metadata
  persists and is network-resolvable), Trino 435 + Spark 3.5 pointed at IRC :9001
  (configs from `docs/iceberg-rest-engine/{trino,spark}.md`).
- **Flow:** from each engine, list/describe the table via the IRC; confirm they resolve the
  same table + non-variant columns.
- **Success:** metadata visible + consistent across engines. **Expected & documented:**
  selecting the `payload` variant column errors on these versions — version-gated, not a bug.

## Milestone 3 — True cross-engine variant data read (optional / future)

- **Env:** add Spark 4 hitting the IRC **directly** (bypasses the version-pinned Gravitino
  connector — the escape hatch).
- **Flow:** write + read actual variant data end to end.
- **Success:** variant data round-trips through a real engine. This is the parity story vs
  Lakekeeper.

## Status

- [ ] M1 — native API load assertion
- [ ] M2 — cross-engine metadata alignment
- [ ] M3 — Spark 4 direct-to-IRC variant data read
