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

# Governed encryption customer journey

This customer journey proves the complete POC path:

```text
REST or Spark -> Gravitino policy dispatcher -> lakehouse REST client
              -> standalone Gravitino IRC -> shared warehouse
Spark <- IRC /v1/config; Spark -> OpenBao Transit key wrapping
```

Start with a fresh cluster:

```shell
./dev/docker/governed-encryption-demo/up.sh
```

The guided runner pauses at every story beat so the operator can inspect each result:

```shell
./dev/docker/governed-encryption-demo/demo-cuj.sh
```

Use `--no-pause` for repeatable automated verification. The commands below are the same operations
performed by the runner and can be copied individually. Set the endpoints first:

```shell
API=http://localhost:8090/api
IRC=http://localhost:9001/iceberg
```

## Verify REST-served KMS configuration and the token boundary

Iceberg 1.11+ clients consume the allowlisted KMS implementation, non-secret endpoint, and Transit
mount from the IRC catalog handshake. The credential source is client-local and is never accepted
from IRC:

```shell
curl -sS "$IRC/v1/config" | jq '
  ((.defaults // {}) + (.overrides // {})) as $config
  | {
      kmsImpl: $config["encryption.kms-impl"],
      kmsType: $config["encryption.kms-type"],
      openBaoEndpoint: $config["encryption.kms.openbao.endpoint"],
      transitMount: $config["encryption.kms.openbao.transit-mount"],
      tokenFileServedByIrc: $config["encryption.kms.openbao.token-file"]
    }'

docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest test ! -e /run/secrets/kms/token
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T gravitino test ! -e /run/secrets/kms/token
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T spark test -s /run/secrets/kms/token
```

Expected: the response supplies `encryption.kms-impl` (or `encryption.kms-type`), the internal
OpenBao endpoint, and Transit mount `transit`, while `tokenFileServedByIrc` is null. Spark pins the endpoint and
`/run/secrets/kms/token` in its own configuration and rejects an IRC attempt to override either.
Only Spark mounts that file. Gravitino and IRC can catalog encrypted metadata but cannot unwrap
keys.

## 1. Create the REST-backed Iceberg namespace

Create metalake `demo`, an Iceberg catalog that routes physical operations through IRC, and schema
`customer_data`:

```shell
curl -sS -H 'Content-Type: application/json' -d \
  '{"name":"demo","comment":"Governed encryption demo","properties":{}}' \
  "$API/metalakes" | jq .

curl -sS -H 'Content-Type: application/json' -d '{
  "name":"lakehouse",
  "type":"RELATIONAL",
  "provider":"lakehouse-iceberg",
  "comment":"Policy-governed Iceberg catalog routed through IRC",
  "properties":{
    "catalog-backend":"rest",
    "uri":"http://iceberg-rest:9001/iceberg",
    "table-metadata-cache-impl":""
  }
}' "$API/metalakes/demo/catalogs" | jq .

curl -sS -H 'Content-Type: application/json' -d \
  '{"name":"customer_data","comment":"Customer dataset governed by PII","properties":{}}' \
  "$API/metalakes/demo/catalogs/lakehouse/schemas" | jq .

curl -sS "$API/metalakes/demo/catalogs/lakehouse/schemas/customer_data" | jq .
```

The catalog intentionally uses `catalog-backend=rest`. This keeps the PRD's IRC hop visible while
the IRC SQLite JDBC catalog and shared `file:///warehouse` volume keep the demo small and persist
real Iceberg `*.metadata.json` files for inspection. There are no
engine-specific KMS properties in this create request; Spark learns them from IRC's `/v1/config`.

## 2. Create and inspect the tag and enabled policy

```shell
curl -sS -H 'Content-Type: application/json' -d \
  '{"name":"PII","comment":"Personally identifiable information","properties":{}}' \
  "$API/metalakes/demo/tags" | jq .
curl -sS "$API/metalakes/demo/tags/PII" | jq .

curl -sS -H 'Content-Type: application/json' -d '{
  "name":"customer_data_encryption",
  "comment":"Require the approved Transit key for PII tables",
  "policyType":"system_iceberg_encryption",
  "enabled":true,
  "content":{
    "schemaVersion":1,
    "tag":"PII",
    "required":true,
    "allowedKeyIds":["customer-pii-v1"],
    "enforcement":"deny-create"
  }
}' "$API/metalakes/demo/policies" | jq .
curl -sS "$API/metalakes/demo/policies/customer_data_encryption" | jq .
```

## 3. Tag the exact schema

```shell
curl -sS -H 'Content-Type: application/json' -d \
  '{"tagsToAdd":["PII"],"tagsToRemove":[]}' \
  "$API/metalakes/demo/objects/schema/lakehouse.customer_data/tags" | jq .
curl -sS "$API/metalakes/demo/objects/schema/lakehouse.customer_data/tags" | jq .
```

This is a direct, exact schema association. Catalog-level and nested-schema inheritance are outside
this POC.

## 4. Prove typed, atomic rejection

```shell
curl -sS -o /tmp/denied-table.json -w 'HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' -d '{
    "name":"unapproved_customer_records",
    "columns":[
      {"name":"id","type":"long","nullable":false,"autoIncrement":false},
      {"name":"marker","type":"string","nullable":true,"autoIncrement":false}
    ],
    "properties":{"format-version":"3","encryption.key-id":"unapproved-key"}
  }' "$API/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables"
jq '{code, type, message}' /tmp/denied-table.json

curl -sS -o /tmp/absent-table.json -w 'HTTP %{http_code}\n' \
  "$API/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/unapproved_customer_records"
jq '{code, type, message}' /tmp/absent-table.json

curl -sS "$IRC/v1/namespaces/customer_data/tables" | jq .
```

Expected: HTTP `400`, type `EncryptionPolicyViolationException`, reason `KEY_NOT_ALLOWED`, then HTTP
`404` from Gravitino. The direct IRC list also omits the denied table, proving rejection happened
before downstream creation.

## 5. Prove Spark cannot omit the required key

Use the supported Spark-through-Gravitino path, but deliberately omit `encryption.key-id`:

```shell
./dev/docker/governed-encryption-demo/spark-sql.sh \
  -f /opt/demo/spark-missing-key.sql

curl -sS "$IRC/v1/namespaces/customer_data/tables/missing_key_customer_records" | jq .
```

Expected: Spark reports `KEY_REQUIRED` and IRC returns HTTP `404`. This is the client-facing proof
that a tagged, governed schema requires encryption even when the Spark caller supplies no key
properties.

## 6. Create and write the approved table through Spark

```shell
./dev/docker/governed-encryption-demo/spark-sql.sh -f /opt/demo/spark-cuj.sql
```

Expected: `APPROVED_SPARK_WRITE_OK` and one row written.

## 7. Prove IRC remains a keyless metadata service

Normal metadata loading remains available, but server-side readers fail closed before touching
encrypted manifests or data:

```shell
curl -sS -o /tmp/encrypted-load.json -w 'load HTTP %{http_code}\n' \
  "$IRC/v1/namespaces/customer_data/tables/encrypted_customer_records"
jq '{
  metadataLocation: .["metadata-location"],
  keyId: .metadata.properties["encryption.key-id"]
}' /tmp/encrypted-load.json

curl -sS -o /tmp/encrypted-plan.json -w 'plan HTTP %{http_code}\n' \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "$IRC/v1/namespaces/customer_data/tables/encrypted_customer_records/plan"
jq '.error | {code, type, message}' /tmp/encrypted-plan.json
```

Expected: load-table returns HTTP `200`. Plan scan returns typed HTTP `400`, type
`EncryptedTableServerSideReadException`, and reason
`ENCRYPTED_TABLE_SERVER_READ_UNSUPPORTED`. This is an intentional reader guard, not a 500 caused by
missing keys: IRC has no token and does not attempt to read encrypted AGS1/PARE objects.

## 8. Prove the key ID is immutable

Attempt the same forbidden property change through Gravitino and directly through IRC:

```shell
curl -sS -X PUT -H 'Content-Type: application/json' -d '{
  "updates":[{
    "@type":"setProperty",
    "property":"encryption.key-id",
    "value":"unapproved-key"
  }]
}' "$API/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/encrypted_customer_records" \
  | jq '{code, type, message}'

curl -sS -X POST -H 'Content-Type: application/json' -d '{
  "requirements":[],
  "updates":[{
    "action":"set-properties",
    "updates":{"encryption.key-id":"unapproved-key"}
  }]
}' "$IRC/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  | jq '.error | {code, type, message}'

curl -sS "$IRC/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  | jq '.metadata.properties["encryption.key-id"]'

docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  logs --no-color --tail 200 gravitino iceberg-rest | grep -Ei 'key ID|encryption.key-id'
```

Expected: both writes return typed HTTP `400` errors. Reloading from IRC still prints
`customer-pii-v1`. The demo prints matching service log lines when available; typed error bodies are
the fallback evidence because this POC has no durable audit sink for property-update rejections.

## 9. Inspect IRC and at-rest evidence

IRC must return the table, metadata location, format version 3, approved key ID, and encryption keys:

```shell
curl -sS "$IRC/v1/namespaces/customer_data/tables" | jq .
curl -sS "$IRC/v1/namespaces/customer_data/tables/encrypted_customer_records" | jq '{
  metadataLocation: .["metadata-location"],
  formatVersion: .metadata["format-version"],
  keyId: .metadata.properties["encryption.key-id"],
  encryptionKeys: [
    .metadata["encryption-keys"][]
    | {
        keyId: .["key-id"],
        encryptedById: .["encrypted-by-id"],
        encryptedKeyMetadata: "<withheld>"
      }
  ],
  responseConfig: ((.config // {}) | with_entries(
    select(
      .key == "encryption.kms-impl"
      or .key == "encryption.kms-type"
      or .key == "encryption.kms.openbao.endpoint"
      or .key == "encryption.kms.openbao.transit-mount")))
}'
```

List every object and its size, then inspect text metadata and only the first 64 bytes of binary
objects. The `cat` command intentionally displays the base64-encoded wrapped key metadata stored in
`metadata.json`; it does not display the plaintext key or KMS token:

```shell
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c 'find /warehouse -type f -print | sort'
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c 'find /warehouse -type f -exec ls -lh {} \; | sort -k9'

METADATA_FILE=$(docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c "find /warehouse -name '*.metadata.json' -type f -print | sort" \
  | tail -n 1 | tr -d '\r')
DATA_FILE=$(docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c "find /warehouse -name '*.parquet' -type f -print | sort" \
  | head -n 1 | tr -d '\r')
AVRO_FILE=$(docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c "find /warehouse -name '*.avro' -type f -print | sort" \
  | head -n 1 | tr -d '\r')

docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest cat "$METADATA_FILE"
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c 'head -c 64 "$1" | od -An -tx1c' _ "$DATA_FILE"
docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest sh -c 'head -c 64 "$1" | od -An -tx1c' _ "$AVRO_FILE"

docker compose -f dev/docker/governed-encryption-demo/docker-compose.yaml \
  exec -T iceberg-rest grep -aF 'governed-encryption-poc-marker' "$DATA_FILE" "$AVRO_FILE"
```

Expected: data starts `PARE`, manifest/list Avro starts `AGS1`, and `grep` returns no match. Never
raw-`cat` the full binary objects.

The runner additionally base64-decodes `encrypted-key-metadata`, verifies its `vault:v1:` Transit
envelope, and asks Transit to unwrap that envelope with the generated least-privilege token. It
does not reprint the decoded envelope and asserts non-empty key material without printing the token
or plaintext key.

`bao decrypt` cannot decrypt the Parquet object. Transit unwraps the Iceberg table/snapshot key
envelope; Iceberg uses that key hierarchy and per-file metadata to decrypt the `PARE` and `AGS1`
formats. Sending an entire encrypted file to Transit confuses those separate layers.

## 10. Finish with a fresh, cache-invalidated Spark read

```shell
./dev/docker/governed-encryption-demo/spark-sql.sh -f /opt/demo/spark-readback.sql
```

Expected: `APPROVED_SPARK_READ_OK`, ID `1`, and `governed-encryption-poc-marker`. This final read
shows that a new Spark session can load valid IRC encryption metadata, unwrap through Transit, and
let Iceberg decrypt the stored row.

## 11. Map the out-of-scope direct IRC bypass boundary

The primary CUJ uses the supported Spark-through-Gravitino path. Run this separate checkpoint to
make the current trust boundary visible:

```shell
./dev/docker/governed-encryption-demo/direct-irc-boundary.sh
```

It points a second Spark catalog directly at IRC, omits all encryption table properties, and proves
that the create currently succeeds. The authoritative IRC response has neither
`encryption.key-id` nor `encryption-keys`; the data object starts with plaintext Parquet `PAR1`
rather than encrypted `PARE`, and its uncompressed marker is visible in raw bytes.

This is **OUT-OF-SCOPE DIRECT IRC BYPASS BOUNDARY** evidence, not the supported client workflow.
DAT-232 governs Spark and REST table creates that enter through Gravitino; direct IRC create gating
is intentionally outside this implementation. The plaintext result maps a gap in governed coverage,
not an acceptance failure for the scoped create path.

## 12. Optional advanced checkpoint: rotate the Transit key

This checkpoint is deliberately separate from the primary CUJ. It rotates the backing Transit key
without changing the logical `encryption.key-id`, proves the original `vault:v1:` envelope remains
readable, and creates a new table to inspect the version of a newly wrapped table key:

```shell
./dev/docker/governed-encryption-demo/rotation-checkpoint.sh
```

The underlying admin operation is a Vault-compatible REST call. The default token below is only
the disposable root token of this local dev-mode container:

```shell
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' -X POST \
  -H "X-Vault-Token: ${TRANSIT_ROOT_TOKEN:-gravitino-transit-demo-root}" \
  http://localhost:18200/v1/transit/keys/customer-pii-v1/rotate
```

The key ID remains `customer-pii-v1`. An existing `vault:v1:` envelope stays decryptable because
Transit retains older key versions; a newly wrapped table key can carry `vault:v2:`. A later write
to the *same* table does not necessarily emit v2 immediately because the client may reuse that
table's already wrapped key-encryption key. This POC does not implement bulk rewrap of existing
Iceberg metadata or objects.

Direct calls to IRC remain an explicit POC boundary: they bypass the current Gravitino table-create
dispatcher and therefore bypass this policy check. Stop and delete all ephemeral state with:

```shell
./dev/docker/governed-encryption-demo/down.sh
```
