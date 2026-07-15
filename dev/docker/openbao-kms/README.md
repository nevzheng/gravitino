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

# OpenBao KMS demo

This directory starts a local OpenBao 2.6.0 Transit service for the governed-encryption proof of
concept. OpenBao performs the key wrapping; Gravitino only supplies the Iceberg adapter that calls
its Vault-compatible Transit HTTP API.

OpenBao is the checked-in default because its licensing is compatible with Apache project source
and release policy. HashiCorp Vault's BSL-licensed distribution is not pulled by these scripts. An
operator who has independently obtained a compatible local image may test it by setting
`TRANSIT_IMAGE`; compatibility with non-default images is not asserted by this POC:

```shell
TRANSIT_IMAGE=your-local-transit-image:tag docker compose up --wait
```

> **Demo only:** this stack uses OpenBao's in-memory dev mode, a known root token, and plaintext HTTP.
> It loses all keys on restart and must never be exposed to untrusted networks or used for production
> data. A production deployment must use persistent sealed storage, TLS, and workload authentication.

## Quick KMS verification

Run this script from anywhere in the repository for a fast check of the real OpenBao image, Transit
key, and least-privilege token:

```shell
./dev/docker/openbao-kms/smoke-test.sh
```

The smoke test starts this Compose stack, reads the generated client token without displaying it,
proves an encrypt/decrypt round-trip with `customer-pii-v1`, proves the token cannot use
`unapproved-key`, and always removes the containers and token volume. It does not start Gravitino or
Spark and does not exercise policy resolution.

The operator-driven policy + REST + Spark customer journey is packaged separately in
[`../governed-encryption-demo`](../governed-encryption-demo/README.md). That persistent stack is the
recommended interactive demo.

For automated verification of the full policy-to-ciphertext journey, start the packaged cluster and
run its non-interactive CUJ mode:

```shell
./dev/docker/governed-encryption-demo/up.sh
./dev/docker/governed-encryption-demo/demo-cuj.sh --no-pause
```

The same CUJ pauses between steps by default for an operator-driven presentation. Use the packaged
demo's `down.sh` when finished to remove its temporary services, keys, token, and warehouse.

## Start the KMS service manually

```shell
cd dev/docker/openbao-kms
docker compose up --wait
```

The bootstrap container enables Transit, disables automatic key creation, creates the AES-256 key
`customer-pii-v1`, and writes a generated least-privilege client token to the named Docker volume
`gravitino-openbao-kms-client-token`. The client token can only encrypt and decrypt with that exact
key. It cannot create, modify, list, export, or rotate keys.

OpenBao is reachable from the host at `http://localhost:18200` and from containers on this Compose
network at `http://openbao-kms:8200`.

## Mount the client token

Only the trusted Iceberg client that encrypts and decrypts files mounts the token. In the packaged
demo that client is Spark. Gravitino and IRC deliberately have neither the token mount nor the KMS
adapter, because they are not trusted to read encrypted table files. A Compose service can use:

```yaml
services:
  spark:
    volumes:
      - openbao-kms-client-token:/run/secrets/openbao:ro

volumes:
  openbao-kms-client-token:
    external: true
    name: gravitino-openbao-kms-client-token
```

IRC advertises the allowlisted KMS adapter and non-secret endpoint through its Iceberg REST
`/v1/config` response. Spark pins the endpoint and token-file path locally; the credential source
is not accepted from IRC and that path exists in Spark only:

```text
IRC default: encryption.kms-impl=org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient
IRC default and Spark local pin: encryption.kms.openbao.endpoint=http://openbao-kms:8200
Spark local only: encryption.kms.openbao.token-file=/run/secrets/openbao/token
```

Do not copy these values into `gravitino.bypass.*` or `spark.bypass.*` catalog properties. The
packaged demo configures the IRC handshake once and verifies that the response never contains token
content.

The optional `encryption.kms.openbao.transit-mount` property defaults to `transit`. The property
contains a mount name, not a secret.

An encrypted Iceberg table must use format version 3 and the approved key ID:

```text
format-version=3
encryption.key-id=customer-pii-v1
```

Stop the stack and remove the generated client token with:

```shell
docker compose down --volumes
```
