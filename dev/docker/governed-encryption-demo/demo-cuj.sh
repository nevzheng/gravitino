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
API_BASE="http://localhost:${GRAVITINO_HOST_PORT:-8090}/api"
IRC_BASE="http://localhost:${ICEBERG_REST_HOST_PORT:-9001}/iceberg"
NO_PAUSE=0
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gravitino-encryption-demo.XXXXXX")

METALAKE_PAYLOAD='{
  "name": "demo",
  "comment": "Governed encryption demo",
  "properties": {}
}'
CATALOG_PAYLOAD='{
  "name": "lakehouse",
  "type": "RELATIONAL",
  "provider": "lakehouse-iceberg",
  "comment": "Policy-governed Iceberg catalog routed through IRC",
  "properties": {
    "catalog-backend": "rest",
    "uri": "http://iceberg-rest:9001/iceberg",
    "table-metadata-cache-impl": ""
  }
}'
SCHEMA_PAYLOAD='{
  "name": "customer_data",
  "comment": "Customer dataset governed by the PII tag",
  "properties": {}
}'
TAG_PAYLOAD='{
  "name": "PII",
  "comment": "Personally identifiable information",
  "properties": {}
}'
POLICY_PAYLOAD='{
  "name": "customer_data_encryption",
  "comment": "Require the approved Transit key for PII tables",
  "policyType": "system_iceberg_encryption",
  "enabled": true,
  "content": {
    "schemaVersion": 1,
    "tag": "PII",
    "required": true,
    "allowedKeyIds": ["customer-pii-v1"],
    "enforcement": "deny-create"
  }
}'
SCHEMA_TAG_PAYLOAD='{
  "tagsToAdd": ["PII"],
  "tagsToRemove": []
}'
DENIED_TABLE_PAYLOAD='{
  "name": "unapproved_customer_records",
  "comment": "This request must be rejected atomically",
  "columns": [
    {"name": "id", "type": "long", "nullable": false, "autoIncrement": false},
    {"name": "marker", "type": "string", "nullable": true, "autoIncrement": false}
  ],
  "properties": {
    "format-version": "3",
    "encryption.key-id": "unapproved-key"
  }
}'
GRAVITINO_KEY_UPDATE_PAYLOAD='{
  "updates": [
    {
      "@type": "setProperty",
      "property": "encryption.key-id",
      "value": "unapproved-key"
    }
  ]
}'
IRC_KEY_UPDATE_PAYLOAD='{
  "requirements": [],
  "updates": [
    {
      "action": "set-properties",
      "updates": {"encryption.key-id": "unapproved-key"}
    }
  ]
}'

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  rm -rf "$TMP_DIR"
}

pause() {
  if [[ "$NO_PAUSE" -eq 1 ]]; then
    return
  fi
  if [[ ! -t 0 ]]; then
    fail "Interactive input is unavailable; rerun with --no-pause."
  fi
  read -r -p "Press Enter to continue... " _
}

show_command() {
  printf '  $ %s\n' "$*"
}

pretty_json() {
  jq . "$1"
}

pretty_error() {
  jq '
    if ((.error? // null) | type) == "object" then .error else . end
    | {code, type, message}' "$1"
}

request() {
  local method=$1
  local url=$2
  local body_file=$3
  local payload=${4-}
  local accept='application/vnd.gravitino.v1+json'
  if [[ "$url" == "$IRC_BASE"/* ]]; then
    accept='application/json'
  fi
  local args=(
    --silent --show-error
    --connect-timeout 5 --max-time 120
    --output "$body_file"
    --write-out '%{http_code}'
    --request "$method"
    --header "Accept: $accept"
  )

  if [[ -n "$payload" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "$payload")
  fi
  curl "${args[@]}" "$url"
}

expect_code() {
  local actual=$1
  local expected=$2
  local body_file=$3
  local label=$4
  if [[ "$actual" != "$expected" ]]; then
    pretty_json "$body_file" >&2 || cat "$body_file" >&2
    fail "$label returned HTTP $actual; expected $expected."
  fi
}

post_success() {
  local label=$1
  local path=$2
  local payload=$3
  local body_file="$TMP_DIR/post.json"
  local status
  status=$(request POST "$API_BASE$path" "$body_file" "$payload")
  expect_code "$status" 200 "$body_file" "$label"
  jq -e '.code == 0' "$body_file" >/dev/null || fail "$label returned a nonzero API code."
  printf '  PASS %s\n' "$label"
}

inspect_gravitino() {
  local label=$1
  local path=$2
  local body_file="$TMP_DIR/get.json"
  local status
  show_command "curl -sS '$API_BASE$path' | jq ."
  status=$(request GET "$API_BASE$path" "$body_file")
  expect_code "$status" 200 "$body_file" "$label"
  pretty_json "$body_file"
}

trap cleanup EXIT

case "${1-}" in
  "") ;;
  --no-pause) NO_PAUSE=1 ;;
  -h|--help)
    printf 'Usage: %s [--no-pause]\n' "$0"
    exit 0
    ;;
  *) fail "Unknown option: $1" ;;
esac
[[ $# -le 1 ]] || fail "Only --no-pause is supported."

for command_name in curl docker jq python3; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done
docker info >/dev/null 2>&1 || fail "The Docker daemon is unavailable."

printf '\n[1/12] Inspect the persistent cluster\n'
health_file="$TMP_DIR/health.json"
health_status=$(request GET "$API_BASE/health/ready" "$health_file")
expect_code "$health_status" 200 "$health_file" "Gravitino readiness"
irc_config_file="$TMP_DIR/irc-config.json"
irc_status=$(request GET "$IRC_BASE/v1/config" "$irc_config_file")
expect_code "$irc_status" 200 "$irc_config_file" "IRC readiness"
if ! jq -e '
  ((.defaults // {}) + (.overrides // {})) as $config
  | (($config["encryption.kms-impl"] // $config["encryption.kms-type"] // "") | length > 0)
    and $config["encryption.kms.openbao.endpoint"] == "http://openbao-kms:8200"
    and $config["encryption.kms.openbao.transit-mount"] == "transit"
    and ([$config
      | to_entries[]
      | select(.key | test("token"; "i"))]
      | length == 0)' "$irc_config_file" >/dev/null; then
  fail "IRC did not vend the expected safe KMS catalog defaults."
fi
show_command \
  "curl -sS '$IRC_BASE/v1/config' | jq '.defaults | with_entries(select(.key | startswith(\"encryption.kms\")))'"
jq '
  ((.defaults // {}) + (.overrides // {})) as $config
  | {
      kmsImpl: $config["encryption.kms-impl"],
      kmsType: $config["encryption.kms-type"],
      openBaoEndpoint: $config["encryption.kms.openbao.endpoint"],
      transitMount: $config["encryption.kms.openbao.transit-mount"],
      tokenFileServedByIrc: $config["encryption.kms.openbao.token-file"],
      endpoints: .endpoints
    }' "$irc_config_file"
"${COMPOSE[@]}" exec -T iceberg-rest test ! -e /run/secrets/kms/token \
  || fail "IRC must not mount the KMS token."
"${COMPOSE[@]}" exec -T gravitino test ! -e /run/secrets/kms/token \
  || fail "Gravitino must not mount the KMS token."
"${COMPOSE[@]}" exec -T spark test -s /run/secrets/kms/token \
  || fail "Spark must receive the generated KMS token."
"${COMPOSE[@]}" ps
printf '  PASS Iceberg 1.11+ clients receive safe REST-served KMS configuration.\n'
printf '  PASS the runtime token is mounted only in Spark, never Gravitino or IRC.\n'
pause

printf '\n[2/12] Create the metalake, REST-backed catalog, and customer schema\n'
existing_file="$TMP_DIR/existing.json"
existing_status=$(request GET "$API_BASE/metalakes/demo" "$existing_file")
[[ "$existing_status" == 404 ]] \
  || fail "Metalake demo already exists. Run down.sh, then up.sh, before replaying the CUJ."
post_success "metalake demo created" "/metalakes" "$METALAKE_PAYLOAD"
post_success "lakehouse REST catalog created" "/metalakes/demo/catalogs" "$CATALOG_PAYLOAD"
post_success \
  "customer_data schema created through IRC" \
  "/metalakes/demo/catalogs/lakehouse/schemas" \
  "$SCHEMA_PAYLOAD"
inspect_gravitino "schema inspection" "/metalakes/demo/catalogs/lakehouse/schemas/customer_data"
pause

printf '\n[3/12] Create and inspect the PII tag\n'
post_success "PII tag created" "/metalakes/demo/tags" "$TAG_PAYLOAD"
inspect_gravitino "PII tag inspection" "/metalakes/demo/tags/PII"
pause

printf '\n[4/12] Create and inspect the enabled deny-create policy\n'
post_success \
  "enabled encryption policy created" \
  "/metalakes/demo/policies" \
  "$POLICY_PAYLOAD"
policy_file="$TMP_DIR/policy.json"
policy_status=$(request GET "$API_BASE/metalakes/demo/policies/customer_data_encryption" "$policy_file")
expect_code "$policy_status" 200 "$policy_file" "policy inspection"
jq -e '.policy.enabled == true and .policy.policyType == "system_iceberg_encryption"' \
  "$policy_file" >/dev/null || fail "The loaded encryption policy is not enabled and typed."
show_command "curl -sS '$API_BASE/metalakes/demo/policies/customer_data_encryption' | jq ."
pretty_json "$policy_file"
pause

printf '\n[5/12] Associate and inspect the tag on the exact schema\n'
post_success \
  "PII associated directly with lakehouse.customer_data" \
  "/metalakes/demo/objects/schema/lakehouse.customer_data/tags" \
  "$SCHEMA_TAG_PAYLOAD"
tags_file="$TMP_DIR/schema-tags.json"
tags_status=$(request \
  GET \
  "$API_BASE/metalakes/demo/objects/schema/lakehouse.customer_data/tags" \
  "$tags_file")
expect_code "$tags_status" 200 "$tags_file" "schema tag inspection"
jq -e '.. | strings | select(. == "PII")' "$tags_file" >/dev/null \
  || fail "PII is not directly associated with the exact schema."
show_command \
  "curl -sS '$API_BASE/metalakes/demo/objects/schema/lakehouse.customer_data/tags' | jq ."
pretty_json "$tags_file"
pause

printf '\n[6/12] Prove a disallowed REST create is typed and atomic\n'
denied_file="$TMP_DIR/denied.json"
denied_status=$(request \
  POST \
  "$API_BASE/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables" \
  "$denied_file" \
  "$DENIED_TABLE_PAYLOAD")
expect_code "$denied_status" 400 "$denied_file" "disallowed table create"
jq -e \
  '.type == "EncryptionPolicyViolationException" and (.message | contains("KEY_NOT_ALLOWED"))' \
  "$denied_file" >/dev/null || fail "The denial was not the expected typed policy violation."
printf '  HTTP 400 body:\n'
pretty_error "$denied_file"

absent_file="$TMP_DIR/absent.json"
absent_status=$(request \
  GET \
  "$API_BASE/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/unapproved_customer_records" \
  "$absent_file")
expect_code "$absent_status" 404 "$absent_file" "denied table absence check"
show_command \
  "curl -sS '$API_BASE/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/unapproved_customer_records' | jq '{code, type, message}'"
pretty_error "$absent_file"

irc_denied_list="$TMP_DIR/irc-denied-list.json"
irc_denied_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/customer_data/tables" \
  "$irc_denied_list")
expect_code "$irc_denied_status" 200 "$irc_denied_list" "IRC atomicity inspection"
if jq -e '.. | strings | select(. == "unapproved_customer_records")' \
  "$irc_denied_list" >/dev/null; then
  fail "The denied table reached IRC; create was not atomic."
fi
show_command "curl -sS '$IRC_BASE/v1/namespaces/customer_data/tables' | jq ."
pretty_json "$irc_denied_list"
printf '  PASS policy denied before either Gravitino or IRC created the table.\n'
pause

printf '\n[7/12] Prove Spark through Gravitino cannot omit encryption.key-id\n'
set +e
"$SCRIPT_DIR/spark-sql.sh" -f /opt/demo/spark-missing-key.sql \
  >"$TMP_DIR/spark-missing-key.log" 2>&1
missing_key_spark_status=$?
set -e
grep -Fq 'KEY_REQUIRED' "$TMP_DIR/spark-missing-key.log" \
  || fail "Spark did not expose the expected KEY_REQUIRED policy decision."
spark_error_message=$(grep -m1 'KEY_REQUIRED' "$TMP_DIR/spark-missing-key.log" \
  | sed 's/^[[:space:]]*//' \
  | cut -c 1-500)
printf '  type: EncryptionPolicyViolationException\n'
printf '  message: %s\n' "$spark_error_message"
printf '  rawLog: %s\n' "$TMP_DIR/spark-missing-key.log"

missing_key_file="$TMP_DIR/missing-key-table.json"
missing_key_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/customer_data/tables/missing_key_customer_records" \
  "$missing_key_file")
expect_code "$missing_key_status" 404 "$missing_key_file" "missing-key IRC absence check"
printf '  PASS Spark status=%s, reason=KEY_REQUIRED, and IRC confirms no table was created.\n' \
  "$missing_key_spark_status"
pause

printf '\n[8/12] Create and write the approved encrypted table through Spark 3.5\n'
show_command "$SCRIPT_DIR/spark-sql.sh -f /opt/demo/spark-cuj.sql"
"$SCRIPT_DIR/spark-sql.sh" -f /opt/demo/spark-cuj.sql 2>&1 | tee "$TMP_DIR/spark-write.log"
grep -Fq 'APPROVED_SPARK_WRITE_OK' "$TMP_DIR/spark-write.log" \
  || fail "Spark did not report the approved write proof."
printf '  PASS Spark created and wrote the format-v3 table through Gravitino and IRC.\n'
pause

printf '\n[9/12] Prove IRC can load metadata but cannot perform a server-side encrypted read\n'
server_load_file="$TMP_DIR/server-load.json"
server_load_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  "$server_load_file")
expect_code "$server_load_status" 200 "$server_load_file" "keyless IRC metadata load"
jq -e \
  '.metadata.properties["encryption.key-id"] == "customer-pii-v1"' \
  "$server_load_file" >/dev/null \
  || fail "IRC load-table did not return the encrypted table metadata."
printf '  PASS normal IRC load-table returned HTTP 200 without needing a KMS token.\n'

server_plan_file="$TMP_DIR/server-plan.json"
server_plan_status=$(request \
  POST \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records/plan" \
  "$server_plan_file" \
  '{}')
expect_code "$server_plan_status" 400 "$server_plan_file" "keyless IRC plan scan guard"
jq -e \
  '.error.code == 400
    and .error.type == "EncryptedTableServerSideReadException"
    and (.error.message | contains("ENCRYPTED_TABLE_SERVER_READ_UNSUPPORTED"))' \
  "$server_plan_file" >/dev/null \
  || fail "IRC did not return the typed encrypted server-read guard."
show_command \
  "curl -sS -X POST -H 'Content-Type: application/json' --data '{}' '$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records/plan' | jq '.error | {code, type, message}'"
jq '.error | {code, type, message}' "$server_plan_file"
printf '  PASS server-side plan scan stopped with a typed HTTP 400, never a key/file error.\n'
pause

printf '\n[10/12] Prove encryption.key-id is immutable through Gravitino and direct IRC\n'
gravitino_update_file="$TMP_DIR/gravitino-key-update.json"
gravitino_update_status=$(request \
  PUT \
  "$API_BASE/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/encrypted_customer_records" \
  "$gravitino_update_file" \
  "$GRAVITINO_KEY_UPDATE_PAYLOAD")
expect_code "$gravitino_update_status" 400 "$gravitino_update_file" "Gravitino key-ID update"
jq -e \
  '.type == "EncryptionKeyIdImmutableException"
    and ((.message // "") | contains("decisionId="))
    and ((.message // "") | contains("reason=KEY_ID_IMMUTABLE"))' \
  "$gravitino_update_file" >/dev/null \
  || fail "Gravitino did not return a typed key-ID immutability error."
show_command \
  "curl -sS -X PUT -H 'Content-Type: application/json' --data '<set encryption.key-id>' '$API_BASE/metalakes/demo/catalogs/lakehouse/schemas/customer_data/tables/encrypted_customer_records' | jq '{code, type, message}'"
pretty_error "$gravitino_update_file"

irc_update_file="$TMP_DIR/irc-key-update.json"
irc_update_status=$(request \
  POST \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  "$irc_update_file" \
  "$IRC_KEY_UPDATE_PAYLOAD")
expect_code "$irc_update_status" 400 "$irc_update_file" "direct IRC key-ID update"
jq -e \
  '(.type // .error.type // "") == "EncryptionKeyIdImmutableException"
    and ((.message // .error.message // "") | contains("decisionId="))
    and ((.message // .error.message // "") | contains("reason=KEY_ID_IMMUTABLE"))' \
  "$irc_update_file" >/dev/null \
  || fail "IRC did not return a typed key-ID immutability error."
show_command \
  "curl -sS -X POST -H 'Content-Type: application/json' --data '<set encryption.key-id>' '$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records' | jq '.error | {code, type, message}'"
pretty_error "$irc_update_file"

immutable_reload_file="$TMP_DIR/immutable-reload.json"
immutable_reload_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  "$immutable_reload_file")
expect_code "$immutable_reload_status" 200 "$immutable_reload_file" "immutable key-ID reload"
jq -e '.metadata.properties["encryption.key-id"] == "customer-pii-v1"' \
  "$immutable_reload_file" >/dev/null \
  || fail "A rejected update changed the original encryption key ID."
printf '  PASS IRC reload still reports encryption.key-id=customer-pii-v1.\n'

show_command \
  "docker compose logs --no-color --tail 200 gravitino iceberg-rest | grep -Ei 'key ID|encryption.key-id'"
"${COMPOSE[@]}" logs --no-color --tail 200 gravitino iceberg-rest \
  >"$TMP_DIR/key-update.log" 2>&1 || true
if grep -Ei 'key ID|encryption.key-id' "$TMP_DIR/key-update.log" >"$TMP_DIR/key-update-lines.log"; then
  cat "$TMP_DIR/key-update-lines.log"
else
  printf '  NOTE The typed HTTP 400 bodies above are the available mutation audit evidence;\n'
  printf '       this POC has no durable audit sink for rejected property updates.\n'
fi
pause

printf '\n[11/12] Inspect IRC metadata, warehouse bytes, and the Transit envelope\n'
irc_list_file="$TMP_DIR/irc-list.json"
irc_list_status=$(request GET "$IRC_BASE/v1/namespaces/customer_data/tables" "$irc_list_file")
expect_code "$irc_list_status" 200 "$irc_list_file" "IRC table list"
show_command "curl -sS '$IRC_BASE/v1/namespaces/customer_data/tables' | jq ."
pretty_json "$irc_list_file"

irc_table_file="$TMP_DIR/irc-table.json"
irc_table_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records" \
  "$irc_table_file")
expect_code "$irc_table_status" 200 "$irc_table_file" "IRC encrypted-table load"
if ! jq -e '
  [(.config // {})
    | to_entries[]
    | select(.key | test("token"; "i"))]
  | length == 0' "$irc_table_file" >/dev/null; then
  fail "IRC load-table config exposed an unsafe token property."
fi
show_command \
  "curl -sS '$IRC_BASE/v1/namespaces/customer_data/tables/encrypted_customer_records' | jq '{metadataLocation: .[\"metadata-location\"], formatVersion: .metadata[\"format-version\"], keyId: .metadata.properties[\"encryption.key-id\"], encryptionKeyCount: (.metadata[\"encryption-keys\"] | length), responseConfig: .config}'"
jq '{
  metadataLocation: .["metadata-location"],
  formatVersion: .metadata["format-version"],
  keyId: .metadata.properties["encryption.key-id"],
  encryptionKeys: [
    (.metadata["encryption-keys"] // [])[]
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
}' "$irc_table_file"
jq -e \
  '.metadata["format-version"] == 3
    and .metadata.properties["encryption.key-id"] == "customer-pii-v1"
    and (.metadata["encryption-keys"] | length > 0)' \
  "$irc_table_file" >/dev/null || fail "IRC did not return valid format-v3 encryption metadata."

metadata_location=$(jq -er '.["metadata-location"]' "$irc_table_file")
metadata_path=$(python3 -c \
  'import sys, urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).path)' \
  "$metadata_location")
"${COMPOSE[@]}" exec -T iceberg-rest test -f "$metadata_path" \
  || fail "IRC metadata-location does not exist in the shared warehouse."
printf '  PASS IRC metadata-location correlates to %s in the shared volume.\n' "$metadata_path"

show_command "docker compose exec -T iceberg-rest sh -c 'find /warehouse -type f -print | sort'"
"${COMPOSE[@]}" exec -T iceberg-rest sh -c 'find /warehouse -type f -print | sort'
show_command \
  "docker compose exec -T iceberg-rest sh -c 'find /warehouse -type f -exec ls -lh {} \\; | sort -k9'"
"${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'find /warehouse -type f -exec ls -lh {} \; | sort -k9'

metadata_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  "find /warehouse -type f -name '*.metadata.json' -print | sort")
latest_metadata=${metadata_files##*$'\n'}
latest_metadata=${latest_metadata%$'\r'}
[[ -n "$latest_metadata" ]] || fail "No Iceberg metadata JSON was written."
show_command "docker compose exec -T iceberg-rest cat '$latest_metadata'"
"${COMPOSE[@]}" exec -T iceberg-rest cat "$latest_metadata"

data_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  "find /warehouse -type f -name '*.parquet' -print | sort")
data_file=${data_files%%$'\n'*}
data_file=${data_file%$'\r'}
avro_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  "find /warehouse -type f -name '*.avro' -print | sort")
avro_file=${avro_files%%$'\n'*}
avro_file=${avro_file%$'\r'}
[[ -n "$data_file" && -n "$avro_file" ]] \
  || fail "Expected encrypted Parquet and Avro objects were not written."

for binary_file in "$data_file" "$avro_file"; do
  show_command \
    "docker compose exec -T iceberg-rest sh -c 'head -c 64 \"\$1\" | od -An -tx1c' _ '$binary_file'"
  "${COMPOSE[@]}" exec -T iceberg-rest sh -c \
    'head -c 64 "$1" | od -An -tx1c' _ "$binary_file"
done

data_magic=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c 'head -c 4 "$1"' _ "$data_file")
avro_magic=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c 'head -c 4 "$1"' _ "$avro_file")
[[ "$data_magic" == PARE ]] || fail "Data object magic was $data_magic, not PARE."
[[ "$avro_magic" == AGS1 ]] || fail "Manifest object magic was $avro_magic, not AGS1."
printf '  PASS encrypted object magic: data=PARE manifest=AGS1.\n'

show_command \
  "docker compose exec -T iceberg-rest grep -aF 'governed-encryption-poc-marker' '$data_file' '$avro_file'"
if "${COMPOSE[@]}" exec -T iceberg-rest \
  grep -aF 'governed-encryption-poc-marker' "$data_file" "$avro_file" >/dev/null 2>&1; then
  fail "A raw encrypted object exposed the known plaintext marker."
fi
printf '  PASS the known plaintext marker is absent from raw data and manifest bytes.\n'

encoded_envelope=$(jq -er \
  '.metadata["encryption-keys"]
    | map(select(.["encrypted-by-id"] == "customer-pii-v1"))
    | .[0]["encrypted-key-metadata"]' \
  "$irc_table_file")
wrapped_envelope=$(python3 -c \
  'import base64, sys; print(base64.b64decode(sys.argv[1]).decode("utf-8"))' \
  "$encoded_envelope")
[[ "$wrapped_envelope" == vault:v*:* ]] \
  || fail "Iceberg encrypted-key-metadata was not a Vault-compatible Transit envelope."
printf '  PASS Iceberg key metadata decodes to vault:v1: ciphertext (decoded value not reprinted).\n'

"${COMPOSE[@]}" run --rm --no-deps \
  --entrypoint /bin/sh \
  --env WRAPPED_ENVELOPE="$wrapped_envelope" \
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
printf '  PASS KMS unwrap succeeded; key material withheld.\n'
printf '  NOTE Transit unwraps only Iceberg key metadata. PARE and AGS1 are Iceberg encrypted\n'
printf '       file formats; sending an entire Parquet or manifest object to bao decrypt is invalid.\n'
pause

printf '\n[12/12] Start a fresh Spark session and prove layered decryption\n'
show_command "$SCRIPT_DIR/spark-sql.sh -f /opt/demo/spark-readback.sql"
"$SCRIPT_DIR/spark-sql.sh" -f /opt/demo/spark-readback.sql 2>&1 | tee "$TMP_DIR/spark-read.log"
grep -Fq 'APPROVED_SPARK_READ_OK' "$TMP_DIR/spark-read.log" \
  || fail "Spark did not report the approved read proof."
grep -Fq 'governed-encryption-poc-marker' "$TMP_DIR/spark-read.log" \
  || fail "Spark did not recover the known plaintext marker through Iceberg and KMS."
printf '  PASS a new Spark session loaded IRC metadata, asked KMS to unwrap the key, and Iceberg\n'
printf '       decrypted the AGS1/PARE layers to recover the expected row.\n'

printf '\nGoverned encryption CUJ passed. The persistent cluster remains running.\n'
printf 'Out-of-scope direct IRC boundary: %s/direct-irc-boundary.sh\n' "$SCRIPT_DIR"
printf 'Optional key rotation checkpoint: %s/rotation-checkpoint.sh\n' "$SCRIPT_DIR"
printf 'Stop it with: %s/down.sh\n' "$SCRIPT_DIR"
