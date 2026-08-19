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

# Governed Iceberg encryption demo cluster

This directory packages the current checkout into a persistent, manually operable demo cluster:

- the current Gravitino server and lakehouse-Iceberg catalog;
- a standalone current-source Gravitino Iceberg REST Catalog (IRC) service;
- the current shaded Spark 3.5 connector plus Iceberg 1.11.0;
- an IRC SQLite JDBC catalog with a shared local warehouse volume; and
- a PKCS12 keystore bootstrap with one approved wrapping-key alias mounted only into Spark.

Start the cluster from anywhere in the repository:

```shell
./dev/docker/governed-encryption-demo/up.sh
```

`up.sh` builds the current source, starts all containers, waits for their health checks, and then
exits without stopping the cluster. Follow the copy/paste customer
journey in [CUJ.md](./CUJ.md), run the presenter-friendly walkthrough with `./demo-cuj.sh`, or open
an already configured Spark shell with `./spark-sql.sh`. The runner pauses between steps, labels
each request/input/output/evidence block, and colorizes commands and JSON on a TTY. Use
`--no-pause --no-color` for automation. Each run generates one suffix shared by its metalake,
schema, and table names, so the persistent cluster can host repeated runs; pass
`--run-suffix <id>` or `DEMO_RUN_SUFFIX=<id>` to choose a reproducible suffix.

Stop the cluster and remove its ephemeral keystore, metadata, and data with:

```shell
./dev/docker/governed-encryption-demo/down.sh
```

## Stable demo addresses

| Resource | Address |
|---|---|
| Gravitino from the host | `http://localhost:8090` |
| Gravitino inside Compose | `http://gravitino:8090` |
| IRC from the host | `http://localhost:9001/iceberg` |
| IRC inside Compose | `http://iceberg-rest:9001/iceberg` |
| Iceberg warehouse | `file:///warehouse` in the shared `warehouse` volume |
| Spark-only keystore | `/run/secrets/kms/demo.p12` and `/run/secrets/kms/password` in Spark |
| Mounted demo files in Spark | `/opt/demo` |

The engine wraps and unwraps Iceberg DEKs by talking to the keystore directly through
`org.apache.iceberg.encryption.KeystoreKeyManagementClient`. Gravitino and IRC never mount the
keystore and never proxy wrap/unwrap. IRC only advertises the `encryption.kms-impl` class name;
Spark pins the keystore path and password-file locally and rejects an IRC override of those
secrets.

The governed lakehouse-Iceberg catalog uses the REST backend and points to the demo IRC service. IRC
uses a local SQLite JDBC catalog and writes Iceberg metadata and data files to the shared
`/warehouse` volume. The SQLite database is also ephemeral in that volume. This makes the committed
`*.metadata.json` files available for the demo's real list/cat inspection. The resulting create
path is Gravitino policy dispatcher -> lakehouse REST client -> IRC -> local warehouse. Neither
Gravitino nor IRC receives KMS credentials. This
removes Hive and HDFS from the POC without removing the PRD's IRC integration surface. Iceberg v3
encryption is catalog-backend independent. Calling IRC directly remains a POC boundary: a direct IRC
client bypasses the current Gravitino table-create dispatcher and therefore bypasses this policy
check.

## Inspect authoritative Iceberg metadata

The demo publishes IRC directly to the host for inspection. It uses no catalog prefix. Set the run
suffix printed by `demo-cuj.sh`, then load that run's table directly:

```shell
RUN_SUFFIX=<printed-run-suffix>
SCHEMA="customer_data_${RUN_SUFFIX}"
TABLE="encrypted_customer_records_${RUN_SUFFIX}"

curl --fail --silent \
  "http://localhost:9001/iceberg/v1/namespaces/${SCHEMA}/tables/${TABLE}" \
  | jq '{metadata_location: .["metadata-location"], encryption_keys: .metadata["encryption-keys"]}'
```

This inspection intentionally shows the base64-encoded wrapped key metadata. It never shows the
plaintext key or the KMS token.

Treat this IRC response, rather than the requested Gravitino table properties, as the source of
truth for Iceberg encryption metadata. A successful encrypted write has a non-empty
`metadata.encryption-keys` array. The `metadata-location` value is a `file:/warehouse/` URI
(equivalently rendered with three slashes by some clients). That exact path is inside the named
`gravitino-governed-encryption-warehouse` volume mounted by IRC
and Spark. Inspect the committed metadata and object files from the IRC container with:

```shell
docker compose \
  --project-directory dev/docker/governed-encryption-demo \
  --file dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec iceberg-rest find "/warehouse/${SCHEMA}/${TABLE}" -type f -print
```

The CUJ correlates the load-table `metadata-location` with that mounted warehouse path and also
checks the stored file framing. Merely seeing `encryption.key-id` in table properties proves that the
policy supplied a key request; it does not, by itself, prove encryption at rest.

## Acceptance boundaries

| Path | Create policy | Key-ID immutability after create | Expected violation evidence |
|---|---|---|---|
| Gravitino REST and Spark through Gravitino | Required in this POC | Must be enforced | Typed HTTP 400 plus audit decision |
| Direct IRC | Bypasses the POC create dispatcher | Must also be enforced | Typed HTTP 400 plus audit decision |
| Hive-only catalog guard | Not part of this IRC-backed demo | Insufficient for acceptance | N/A |

The direct-IRC create-policy bypass is an explicit POC limitation, not permission to change an
encryption key after table creation. Production acceptance requires both Gravitino-mediated alters
and direct IRC alters to reject changes to `encryption.key-id`; testing only a Hive-backed guard does
not satisfy this IRC path.

## Observe the demo

Every service logs to its container standard output, so a presenter can follow policy, IRC, and Spark
activity without entering a container:

```shell
docker compose \
  --project-directory dev/docker/governed-encryption-demo \
  --file dev/docker/governed-encryption-demo/docker-compose.yaml \
  logs --follow --tail=100 gravitino iceberg-rest spark
```

Remove `--follow` for a finite log snapshot.

The PKCS12 keystore demo does not include Transit-style key-version rotation. Use a fresh run suffix
when you need a clean wrapping key.
