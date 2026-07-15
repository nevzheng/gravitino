# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# OpenAPI customer-journey tests

This directory defines API-only customer-journey (CUJ) tests for the versioned Gravitino API.
They use a standalone Playwright `APIRequestContext` as the HTTP transport and `pytest-bdd` for
Gherkin. They never create a page, launch a browser, or exercise the Web UI.

The generated `gravitino_client` is the typed read-back client once an operation is present in
the V1 contract. Playwright remains the raw transport for a journey so that the test can inspect
the exact request, status, headers, public error envelope, and retryability classification.

## Scope

The suite has two complementary V1 API-only CUJ families:

1. `v1_hierarchy` is real `pytest-bdd`/Gherkin coverage for the route-versioned resource tree.
   It visibly drives metalake, catalog, schema, and table create/GET/list/full conditional PUT/
   DELETE through Playwright's `APIRequestContext`. The table outline provisions a disposable V1
   hierarchy for each configured writable connector profile; no legacy `/api` route counts as
   setup or fallback coverage.
2. `create_table` covers documented table-create behavior across each relational connector,
   rather than attempting a single Cartesian product of every optional field. The provider profile
   selects only combinations that the connector documentation describes as meaningful.

| Profile      | Documented create-table focus                                                            |
| ------------ | ---------------------------------------------------------------------------------------- |
| `lance`      | Managed, external, declared, registered, and create-mode table lifecycle.                |
| `delta`      | Registration of an existing external Delta table.                                        |
| `iceberg`    | Partition transforms, sort expressions, hash/range distribution, and Iceberg properties. |
| `hive`       | Identity partitioning, hash distribution, sort order, storage properties.                |
| `glue`       | Hive- and Iceberg-format table variants, partitioning, properties, hash distribution.    |
| `paimon`     | Identity partitioning, primary keys, and Paimon table properties.                        |
| `hudi`       | Read-only negative capability: table creation is unsupported.                            |
| `mysql`      | Defaults, auto-increment, primary and unique indexes.                                    |
| `postgresql` | Defaults, auto-increment, primary and unique indexes.                                    |
| `doris`      | Required distribution, partitions, indexes, and defaults.                                |
| `starrocks`  | Required distribution, partitions, and defaults; indexes are a documented limitation.    |
| `oceanbase`  | Defaults, auto-increment, primary and unique indexes.                                    |
| `clickhouse` | Engine/properties, partitioning, sort order, and primary keys.                           |
| `hologres`   | Distribution, partitioning, indexes, and connector-specific properties.                  |

The scenarios are derived from the relational metadata guide and the connector documentation:

- `docs/manage-relational-metadata-using-gravitino.md`
- `docs/table-partitioning-distribution-sort-order-indexes.md`
- `docs/apache-hive-catalog.md`, `docs/aws-glue-catalog.md`
- `docs/lakehouse-{iceberg,paimon,hudi}-catalog.md`
- `docs/lakehouse-generic-{lance,delta}-table.md`
- `docs/jdbc-{mysql,postgresql,doris,starrocks,oceanbase,clickhouse,hologres}-catalog.md`

## Division of labor

The suite has three layers:

1. **Provider profiles** declare the supported use cases, prerequisites, payload traits, and
   expected public errors. They are the source of truth for whether a scenario applies to a
   connector.
2. **Fixtures and lifecycle helpers** provision a disposable V1 hierarchy, create isolated names,
   own conditional cleanup, and expose a standalone Playwright request context. They do not hide
   API calls in a Java client.
3. **BDD steps** execute a named use case, verify the immediate API outcome, then verify the
   durable postcondition through V1 list/load operations when those operations exist.

The same feature can therefore run against a local Lance profile or a CI-provisioned JDBC/HMS
profile without copying the scenario logic. A profile that has not been configured is skipped;
an unsupported connector feature is a documented, non-blocking expected outcome, not a false
failure.

`minimal` means the smallest **supported** request for that provider, not a universally bare
request. For example, Doris and StarRocks need a documented distribution; ClickHouse needs an
engine-compatible key/order combination; and MySQL/OceanBase auto-increment needs a primary or
unique key in the same request. Paimon primary-key fields must differ from partition fields, and
Glue complex-schema cases must not request `NOT NULL` columns.

## Error policy

Negative contract scenarios are assertions: they require the documented HTTP status, public
`error.type`, `error.retryable`, request ID, and relevant field/resource detail. Error messages
are diagnostic only and are never parsed.

Some provider-specific outcomes are known limitations or a temporary V1 migration gap. Those are
recorded as an `expected_error` observation. If the API returns the profile's declared public
error envelope, the runner emits a warning with its request ID and lets the suite continue. A
successful response is treated as an improvement. A different error remains a failure because it
is neither the documented contract nor a known limitation.

This is intentionally different from `xfail`: expected connector errors remain visible in test
output and do not mask an unrelated regression.

## Target lifecycle contract

V1 now has collection `POST`/`GET` and item `GET`/full `PUT`/conditional `DELETE` for metalakes,
catalogs, schemas, and tables. Every mutating CUJ obtains a strong ETag from an item `GET` and
sends it in `If-Match`; stale and missing validators are strict `412 PRECONDITION_FAILED` contract
assertions. A configured route that is absent, rejects the requested method, or fails unexpectedly
is a normal CUJ failure. The suite never substitutes a legacy `/api/...` operation for V1 coverage.

The CUJs deliberately distinguish supported full desired-state replacement from documented
capability gaps:

- Schema property replacement is supported; changing the existing schema comment is currently a
  visible, warning-only `501 UNSUPPORTED_OPERATION` observation until the core has a correct
  mutation primitive.
- Table comment/property replacement is supported and asserted. A replacement that changes
  columns, partitioning, distribution, sort order, or indexes is currently a visible,
  warning-only `501 UNSUPPORTED_OPERATION` observation for the same reason.

Those are the only non-blocking hierarchy observations. Ordinary validation, authentication,
authorization, stale/missing precondition, route, and provider regressions remain failures.

For one configured provider, set `GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE` to the V1 catalog-create
JSON body. A table lifecycle outline can instead use
`GRAVITINO_OPENAPI_CUJ_<PROVIDER>_CATALOG_CREATE` for provider-specific hierarchy setup; that value
takes precedence over the generic fallback.

## Typed table-contract CUJs

`v1_typed_table/lifecycle.feature` is the forward-looking table-create and
round-trip matrix. It deliberately does **not** reuse the current generic
table-property map. A typed POST has shared `storage` fields—`ownership`,
`tableFormat`, `location`, and `fileFormat`—plus zero or one of
`icebergOptions`, `hiveOptions`, `clickhouseOptions`, or `mysqlOptions`.
The lifecycle reads the table, sends a complete conditional `PUT` that changes
only the comment, verifies the storage/options round trip, then conditionally
deletes it.

| Profile | Minimal typed shape | Live expectation |
| --- | --- | --- |
| Lance | Managed `storage` plus fixture table location | Full lifecycle |
| Delta | External `storage` with existing Delta dataset location | POST creates Gravitino metadata; PUT limitation is a visible non-blocking observation |
| Iceberg | Managed Iceberg/Parquet storage plus `icebergOptions.formatVersion` | Full lifecycle |
| Hive | Managed Hive/ORC storage plus `hiveOptions.serdeLibrary` | Full lifecycle |
| Glue | External Hive/Parquet storage plus fixture location | Full lifecycle |
| Paimon | No shared storage claim yet | Full lifecycle when provisioned |
| MySQL | `mysqlOptions.engine` | Full lifecycle |
| PostgreSQL, Doris, StarRocks, OceanBase, Hologres | Shared relational table shape only | Full lifecycle |
| ClickHouse | `clickhouseOptions.engine` | Full lifecycle |
| Hudi | Shared table shape only | `POST` is a non-blocking `501 UNSUPPORTED_OPERATION` observation |

The Gherkin scenarios are intentionally skipped unless all of these are
explicitly supplied:

```text
GRAVITINO_OPENAPI_CUJ_TYPED_TABLE_CONTRACT=true
GRAVITINO_OPENAPI_CUJ_PROFILES=iceberg,...
GRAVITINO_OPENAPI_CUJ_<PROVIDER>_CATALOG_CREATE='{"type":"RELATIONAL",...}'
```

Lance and Glue additionally require
`GRAVITINO_OPENAPI_CUJ_<PROVIDER>_TABLE_LOCATION`; `{table}` in that value is
replaced with the isolated generated table name. Delta requires
`GRAVITINO_OPENAPI_CUJ_DELTA_EXISTING_DATASET_LOCATION`, which must already
contain a valid `_delta_log`. The test suite never guesses credentials,
catalog configuration, or writable storage paths, and it does not claim a
skipped external provider has passed.

## Documented/source differences tracked by the CUJs

The suite keeps documentation and current implementation differences visible instead of treating
them as passing coverage. In particular:

- Lance documentation describes schema discovery during registration with empty columns, while
  the current registration path does not read that schema. The schema-discovery case is a
  non-blocking observation until the V1 contract chooses one behavior.
- Lance documentation lists layout features as unsupported, while an integration test currently
  round-trips some layout metadata. CUJs do not claim physical-layout semantics from that
  round-trip alone.
- The general metadata guide and StarRocks connector documentation disagree about auto-increment.
  The profile treats it as a declared limitation pending reconciliation.
