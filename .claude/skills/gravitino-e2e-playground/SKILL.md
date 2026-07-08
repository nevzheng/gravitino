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

---
name: gravitino-e2e-playground
description: Use when standing up or working in the Gravitino end-to-end Docker playground to manually or semi-automatically verify catalog/connector behavior across engines — the Gravitino core + built-in Iceberg REST catalog plus query engines (Trino, Spark, Flink, Doris, …). Covers building and hot-loading a locally-built connector into the running environment, and swapping component versions / images / binaries. Reach for it for cross-engine behavior checks (e.g. does a type round-trip identically everywhere) and for reproducing native-API vs IRC discrepancies.
---

# Gravitino E2E Playground

## What this is for

Stand up a realistic Gravitino environment in Docker — the Gravitino server, its
built-in Iceberg REST catalog, and one or more query engines — then drive it with
`curl` and engine clients to check behavior end-to-end. Use it to:

- Verify a metadata/type change round-trips identically across engines.
- Reproduce a native-API-only failure that the raw catalog surface hides (see the
  two-surfaces note below — this is the single most common source of confusion).
- Try a locally-built connector, or a different engine/image version, without a full release.

This is an exploration and manual/semi-automatic verification harness. For a repeatable
CI test, prefer a Gradle integration-test module (e.g. `iceberg/iceberg-rest-trino-it`).

## The two surfaces — read this first

A Gravitino deployment exposes **two different HTTP surfaces**, and most "it works via
curl but fails in the UI" confusion comes from conflating them:

| Surface | Default port | Path shape | What it is |
|---|---|---|---|
| **Iceberg REST Catalog (IRC)** | `9001` | `/iceberg/v1/namespaces/...` | Gravitino's built-in, spec-compliant Iceberg REST catalog. Engines connect here as a normal Iceberg REST catalog. |
| **Native metadata API** | `8090` | `/api/metalakes/.../catalogs/.../tables/...` | The federated governance layer (also backs the UI, tags/policies, column governance). |

The IRC (`:9001`) is the built-in module `iceberg/iceberg-rest-server` (image
`apache/gravitino-iceberg-rest`). It runs **standalone** or **embedded** in the main
Gravitino server — either way it defaults to port `9001`
(`gravitino.iceberg-rest.httpPort`). One Gravitino process can expose both `8090` and `9001`.

**Why it matters for verification:** a check that only queries the IRC can pass while the
native API path is broken (the IRC delegates to Iceberg core; the native API runs
Gravitino's own type converter). Whenever you verify a metadata/type behavior, exercise
**both**: create via the IRC (or an engine), then **load through the native API** and assert.

## Pay attention to versions

Behavior is version-dependent, and the versions the code is *compiled* against are the
ground truth — not whatever a Docker image happens to be tagged. Pinned versions live in
[`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml). As of this writing:

| Component | Pinned version | Note |
|---|---|---|
| Iceberg | `1.11.0` | brings V3 types into range |
| Spark | `3.4.3` / `3.5.3` | Open Variant lands in **Spark 4.0** |
| Flink | `1.18.0` | Open Variant lands in **Flink 2.1.0** |
| Trino | `435` | connector SPI; variant lands ~Trino 471 |
| Doris | JDBC | native variant (Doris 2.1+) |
| Paimon | `1.2.0` | native variant (Paimon 1.1+) |
| Hive | `2.3.9` | no variant |

Consequence: several engines *reject* newer types simply because we compile against a
version that predates them. When you observe a reject, confirm it's a version floor, not a
bug, before filing. Docker image tags must be re-anchored to specific versions when
reporting results — "latest" drifts (a lesson from prior competitive probes).

## The environment

### Base to build on

- **`apache/gravitino-playground`** (separate repo) — a ready `docker-compose` with
  Gravitino, Hive, Trino, Spark, MySQL, PostgreSQL, Jupyter, Prometheus/Grafana. **It does
  NOT include the Iceberg REST catalog, Flink, or Doris** — add those.
  Docs: https://gravitino.apache.org/docs/next/getting-started/playground/
- **In-repo Docker builds** under [`dev/docker/`](../../../dev/docker/): `gravitino`,
  `iceberg-rest-server`, `trino`, `doris`, `hive`, `kerberos-hive`, `ranger`,
  `lance-rest-server`, `mcp-server`. Each has a `Dockerfile` + dependency script.
- **Per-engine wiring recipes** — [`docs/iceberg-rest-engine/`](../../../docs/iceberg-rest-engine/):
  `trino.md`, `spark.md`, `flink.md`, `doris.md`, `starrocks.md`, `pyiceberg.md`, `ray.md`.
  These are the canonical "point engine X at the IRC" instructions — start here rather than
  reverse-engineering catalog config.

### Recommended topology

One Gravitino instance (exposing **`8090` + embedded IRC `9001`**), a shared warehouse
(local FS or MinIO/S3), and each engine pointed at the IRC on `9001`. Keep the compose
minimal; add engines incrementally.

```
                         ┌────────────────────────────────┐
   native API :8090 ───▶ │  Gravitino server (1 container)│ ◀─ catalog connectors
                         │  loads catalog connectors      │   (iceberg/hive/mysql/
   IRC        :9001 ───▶ │  in-process: catalogs/<p>/libs │    doris/paimon…) run
                         └───────────────┬────────────────┘   INSIDE this process
                                         │ (federates)
                    ┌────────────────────┼─────────────────────┐
                    ▼                    ▼                      ▼
              Hive Metastore        Warehouse              JDBC backends
                (:9083)             (S3/MinIO or FS)       (MySQL/Postgres)

   Engines — each its OWN container, each with an engine-side catalog/connector
   pointed at the SAME IRC (:9001) + SAME warehouse (this shared pair is the ONLY
   reason they agree; they do not query each other):

     ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐
     │ Trino │  │ Spark │  │ Flink │  │ Doris │  → http://gravitino:9001/iceberg
     └───────┘  └───────┘  └───────┘  └───────┘    + shared warehouse
```

"Line up the env vars" = every engine's catalog config carries the **same IRC URL, same
warehouse path, same credentials**. That is the entire wiring.

### Gotcha: `warehouse` property on the REST backend

When registering the IRC as a native catalog (`catalog-backend: rest`), **omit the
`warehouse` property**. On the REST backend it is interpreted as a catalog *selector*, not
a storage path, and a URI-shaped value yields `NoSuchWarehouseException`. Tracked in
[apache/gravitino#11943](https://github.com/apache/gravitino/issues/11943).

## Building & hot-loading from the active codebase

You will usually want to run **your working tree**, not a published image.

### Build a full distribution

```bash
# From repo root — assembles distribution/package (server + bundled catalogs)
./gradlew compileDistribution        # unpacked, fast to iterate
./gradlew assembleDistribution       # tarball, includes versioned Trino connectors
```

Output lands in `distribution/package/`. Catalog connector jars live under
`distribution/package/catalogs/<provider>/libs/` (e.g. `catalogs/lakehouse-iceberg/libs/`).

### Build a Docker image from source

```bash
# dev/docker/build-docker.sh --platform <p> --type <component> --image <name> --tag <tag>
dev/docker/build-docker.sh --platform linux/amd64 --type gravitino \
  --image local/gravitino --tag dev
# component types: gravitino | iceberg-rest-server | trino | doris | hive |
#                  kerberos-hive | ranger | lance-rest-server | mcp-server
```

Then point the compose at the locally-built tag (e.g. `local/gravitino:dev`) instead of
`apache/gravitino:<release>`.

### Hot-load a single rebuilt connector (fast loop)

Rather than rebuilding the whole image, rebuild one catalog module and drop its jars into
the running container. Each catalog runs in its own `IsolatedClassLoader`
(`core/.../IsolatedClassLoader.java`, used by `BaseCatalog`), loading from
`catalogs/<provider>/libs` — so replacing the jars and reloading the classloader picks up
your build. This is *replace-then-reload*, **not** zero-downtime live reload.

```bash
# 1. rebuild just the catalog you changed and stage its libs.
#    copyCatalogLibs -> distribution/package/catalogs/lakehouse-iceberg/libs
#    (or copyLibAndConfig for libs + conf). Provider dir name = the distribution dir
#    (lakehouse-iceberg, jdbc-doris, lakehouse-paimon, …), not the Gradle module name.
./gradlew :catalogs:catalog-lakehouse-iceberg:copyCatalogLibs

# 2. copy the staged libs into the running container's provider dir (under GRAVITINO_HOME)
docker cp distribution/package/catalogs/lakehouse-iceberg/libs/. \
  <gravitino-container>:<GRAVITINO_HOME>/catalogs/lakehouse-iceberg/libs/

# 3. reload the classloader — restart the server, OR drop & re-create the catalog
#    (recreating the catalog rebuilds just its IsolatedClassLoader)
docker restart <gravitino-container>
```

## Swapping versions / images / binaries — yes, this is legit

Trying a different engine/catalog version is a normal and supported workflow — it's exactly
how you validate that a "reject" arm flips to native when an engine crosses its version
floor (e.g. Spark 4.0 variant). Ways to swap, cheapest first:

- **Image tag** — change the engine/Gravitino image tag in the compose (many playground
  services read `${*_IMAGE_TAG}` env vars). Good for trying a released engine version.
- **Build from source at a version** — rebuild via `build-docker.sh` after bumping the
  relevant entry in `gradle/libs.versions.toml` (recompile the affected module too).
- **Swap a binary/jar in place** — `docker cp` a specific engine or catalog jar into a
  running container for a one-off probe.

**The real constraint — engine-side connectors are version-pinned.** You can swap an engine
container freely, but federating it *through Gravitino* only works within a version range
that has a matching Gravitino connector module:

- **Trino:** `trino-connector-{435-439, 440-445, 446-451, 452-468, 469-472, 473-478}`
- **Spark:** `spark-connector/{v3.3, v3.4, v3.5}`
- **Flink:** `flink-common` (pinned Flink 1.18)

Bump Trino to 471 → fine (`469-472`). Bump Spark to 4.0 → the Gravitino Spark connector has
no module for it yet, so federation breaks until a `spark-4` module exists. **Escape hatch:**
an engine can always point at the **IRC (:9001) directly** as a plain Iceberg REST client —
that path is *not* gated by a Gravitino engine-connector module, so it's how you test a
newer engine (e.g. Spark 4 variant) before the connector catches up.

When you swap, **record the exact tags/versions** used in any result you report.

If you need image internals (base image, entrypoint, exposed ports, env), read the
component's `dev/docker/<type>/Dockerfile` and start script; pull upstream image docs as
needed.

## Smoke test — the canonical probe

```bash
# 1. Create a V3 table with a variant column via the IRC (:9001)
curl -s -X POST http://localhost:9001/iceberg/v1/namespaces \
  -H 'Content-Type: application/json' -d '{"namespace":["v3_test"]}'
curl -s -X POST http://localhost:9001/iceberg/v1/namespaces/v3_test/tables \
  -H 'Content-Type: application/json' -d '{
    "name":"variant_probe",
    "schema":{"type":"struct","schema-id":0,"fields":[
      {"id":1,"name":"id","required":true,"type":"long"},
      {"id":2,"name":"payload","required":false,"type":"variant"}]},
    "properties":{"format-version":"3"}}'

# 2. Round-trip via the IRC (should return the variant field unchanged)
curl -s http://localhost:9001/iceberg/v1/namespaces/v3_test/tables/variant_probe

# 3. Load through the NATIVE API (:8090) — the surface under test.
#    Register the IRC as a REST-backend catalog (NO warehouse property), then load.
curl -s -X POST http://localhost:8090/api/metalakes/<metalake>/catalogs \
  -H 'Content-Type: application/json' -d '{
    "name":"irc_probe","type":"RELATIONAL","provider":"lakehouse-iceberg",
    "properties":{"catalog-backend":"rest","uri":"http://<irc-host>:9001/iceberg"}}'
curl -s http://localhost:8090/api/metalakes/<metalake>/catalogs/irc_probe/schemas/v3_test/tables/variant_probe

# 4. Cross-engine: read the same table from each engine pointed at the IRC,
#    per docs/iceberg-rest-engine/<engine>.md, and confirm identical results.
```

## Per-engine & connector reference

For each engine, work in this order: (1) the **in-repo IRC recipe** for the exact catalog
config to point it at Gravitino, (2) the **Gravitino connector doc** for
Gravitino-specific behavior/config, (3) the **upstream doc** for the engine's own SQL/API
surface and type support. In-repo links are relative to the repo root.

### Query engines (connect to the IRC, then query)

| Engine | Point-it-at-IRC recipe (in-repo) | Gravitino connector docs (in-repo) | Upstream API / SQL docs |
|---|---|---|---|
| **Trino** | [`docs/iceberg-rest-engine/trino.md`](../../../docs/iceberg-rest-engine/trino.md) | [`docs/trino-connector/`](../../../docs/trino-connector/) (`catalog-iceberg.md`, `sql-support.md`, `configuration.md`) | https://trino.io/docs/current/connector/iceberg.html · https://trino.io/docs/current/language.html |
| **Spark** | [`docs/iceberg-rest-engine/spark.md`](../../../docs/iceberg-rest-engine/spark.md) | [`docs/spark-connector/spark-catalog-iceberg.md`](../../../docs/spark-connector/spark-catalog-iceberg.md) | https://iceberg.apache.org/docs/latest/spark-configuration/ · https://spark.apache.org/docs/latest/sql-ref.html |
| **Flink** | [`docs/iceberg-rest-engine/flink.md`](../../../docs/iceberg-rest-engine/flink.md) | [`docs/flink-connector/flink-catalog-iceberg.md`](../../../docs/flink-connector/flink-catalog-iceberg.md) | https://iceberg.apache.org/docs/latest/flink/ · https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/sql/overview/ |
| **Doris** | [`docs/iceberg-rest-engine/doris.md`](../../../docs/iceberg-rest-engine/doris.md) | [`docs/jdbc-doris-catalog.md`](../../../docs/jdbc-doris-catalog.md) | https://doris.apache.org/docs/lakehouse/catalogs/iceberg-catalog |
| **StarRocks** | [`docs/iceberg-rest-engine/starrocks.md`](../../../docs/iceberg-rest-engine/starrocks.md) | [`docs/jdbc-starrocks-catalog.md`](../../../docs/jdbc-starrocks-catalog.md) | https://docs.starrocks.io/docs/data_source/catalog/iceberg/iceberg_catalog/ |
| **PyIceberg** | [`docs/iceberg-rest-engine/pyiceberg.md`](../../../docs/iceberg-rest-engine/pyiceberg.md) | — | https://py.iceberg.apache.org/ |
| **Ray** | [`docs/iceberg-rest-engine/ray.md`](../../../docs/iceberg-rest-engine/ray.md) | — | https://docs.ray.io/en/latest/data/api/doc/ray.data.read_iceberg.html |
| **Daft** | — | [`docs/daft-connector/daft-connector.md`](../../../docs/daft-connector/daft-connector.md) | https://docs.getdaft.io/ |

### Gravitino catalog connectors (server-side plugins / native metadata API)

These run inside the Gravitino server; their docs describe catalog properties and the
type mapping each performs (the surface your metadata/type changes touch).

| Provider | Catalog docs (in-repo) | Upstream project |
|---|---|---|
| Iceberg (lakehouse) | [`docs/lakehouse-iceberg-catalog.md`](../../../docs/lakehouse-iceberg-catalog.md) · [`docs/iceberg-rest-service.md`](../../../docs/iceberg-rest-service.md) | https://iceberg.apache.org/docs/latest/ |
| Paimon (lakehouse) | [`docs/lakehouse-paimon-catalog.md`](../../../docs/lakehouse-paimon-catalog.md) | https://paimon.apache.org/docs/master/ |
| Hudi (lakehouse) | [`docs/lakehouse-hudi-catalog.md`](../../../docs/lakehouse-hudi-catalog.md) | https://hudi.apache.org/docs/overview |
| Hive | [`docs/apache-hive-catalog.md`](../../../docs/apache-hive-catalog.md) | https://hive.apache.org/ |
| Doris (JDBC) | [`docs/jdbc-doris-catalog.md`](../../../docs/jdbc-doris-catalog.md) | https://doris.apache.org/docs/ |
| MySQL / PostgreSQL (JDBC) | [`docs/jdbc-mysql-catalog.md`](../../../docs/jdbc-mysql-catalog.md) · [`docs/jdbc-postgresql-catalog.md`](../../../docs/jdbc-postgresql-catalog.md) | engine docs |
| ClickHouse / OceanBase / Hologres / StarRocks (JDBC) | `docs/jdbc-{clickhouse,oceanbase,hologres,starrocks}-catalog.md` | engine docs |

### Spec / protocol

- **Iceberg REST Catalog OpenAPI spec** (what the IRC implements): https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml
- **Gravitino native REST API** (the `:8090` surface): [`docs/open-api/`](../../../docs/open-api/)

> Not sure where an engine's behavior is defined? Search order: in-repo recipe → Gravitino
> connector doc → upstream engine doc → the engine's source at the pinned version in
> `gradle/libs.versions.toml`. If a doc link 404s (upstream reorganizes often), search the
> project site for "iceberg rest catalog".

## Future work (not built yet)

This is a manual/semi-automatic playground today. A likely evolution — capture the intent,
build later:

- **Agentic version sweeps** — let an agent swap engine/catalog versions across a matrix
  (e.g. Trino 435 → 471, Spark 3.5 → 4.0 direct-to-IRC) and diff the per-engine result for a
  given type, to find exactly where behavior changes. The version-swap + direct-IRC escape
  hatch above are the primitives this would drive.
- **Promote to a Gradle IT** — once a probe is worth keeping, port it to a
  testcontainers-based module (see `iceberg/iceberg-rest-trino-it`) so CI runs it.

## Pointers index

- Built-in IRC: [`iceberg/iceberg-rest-server/`](../../../iceberg/iceberg-rest-server/) · config `conf/gravitino-iceberg-rest-server.conf.template`
- Docker builds: [`dev/docker/`](../../../dev/docker/) · `build-docker.sh`
- Engine wiring: [`docs/iceberg-rest-engine/`](../../../docs/iceberg-rest-engine/)
- Versions: [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml)
- Existing programmatic e2e precedent: [`iceberg/iceberg-rest-trino-it/`](../../../iceberg/iceberg-rest-trino-it/)
- Playground base repo: https://github.com/apache/gravitino-playground
