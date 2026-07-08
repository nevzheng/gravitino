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

# Manual interaction cheat-sheet (iTerm)

Copy-paste commands to inspect and drive the Iceberg V3 variant e2e environment by hand.
Pipe through `jq` for readable JSON (`brew install jq` if needed). See `VALIDATION_PLAN.md`
for what each milestone is proving, and the `gravitino-e2e-playground` skill for the model.

## Setup — run once per terminal

```bash
export GRAV=http://localhost:8090/api          # Gravitino native metadata API
export IRC=http://localhost:9001/iceberg/v1    # Gravitino built-in Iceberg REST catalog
export ML=v3check                              # metalake name
export NS=v3_test                              # namespace / schema
export TBL=variant_probe                       # table name
```

## Server lifecycle (M1 — local distribution)

```bash
# from repo root, after the distribution is built
distribution/package/bin/gravitino.sh start      # start (native API :8090 + embedded IRC :9001)
distribution/package/bin/gravitino.sh status
distribution/package/bin/gravitino.sh stop
tail -f distribution/package/logs/gravitino-server.log   # watch logs (e.g. see the load stack)
```

Health check both surfaces:

```bash
curl -s "$IRC/config" | jq .                      # IRC up?  returns catalog config
curl -s "$GRAV/metalakes" | jq .                  # native API up?  lists metalakes
```

## Surface A — the built-in Iceberg REST catalog (:9001)

This is the spec-compliant Iceberg surface. It worked for variant even before the fix.

```bash
# list / create namespaces
curl -s "$IRC/namespaces" | jq .
curl -s -X POST "$IRC/namespaces" -H 'Content-Type: application/json' \
  -d "{\"namespace\":[\"$NS\"]}" | jq .

# create a format-version-3 table with a variant column
curl -s -X POST "$IRC/namespaces/$NS/tables" -H 'Content-Type: application/json' -d '{
  "name": "'"$TBL"'",
  "schema": {"type":"struct","schema-id":0,"fields":[
    {"id":1,"name":"id","required":true,"type":"long"},
    {"id":2,"name":"payload","required":false,"type":"variant"}]},
  "properties": {"format-version":"3"}
}' | jq .

# load it back / list tables
curl -s "$IRC/namespaces/$NS/tables/$TBL" | jq '.metadata.schemas'
curl -s "$IRC/namespaces/$NS/tables" | jq .

# drop (cleanup)
curl -s -X DELETE "$IRC/namespaces/$NS/tables/$TBL" | jq .
```

## Surface B — the Gravitino native metadata API (:8090)

This is the federated layer the fix touches (also backs the UI, tags, policies).

```bash
# metalake
curl -s -X POST "$GRAV/metalakes" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$ML\",\"comment\":\"e2e\",\"properties\":{}}" | jq .
curl -s "$GRAV/metalakes" | jq '.metalakes[].name'

# register the IRC as a catalog — REST backend, NO warehouse property (issue #11943)
curl -s -X POST "$GRAV/metalakes/$ML/catalogs" -H 'Content-Type: application/json' -d '{
  "name":"irc_probe","type":"RELATIONAL","provider":"lakehouse-iceberg",
  "properties":{"catalog-backend":"rest","uri":"http://localhost:9001/iceberg"}
}' | jq .

# inspect the tree
curl -s "$GRAV/metalakes/$ML/catalogs" | jq '.catalogs[].name'
curl -s "$GRAV/metalakes/$ML/catalogs/irc_probe/schemas" | jq .

# THE money query — load the variant table through the native API.
# Pre-fix: UnsupportedOperationException. Post-fix: column type "variant".
curl -s "$GRAV/metalakes/$ML/catalogs/irc_probe/schemas/$NS/tables/$TBL" | jq '.table.columns'
```

## One-shot smoke

```bash
dev/e2e/iceberg-v3/smoke.sh          # runs the full create -> IRC round-trip -> native load assert
```

## Surface C — query engines (M2, once the compose is up)

Placeholders — fill exact ports/creds when the compose lands.

```bash
# Trino CLI (expects trino container on :18080, gravitino catalog wired to the IRC)
docker exec -it <trino-container> trino --server localhost:8080
#   trino> SHOW SCHEMAS FROM iceberg;
#   trino> DESCRIBE iceberg.v3_test.variant_probe;      -- table/schema visible
#   trino> SELECT id FROM iceberg.v3_test.variant_probe; -- non-variant col: works
#   trino> SELECT payload FROM ...;                      -- variant: expected to fail (v435)

# Spark SQL (Iceberg REST catalog pointed at the IRC)
docker exec -it <spark-container> spark-sql
#   spark-sql> SHOW TABLES IN rest.v3_test;
#   spark-sql> DESCRIBE rest.v3_test.variant_probe;

# MinIO — inspect the real Parquet files
open http://localhost:9090   # MinIO console (creds from compose)
```

## Handy

```bash
# pretty stack trace if the native load errors
curl -s "$GRAV/metalakes/$ML/catalogs/irc_probe/schemas/$NS/tables/$TBL" | jq -r '.message'
# reset: drop the probe catalog
curl -s -X DELETE "$GRAV/metalakes/$ML/catalogs/irc_probe?force=true" | jq .
```
