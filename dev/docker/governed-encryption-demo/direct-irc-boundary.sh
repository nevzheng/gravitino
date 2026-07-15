#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE=(
  docker compose
  --project-directory "$SCRIPT_DIR"
  --file "$SCRIPT_DIR/docker-compose.yaml"
)
IRC_BASE="http://localhost:${ICEBERG_REST_HOST_PORT:-9001}/iceberg"
TABLE=direct_irc_policy_bypass_plaintext
MARKER=direct-irc-policy-bypass-plaintext-marker
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gravitino-direct-irc-boundary.XXXXXX")

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  rm -rf "$TMP_DIR"
}

trap cleanup EXIT

for command_name in curl docker jq; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done
docker info >/dev/null 2>&1 || fail "The Docker daemon is unavailable."

printf '\n============================================================\n'
printf 'OUT-OF-SCOPE DIRECT IRC BYPASS BOUNDARY\n'
printf 'This maps the edge of governed coverage; it is not the supported client path.\n'
printf '============================================================\n\n'

printf '[gap 1/3] Point Spark directly at IRC and omit encryption properties\n'
"$SCRIPT_DIR/spark-sql.sh" \
  --conf spark.sql.catalog.direct_irc=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.direct_irc.type=rest \
  --conf spark.sql.catalog.direct_irc.uri=http://iceberg-rest:9001/iceberg \
  --conf spark.sql.catalog.direct_irc.io-impl=org.apache.iceberg.hadoop.HadoopFileIO \
  --conf spark.sql.catalog.direct_irc.cache-enabled=false \
  -f /opt/demo/spark-direct-irc-bypass.sql \
  2>&1 | tee "$TMP_DIR/spark-direct-irc.log"
grep -Fq 'DIRECT_IRC_BYPASS_OBSERVED' "$TMP_DIR/spark-direct-irc.log" \
  || fail "The direct IRC boundary was not reproduced."

printf '\n[gap 2/3] Show authoritative IRC metadata has no encryption declaration\n'
table_file="$TMP_DIR/direct-table.json"
curl --fail --silent --show-error \
  "$IRC_BASE/v1/namespaces/customer_data/tables/$TABLE" >"$table_file"
jq '{
  metadataLocation: .["metadata-location"],
  formatVersion: .metadata["format-version"],
  keyId: .metadata.properties["encryption.key-id"],
  encryptionKeys: .metadata["encryption-keys"]
}' "$table_file"
jq -e \
  '.metadata.properties["encryption.key-id"] == null
    and ((.metadata["encryption-keys"] // []) | length == 0)' \
  "$table_file" >/dev/null \
  || fail "The direct IRC table unexpectedly contains Iceberg encryption metadata."
printf '  BOUNDARY CONFIRMED metadata lacks encryption.key-id and encryption-keys.\n'

printf '\n[gap 3/3] Show the direct-IRC data object is plaintext Parquet\n'
data_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  "find /warehouse -type f -path '*$TABLE*' -name '*.parquet' -print | sort")
data_file=${data_files%%$'\n'*}
data_file=${data_file%$'\r'}
[[ -n "$data_file" ]] || fail "No direct-IRC Parquet object was found."
"${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'head -c 64 "$1" | od -An -tx1c' _ "$data_file"
data_magic=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c 'head -c 4 "$1"' _ "$data_file")
[[ "$data_magic" == PAR1 ]] || fail "Expected plaintext PAR1 framing; found $data_magic."
"${COMPOSE[@]}" exec -T iceberg-rest grep -aF "$MARKER" "$data_file" >/dev/null \
  || fail "The uncompressed plaintext marker was not visible in the direct-IRC object."
printf '  BOUNDARY CONFIRMED header=PAR1 and the known marker is visible in raw bytes.\n'

printf '\nOUT-OF-SCOPE DIRECT IRC BYPASS BOUNDARY reproduced.\n'
printf 'DAT-232 governs Spark and REST creates entering through Gravitino; direct IRC create\n'
printf 'gating is intentionally outside this implementation.\n'
