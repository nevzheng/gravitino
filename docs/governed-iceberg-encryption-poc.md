---
title: "Governed Iceberg Encryption POC"
slug: "/governed-iceberg-encryption-poc"
date: 2026-07-14
keyword: "iceberg, encryption, policy, KMS, Spark, Gravitino"
license: "This software is licensed under the Apache License version 2."
---

## Status

This is an experimental proof of concept. It demonstrates policy-gated table declarations through
the Gravitino REST API and an end-to-end encrypted create, write, and read through the Gravitino
Spark connector. It is not a production KMS or a complete policy-resolution design.

## Customer journey

1. An administrator creates and enables a metalake policy of type
   `system_iceberg_encryption`. The policy identifies a tag, requires encryption, and lists the
   exact key IDs that may be used.
2. The administrator associates that tag directly with an Iceberg schema. In this POC, a schema is
   the dataset/database namespace that contains tables.
3. A REST or Spark client requests a table under that schema and supplies Iceberg table properties
   `format-version=3` and `encryption.key-id=<approved-key-id>`. The demo uses REST to prove typed,
   atomic policy rejection and Spark to perform the approved create and encrypted write.
4. Before any physical table or Gravitino table entity is created, Gravitino evaluates the enabled
   policy against the exact schema tag and the supplied key ID.
5. If governance allows the request, Iceberg asks the cluster KMS to wrap the table data-encryption
   key. The KMS keeps its key-encryption key inside the KMS container.
6. Spark writes and reads the encrypted Iceberg table normally.

The policy allowlist and KMS validation are separate checks. A disallowed key ID is rejected by
Gravitino before table creation. An allowed but nonexistent key ID passes governance and is then
rejected by the KMS when Iceberg tries to use it.

## Request sequence

```mermaid
sequenceDiagram
    autonumber
    actor Admin
    participant Client as "REST or Spark client"
    participant Gravitino
    participant Evaluator as "Encryption policy evaluator"
    participant Iceberg as "Iceberg catalog"
    participant KMS as "OpenBao Transit"
    participant Storage

    Admin->>Gravitino: Create enabled encryption policy in metalake
    Admin->>Gravitino: Add PII tag directly to schema
    Client->>Gravitino: Create format-v3 table with encryption.key-id
    Gravitino->>Evaluator: Evaluate normalized table create
    Evaluator->>Evaluator: Match exact schema tag and enabled policy
    Evaluator->>Evaluator: Compare exact, case-sensitive key ID
    alt Missing or disallowed key and enforcement is deny-create
        Evaluator-->>Gravitino: EncryptionPolicyViolationException with decisionId
        Gravitino-->>Client: HTTP 400; no physical or stored table
    else Violation and enforcement is report
        Evaluator->>Evaluator: Write structured warning with decisionId
        Evaluator-->>Gravitino: Allow for observation
        Gravitino->>Iceberg: Create table
        Iceberg-->>Client: Table created
    else Compliant request
        Evaluator-->>Gravitino: Allow
        Gravitino->>Iceberg: Create table
        Client->>Iceberg: Write through Spark
        Iceberg->>KMS: Wrap data-encryption key with approved key ID
        KMS-->>Iceberg: Versioned encrypted key envelope
        Iceberg->>Storage: Write plaintext metadata.json and encrypted manifests/data
        Storage-->>Client: Read encrypted table through Iceberg
    end
```

## Policy content

| Field | Required | Default | POC behavior |
|---|---|---|---|
| `schemaVersion` | Yes | none | Must be `1`. |
| `tag` | Yes | none | Exact tag name associated directly with the schema. |
| `required` | No | `true` | A missing `encryption.key-id` is a violation when `true`. |
| `allowedKeyIds` | Yes when required | none | Exact, case-sensitive, duplicate-free key IDs. |
| `enforcement` | No | `report` | `report` logs and allows; `deny-create` logs and rejects. |

## REST example

The example assumes metalake `demo`, Iceberg catalog `lakehouse`, and schema `customer_data`
already exist.

Create the tag:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "PII",
    "comment": "Personally identifiable information",
    "properties": {}
  }' \
  http://localhost:8090/api/metalakes/demo/tags
```

Create and immediately enable the metalake policy:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "customer_data_encryption",
    "comment": "Require an approved Iceberg encryption key for PII tables",
    "policyType": "system_iceberg_encryption",
    "enabled": true,
    "content": {
      "schemaVersion": 1,
      "tag": "PII",
      "required": true,
      "allowedKeyIds": ["customer-pii-v1"],
      "enforcement": "deny-create"
    }
  }' \
  http://localhost:8090/api/metalakes/demo/policies
```

Associate `PII` directly with the exact schema:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -d '{"tagsToAdd": ["PII"], "tagsToRemove": []}' \
  http://localhost:8090/api/metalakes/demo/objects/schema/lakehouse.customer_data/tags
```

Create a compliant table:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "customers",
    "comment": "Encrypted customer records",
    "columns": [
      {
        "name": "id",
        "type": "long",
        "nullable": false,
        "autoIncrement": false
      },
      {
        "name": "email",
        "type": "string",
        "nullable": true,
        "autoIncrement": false
      }
    ],
    "properties": {
      "format-version": "3",
      "encryption.key-id": "customer-pii-v1"
    }
  }' \
  http://localhost:8090/api/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables
```

Changing the key ID to `unapproved-key` returns HTTP `400` with error type
`EncryptionPolicyViolationException` and a `decisionId`. The downstream table create is not called.

## Spark example

The demo cluster runs the official Go-based OpenBao image and enables its Transit secrets engine.
The standalone IRC advertises an explicit, non-secret KMS configuration allowlist from its
Iceberg REST `/v1/config` response. Iceberg 1.11+ consumes the implementation, endpoint, and Transit
mount when the Spark catalog is initialized. Spark pins the endpoint and token-file path locally,
and the connector rejects an IRC attempt to override either. The Gravitino catalog request does not duplicate them as
`gravitino.bypass.*` or `spark.bypass.*` properties.

```text
IRC default: encryption.kms-impl=org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient
IRC default and Spark local pin: encryption.kms.openbao.endpoint=http://openbao-kms:8200
IRC default: encryption.kms.openbao.transit-mount=transit
Spark local only: encryption.kms.openbao.token-file=/run/secrets/kms/token
```

The token-file path is never served by IRC. Only Spark mounts a token at that path. Neither
Gravitino nor IRC receives a KMS token or packages the KMS client; the shaded Spark runtime owns the
OpenBao adapter and the Iceberg REST encryption bridge. Token contents and arbitrary
`encryption.kms.*` properties are never returned by IRC.

With the Gravitino Spark connector configured, the table request follows the same policy path:

```sql
USE lakehouse.customer_data;

CREATE TABLE customers_from_spark (
  id BIGINT,
  email STRING
) USING iceberg
TBLPROPERTIES (
  'format-version' = '3',
  'encryption.key-id' = 'customer-pii-v1'
);

INSERT INTO customers_from_spark VALUES (1, 'alice@example.com');
SELECT * FROM customers_from_spark;
```

## Create-time key and server-reader guards

`encryption.key-id` is a create-time declaration. Gravitino table alters and direct IRC metadata
updates that set or remove that property are rejected with a typed HTTP `400` error and a
correlated decision ID. Other `encryption.*` properties remain pass-through so the implementation
does not freeze Iceberg's growing encryption vocabulary.

IRC may load and serve the unencrypted Iceberg `metadata.json`, but it is not an authorized data
reader. Operations that currently open encrypted manifests or data server-side—scan planning and
purge—detect `encryption.key-id` first and return a typed
`EncryptedTableServerSideReadException` with reason
`ENCRYPTED_TABLE_SERVER_READ_UNSUPPORTED`. Metadata-only load and non-purge drop remain supported.
Future compaction or statistics implementations that read Iceberg objects must use the same guard
until a separately authorized delegated-read design exists.

## Tamper-evident metadata follow-up

Iceberg leaves `metadata.json` unencrypted. The production design therefore still requires
Gravitino to hash the exact committed bytes, atomically store the digest with the trusted
metadata-location pointer in its relational backend, and verify the digest before every cold parse
or cache fill. A mismatch must fail with a typed storage-integrity error and an audit event; a
verified parsed cache entry may be trusted for its cache lifetime. Keeping the pointer and digest
in the Gravitino database makes that database the trust root and prevents a bucket writer from
silently rolling back or swapping metadata snapshots.

This POC does **not** claim that property. Its standalone IRC uses a disposable SQLite JDBC database
in the same Docker volume as the warehouse so the CUJ can locate and inspect real
`metadata.json` files. It does not store or verify digests, and a sidecar checksum beside the object
would not provide the required independent trust root or atomic transaction. The production work is
tracked separately by DAT-163 (contract and backend matrix), DAT-229 (commit-time trusted identity),
DAT-168 (cold-load and cache verification), and DAT-230 (registration, recovery, and failure
behavior).

## POC boundaries

- Applicability is create-only. Creating or enabling a policy does not scan, rewrite, or otherwise
  retroactively encrypt existing tables or data.
- Only tags directly associated with the exact schema are evaluated. Catalog-level and nested
  schema tag inheritance are follow-up work.
- Policy enforcement is limited to catalogs whose provider is `lakehouse-iceberg`.
- Applicability is evaluated on create only. A table renamed or moved from an untagged schema into
  a tagged schema is not retroactively evaluated in this POC.
- At most one enabled encryption policy may match. Multiple matches fail closed as an ambiguous
  configuration; production precedence and exception rules are follow-up work.
- `report` writes a structured warning. There is no durable violation-report API yet.
- Existing administration authority controls policy updates; this POC does not introduce RBAC.
- Direct IRC creates do not enter Gravitino's policy dispatcher and can currently create an
  unencrypted table. The demo exposes this as the **OUT-OF-SCOPE DIRECT IRC BYPASS BOUNDARY**;
  direct IRC key-ID updates are still rejected by the immutability guard.
- KMS availability validates an allowed key only when Iceberg attempts to wrap a table key. The
  policy allowlist performs exact ID matching and does not auto-inject a key.
- Tamper-evident `metadata.json` verification is required production follow-up work, not a feature
  proven by this disposable SQLite POC.
- The demo uses OpenBao dev mode and plain HTTP only inside the isolated demo network. Dev mode is
  ephemeral: restarting it invalidates previously wrapped table keys. Production requires durable
  OpenBao storage, TLS, workload identity, unseal and recovery procedures, availability, and
  durable audit.
