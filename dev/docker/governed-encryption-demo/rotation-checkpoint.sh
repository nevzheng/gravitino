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
KMS_BASE="http://localhost:${TRANSIT_HOST_PORT:-18200}"
KEY_ID=customer-pii-v1
ROOT_TOKEN=${TRANSIT_ROOT_TOKEN:-gravitino-transit-demo-root}
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gravitino-key-rotation.XXXXXX")

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  rm -rf "$TMP_DIR"
}

extract_envelope() {
  local table_json=$1
  local encoded

  encoded=$(jq -er \
    --arg key_id "$KEY_ID" \
    '.metadata["encryption-keys"]
      | map(select(.["encrypted-by-id"] == $key_id))
      | .[0]["encrypted-key-metadata"]' \
    "$table_json")
  python3 -c \
    'import base64, sys; print(base64.b64decode(sys.argv[1]).decode("utf-8"))' \
    "$encoded"
}

unwrap_without_output() {
  local envelope=$1

  "${COMPOSE[@]}" run --rm --no-deps \
    --entrypoint /bin/sh \
    --env WRAPPED_ENVELOPE="$envelope" \
    openbao-kms-bootstrap -eu -c '
      client_token=$(cat /run/openbao-client/token)
      export BAO_ADDR="${BAO_ADDR:-$VAULT_ADDR}"
      export VAULT_ADDR="$BAO_ADDR"
      export BAO_TOKEN="$client_token"
      export VAULT_TOKEN="$client_token"
      unset client_token
      unwrapped=$(bao write -field=plaintext \
        transit/decrypt/customer-pii-v1 ciphertext="$WRAPPED_ENVELOPE")
      test -n "$unwrapped"
      unset unwrapped
    ' >/dev/null
}

trap cleanup EXIT

for command_name in curl docker jq python3; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done
docker info >/dev/null 2>&1 || fail "The Docker daemon is unavailable."

printf '\n[rotation 1/4] Capture the existing table envelope\n'
existing_file="$TMP_DIR/existing-table.json"
existing_status=$(curl --silent --show-error \
  --output "$existing_file" \
  --write-out '%{http_code}' \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records")
[[ "$existing_status" == 200 ]] \
  || fail "Run demo-cuj.sh successfully before this optional checkpoint."
existing_envelope=$(extract_envelope "$existing_file")
[[ "$existing_envelope" == vault:v1:* ]] \
  || fail "The original table does not contain the expected vault:v1: envelope."
printf '  PASS original envelope is vault:v1: (ciphertext withheld).\n'

printf '\n[rotation 2/4] Rotate the same Transit key ID over REST\n'
rotate_file="$TMP_DIR/rotate.json"
rotate_status=$(curl --silent --show-error \
  --output "$rotate_file" \
  --write-out '%{http_code}' \
  --request POST \
  --header "X-Vault-Token: $ROOT_TOKEN" \
  "$KMS_BASE/v1/transit/keys/$KEY_ID/rotate")
[[ "$rotate_status" == 200 || "$rotate_status" == 204 ]] \
  || {
    jq . "$rotate_file" >&2 || cat "$rotate_file" >&2
    fail "Transit rotation returned HTTP $rotate_status."
  }
key_file="$TMP_DIR/key.json"
curl --fail --silent --show-error \
  --header "X-Vault-Token: $ROOT_TOKEN" \
  "$KMS_BASE/v1/transit/keys/$KEY_ID" >"$key_file"
latest_version=$(jq -er '.data.latest_version' "$key_file")
[[ "$latest_version" -ge 2 ]] || fail "Transit did not advance the key version."
printf '  POST %s/v1/transit/keys/%s/rotate -> HTTP %s\n' \
  "$KMS_BASE" "$KEY_ID" "$rotate_status"
printf '  PASS key ID remains %s; latest Transit version is v%s.\n' \
  "$KEY_ID" "$latest_version"

printf '\n[rotation 3/4] Prove the old vault:v1: envelope remains readable\n'
unwrap_without_output "$existing_envelope"
printf '  PASS Transit decrypted the original v1 envelope after rotation; material withheld.\n'

printf '\n[rotation 4/4] Create a new table and inspect its envelope version\n'
"$SCRIPT_DIR/spark-sql.sh" -f /opt/demo/spark-rotation.sql \
  2>&1 | tee "$TMP_DIR/spark-rotation.log"
grep -Fq 'ROTATED_KEY_WRITE_OK' "$TMP_DIR/spark-rotation.log" \
  || fail "Spark did not report the rotated-key write proof."

rotated_file="$TMP_DIR/rotated-table.json"
curl --fail --silent --show-error \
  "$IRC_BASE/v1/namespaces/customer_data/tables/rotated_customer_records" \
  >"$rotated_file"
jq -e \
  --arg key_id "$KEY_ID" \
  '.metadata.properties["encryption.key-id"] == $key_id' \
  "$rotated_file" >/dev/null \
  || fail "The new table changed the logical encryption key ID."
rotated_envelope=$(extract_envelope "$rotated_file")
rotated_version=${rotated_envelope%%:*}
rotated_remainder=${rotated_envelope#*:}
rotated_version=$rotated_version:${rotated_remainder%%:*}:

if [[ "$rotated_version" == "vault:v${latest_version}:" ]]; then
  printf '  PASS new table uses %s while encryption.key-id remains %s.\n' \
    "$rotated_version" "$KEY_ID"
else
  printf '  NOTE new table reported %s after Transit advanced to v%s.\n' \
    "$rotated_version" "$latest_version"
  printf '       This observation is non-fatal: clients may reuse an already wrapped KEK.\n'
fi

printf '\nRotation checkpoint complete. Existing Iceberg objects were not bulk-rewrapped.\n'
