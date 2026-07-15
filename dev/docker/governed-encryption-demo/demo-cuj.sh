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
NO_COLOR_OUTPUT=0
RUN_SUFFIX=${DEMO_RUN_SUFFIX:-}
COLOR_RESET=''
COLOR_BOLD=''
COLOR_GREEN=''
COLOR_YELLOW=''
COLOR_CYAN=''
COLOR_BLUE=''
COLOR_MAGENTA=''
ERROR_COLOR=''
ERROR_RESET=''
JQ_OUTPUT_ARGS=(-M)
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gravitino-encryption-demo.XXXXXX")

METALAKE_PAYLOAD='{
  "name": "__METALAKE_NAME__",
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
  "name": "__SCHEMA_NAME__",
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
  "name": "__DENIED_TABLE_NAME__",
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
  printf '%bERROR%b %s\n' "$ERROR_COLOR" "$ERROR_RESET" "$*" >&2
  exit 1
}

init_colors() {
  if [[ "$NO_COLOR_OUTPUT" -eq 0 \
      && -t 1 \
      && "${TERM:-dumb}" != dumb \
      && -z "${NO_COLOR+x}" ]]; then
    COLOR_RESET=$'\033[0m'
    COLOR_BOLD=$'\033[1m'
    COLOR_GREEN=$'\033[32m'
    COLOR_YELLOW=$'\033[33m'
    COLOR_CYAN=$'\033[36m'
    COLOR_BLUE=$'\033[34m'
    COLOR_MAGENTA=$'\033[35m'
    JQ_OUTPUT_ARGS=(-C)
  fi
  if [[ "$NO_COLOR_OUTPUT" -eq 0 \
      && -t 2 \
      && "${TERM:-dumb}" != dumb \
      && -z "${NO_COLOR+x}" ]]; then
    ERROR_COLOR=$'\033[1;31m'
    ERROR_RESET=$'\033[0m'
  fi
}

banner() {
  printf '\n%b%s%b\n' "${COLOR_BOLD}${COLOR_CYAN}" \
    '======================================================================' "$COLOR_RESET"
  printf '%b  GOVERNED ICEBERG ENCRYPTION — END-TO-END CUSTOMER JOURNEY%b\n' \
    "${COLOR_BOLD}${COLOR_CYAN}" "$COLOR_RESET"
  printf '%b%s%b\n' "${COLOR_BOLD}${COLOR_CYAN}" \
    '======================================================================' "$COLOR_RESET"
  printf '  This walkthrough shows the request, input, response, and evidence\n'
  printf '  for each governance and encryption boundary. Sensitive material stays redacted.\n'
  printf '  %bRUN%b      suffix=%s  metalake=%s  schema=%s\n' \
    "${COLOR_BOLD}${COLOR_MAGENTA}" "$COLOR_RESET" "$RUN_SUFFIX" "$METALAKE_NAME" "$SCHEMA_NAME"
  printf '  Copy displayed Docker commands after changing to the demo directory:\n'
  show_command "cd '$SCRIPT_DIR'"
}

step() {
  local number=$1
  local title=$2
  local purpose=$3

  printf '\n%b[%s] %s%b\n' "${COLOR_BOLD}${COLOR_CYAN}" "$number" "$title" "$COLOR_RESET"
  printf '  %bWHY%b      %s\n' "${COLOR_BOLD}${COLOR_BLUE}" "$COLOR_RESET" "$purpose"
}

request_block() {
  printf '\n  %bREQUEST%b  %s\n' "${COLOR_BOLD}${COLOR_CYAN}" "$COLOR_RESET" "$*"
}

input_block() {
  printf '  %bINPUT%b    %s\n' "${COLOR_BOLD}${COLOR_MAGENTA}" "$COLOR_RESET" "$*"
}

output_block() {
  printf '  %bOUTPUT%b   %s\n' "${COLOR_BOLD}${COLOR_YELLOW}" "$COLOR_RESET" "$*"
}

evidence_block() {
  printf '  %bEVIDENCE%b %s\n' "${COLOR_BOLD}${COLOR_YELLOW}" "$COLOR_RESET" "$*"
}

pass() {
  printf '  %bPASS%b     %s\n' "${COLOR_BOLD}${COLOR_GREEN}" "$COLOR_RESET" "$*"
}

note() {
  printf '  %bNOTE%b     %s\n' "${COLOR_BOLD}${COLOR_YELLOW}" "$COLOR_RESET" "$*"
}

input_json() {
  input_block 'JSON request body:'
  printf '%s\n' "$1" | jq "${JQ_OUTPUT_ARGS[@]}" .
}

render_sql() {
  sed \
    -e 's/customer_data/__DEMO_SCHEMA__/g' \
    -e 's/encrypted_customer_records/__DEMO_ENCRYPTED_TABLE__/g' \
    -e 's/missing_key_customer_records/__DEMO_MISSING_KEY_TABLE__/g' \
    -e 's/rotated_customer_records/__DEMO_ROTATED_TABLE__/g' \
    -e 's/direct_irc_policy_bypass_plaintext/__DEMO_DIRECT_IRC_TABLE__/g' \
    "$1" \
    | sed \
      -e "s/__DEMO_SCHEMA__/$SCHEMA_NAME/g" \
      -e "s/__DEMO_ENCRYPTED_TABLE__/$ENCRYPTED_TABLE_NAME/g" \
      -e "s/__DEMO_MISSING_KEY_TABLE__/$MISSING_KEY_TABLE_NAME/g" \
      -e "s/__DEMO_ROTATED_TABLE__/$ROTATED_TABLE_NAME/g" \
      -e "s/__DEMO_DIRECT_IRC_TABLE__/$DIRECT_IRC_TABLE_NAME/g"
}

show_sql() {
  local source_file=$1
  local sql=$2
  input_block "Spark SQL rendered from $(basename "$source_file"):"
  printf '%s\n' "$sql" | awk 'started || /^USE / { started = 1; print }'
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
  printf '\n  %bPAUSE%b    Press Enter to continue... ' \
    "${COLOR_BOLD}${COLOR_CYAN}" "$COLOR_RESET"
  read -r _
}

show_command() {
  printf '  %b$ %s%b\n' "${COLOR_BOLD}${COLOR_CYAN}" "$*" "$COLOR_RESET"
}

pretty_json() {
  jq "${JQ_OUTPUT_ARGS[@]}" . "$1"
}

pretty_error() {
  jq "${JQ_OUTPUT_ARGS[@]}" '
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
  request_block "POST $API_BASE$path"
  input_json "$payload"
  show_command \
    "curl -sS -X POST -H 'Content-Type: application/json' --data-binary '<JSON above>' '$API_BASE$path' | jq ."
  status=$(request POST "$API_BASE$path" "$body_file" "$payload")
  expect_code "$status" 200 "$body_file" "$label"
  jq -e '.code == 0' "$body_file" >/dev/null || fail "$label returned a nonzero API code."
  output_block "HTTP $status, Gravitino code=$(jq -r '.code' "$body_file")"
  pass "$label"
}

inspect_gravitino() {
  local label=$1
  local path=$2
  local body_file="$TMP_DIR/get.json"
  local status
  request_block "GET $API_BASE$path"
  show_command "curl -sS '$API_BASE$path' | jq ."
  status=$(request GET "$API_BASE$path" "$body_file")
  expect_code "$status" 200 "$body_file" "$label"
  output_block "HTTP $status — persisted server state:"
  pretty_json "$body_file"
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-pause) NO_PAUSE=1 ;;
    --no-color) NO_COLOR_OUTPUT=1 ;;
    --run-suffix)
      [[ $# -ge 2 ]] || fail "--run-suffix requires a value."
      RUN_SUFFIX=$2
      shift
      ;;
    -h|--help)
      printf 'Usage: %s [--no-pause] [--no-color] [--run-suffix ID]\n' "$0"
      printf '  --no-pause  Run unattended instead of pausing after each step.\n'
      printf '  --no-color  Disable ANSI colors. NO_COLOR and non-TTY output do this automatically.\n'
      printf '  --run-suffix Reuse a specific run ID; otherwise a random eight-hex ID is generated.\n'
      exit 0
      ;;
    *) fail "Unknown option: $1" ;;
  esac
  shift
done

if [[ -z "$RUN_SUFFIX" ]]; then
  printf -v RUN_SUFFIX '%04x%04x' "$RANDOM" "$RANDOM"
fi
[[ "$RUN_SUFFIX" =~ ^[a-z0-9][a-z0-9_]{0,31}$ ]] \
  || fail "Run suffix must match [a-z0-9][a-z0-9_]{0,31}."

METALAKE_NAME="demo_$RUN_SUFFIX"
CATALOG_NAME='lakehouse'
SCHEMA_NAME="customer_data_$RUN_SUFFIX"
POLICY_NAME='customer_data_encryption'
DENIED_TABLE_NAME="unapproved_customer_records_$RUN_SUFFIX"
MISSING_KEY_TABLE_NAME="missing_key_customer_records_$RUN_SUFFIX"
ENCRYPTED_TABLE_NAME="encrypted_customer_records_$RUN_SUFFIX"
ROTATED_TABLE_NAME="rotated_customer_records_$RUN_SUFFIX"
DIRECT_IRC_TABLE_NAME="direct_irc_policy_bypass_plaintext_$RUN_SUFFIX"

METALAKE_PAYLOAD=${METALAKE_PAYLOAD//__METALAKE_NAME__/$METALAKE_NAME}
SCHEMA_PAYLOAD=${SCHEMA_PAYLOAD//__SCHEMA_NAME__/$SCHEMA_NAME}
DENIED_TABLE_PAYLOAD=${DENIED_TABLE_PAYLOAD//__DENIED_TABLE_NAME__/$DENIED_TABLE_NAME}

init_colors

for command_name in curl docker jq python3; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done
docker info >/dev/null 2>&1 || fail "The Docker daemon is unavailable."

banner

step \
  '1/12' \
  'Verify the trust boundary and REST catalog handshake' \
  'Confirm both services are ready, IRC serves only safe KMS coordinates, and only Spark receives the KMS token.'
health_file="$TMP_DIR/health.json"
request_block "GET $API_BASE/health/ready"
show_command "curl -sS '$API_BASE/health/ready' | jq ."
health_status=$(request GET "$API_BASE/health/ready" "$health_file")
expect_code "$health_status" 200 "$health_file" "Gravitino readiness"
output_block "HTTP $health_status — Gravitino is ready:"
pretty_json "$health_file"

irc_config_file="$TMP_DIR/irc-config.json"
request_block "GET $IRC_BASE/v1/config"
show_command \
  "curl -sS '$IRC_BASE/v1/config' | jq '.defaults | with_entries(select(.key | startswith(\"encryption.kms\")))'"
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
output_block "HTTP $irc_status — safe client configuration (no token):"
jq "${JQ_OUTPUT_ARGS[@]}" '
  ((.defaults // {}) + (.overrides // {})) as $config
  | {
      kmsImpl: $config["encryption.kms-impl"],
      kmsType: $config["encryption.kms-type"],
      openBaoEndpoint: $config["encryption.kms.openbao.endpoint"],
      transitMount: $config["encryption.kms.openbao.transit-mount"],
      tokenFileServedByIrc: $config["encryption.kms.openbao.token-file"],
      advertisedEndpointCount: ((.endpoints // []) | length)
    }' "$irc_config_file"

evidence_block 'Inspect KMS credential placement across containers:'
show_command "docker compose exec -T iceberg-rest test ! -e /run/secrets/kms/token"
"${COMPOSE[@]}" exec -T iceberg-rest test ! -e /run/secrets/kms/token \
  || fail "IRC must not mount the KMS token."
show_command "docker compose exec -T gravitino test ! -e /run/secrets/kms/token"
"${COMPOSE[@]}" exec -T gravitino test ! -e /run/secrets/kms/token \
  || fail "Gravitino must not mount the KMS token."
show_command "docker compose exec -T spark test -s /run/secrets/kms/token"
"${COMPOSE[@]}" exec -T spark test -s /run/secrets/kms/token \
  || fail "Spark must receive the generated KMS token."
show_command "docker compose ps"
"${COMPOSE[@]}" ps
pass 'Iceberg 1.11+ clients receive safe REST-served KMS configuration.'
pass 'The runtime token is mounted only in Spark, never Gravitino or IRC.'
pause

step \
  '2/12' \
  'Build the governed Iceberg route' \
  "Create $METALAKE_NAME, its REST-backed catalog, and $SCHEMA_NAME; the shared suffix isolates this run in IRC too."
existing_file="$TMP_DIR/existing.json"
request_block "GET $API_BASE/metalakes/$METALAKE_NAME"
input_block "Precondition: $METALAKE_NAME must not already exist."
show_command "curl -sS '$API_BASE/metalakes/$METALAKE_NAME' | jq ."
existing_status=$(request GET "$API_BASE/metalakes/$METALAKE_NAME" "$existing_file")
[[ "$existing_status" == 404 ]] \
  || fail "Metalake $METALAKE_NAME already exists. Choose another --run-suffix."
output_block "HTTP $existing_status — clean starting state confirmed."
post_success "metalake $METALAKE_NAME created" "/metalakes" "$METALAKE_PAYLOAD"
post_success \
  "$CATALOG_NAME REST catalog created" \
  "/metalakes/$METALAKE_NAME/catalogs" \
  "$CATALOG_PAYLOAD"
post_success \
  "$SCHEMA_NAME schema created through IRC" \
  "/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas" \
  "$SCHEMA_PAYLOAD"
inspect_gravitino \
  "schema inspection" \
  "/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME"
pause

step \
  '3/12' \
  'Define the PII classification' \
  'Create the policy tag that classifies governed resources, then reload it to prove persistence.'
post_success "PII tag created" "/metalakes/$METALAKE_NAME/tags" "$TAG_PAYLOAD"
inspect_gravitino "PII tag inspection" "/metalakes/$METALAKE_NAME/tags/PII"
pause

step \
  '4/12' \
  'Activate the deny-create encryption policy' \
  'Require encryption.key-id for matching PII resources and allow only customer-pii-v1 before a table is created.'
post_success \
  "enabled encryption policy created" \
  "/metalakes/$METALAKE_NAME/policies" \
  "$POLICY_PAYLOAD"
policy_file="$TMP_DIR/policy.json"
request_block "GET $API_BASE/metalakes/$METALAKE_NAME/policies/$POLICY_NAME"
show_command \
  "curl -sS '$API_BASE/metalakes/$METALAKE_NAME/policies/$POLICY_NAME' | jq ."
policy_status=$(request \
  GET \
  "$API_BASE/metalakes/$METALAKE_NAME/policies/$POLICY_NAME" \
  "$policy_file")
expect_code "$policy_status" 200 "$policy_file" "policy inspection"
jq -e '.policy.enabled == true and .policy.policyType == "system_iceberg_encryption"' \
  "$policy_file" >/dev/null || fail "The loaded encryption policy is not enabled and typed."
output_block "HTTP $policy_status — persisted enabled policy:"
pretty_json "$policy_file"
pass 'The deny-create policy is enabled and typed as system_iceberg_encryption.'
pause

step \
  '5/12' \
  'Apply PII to the exact schema' \
  "Directly tagging $CATALOG_NAME.$SCHEMA_NAME activates the policy for table creates immediately beneath this schema."
post_success \
  "PII associated directly with $CATALOG_NAME.$SCHEMA_NAME" \
  "/metalakes/$METALAKE_NAME/objects/schema/$CATALOG_NAME.$SCHEMA_NAME/tags" \
  "$SCHEMA_TAG_PAYLOAD"
tags_file="$TMP_DIR/schema-tags.json"
request_block \
  "GET $API_BASE/metalakes/$METALAKE_NAME/objects/schema/$CATALOG_NAME.$SCHEMA_NAME/tags"
show_command \
  "curl -sS '$API_BASE/metalakes/$METALAKE_NAME/objects/schema/$CATALOG_NAME.$SCHEMA_NAME/tags' | jq ."
tags_status=$(request \
  GET \
  "$API_BASE/metalakes/$METALAKE_NAME/objects/schema/$CATALOG_NAME.$SCHEMA_NAME/tags" \
  "$tags_file")
expect_code "$tags_status" 200 "$tags_file" "schema tag inspection"
jq -e '.. | strings | select(. == "PII")' "$tags_file" >/dev/null \
  || fail "PII is not directly associated with the exact schema."
output_block "HTTP $tags_status — tags directly associated with the schema:"
pretty_json "$tags_file"
pass "The exact $SCHEMA_NAME schema now matches the PII encryption policy."
pause

step \
  '6/12' \
  'Reject an unapproved key atomically' \
  'Send a real table-create request and prove policy returns a typed 400 before either Gravitino or IRC persists the table.'
denied_file="$TMP_DIR/denied.json"
request_block \
  "POST $API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables"
input_json "$DENIED_TABLE_PAYLOAD"
show_command \
  "curl -sS -X POST -H 'Content-Type: application/json' --data-binary '<JSON above>' '$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables' | jq ."
denied_status=$(request \
  POST \
  "$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables" \
  "$denied_file" \
  "$DENIED_TABLE_PAYLOAD")
expect_code "$denied_status" 400 "$denied_file" "disallowed table create"
jq -e \
  '.type == "EncryptionPolicyViolationException" and (.message | contains("KEY_NOT_ALLOWED"))' \
  "$denied_file" >/dev/null || fail "The denial was not the expected typed policy violation."
output_block "HTTP $denied_status — typed policy decision:"
pretty_error "$denied_file"

absent_file="$TMP_DIR/absent.json"
request_block \
  "GET $API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$DENIED_TABLE_NAME"
show_command \
  "curl -sS '$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$DENIED_TABLE_NAME' | jq '{code, type, message}'"
absent_status=$(request \
  GET \
  "$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$DENIED_TABLE_NAME" \
  "$absent_file")
expect_code "$absent_status" 404 "$absent_file" "denied table absence check"
output_block "HTTP $absent_status — Gravitino confirms the table is absent:"
pretty_error "$absent_file"

irc_denied_list="$TMP_DIR/irc-denied-list.json"
request_block "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables"
show_command "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables' | jq ."
irc_denied_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables" \
  "$irc_denied_list")
expect_code "$irc_denied_status" 200 "$irc_denied_list" "IRC atomicity inspection"
if jq -e --arg table "$DENIED_TABLE_NAME" '.. | strings | select(. == $table)' \
  "$irc_denied_list" >/dev/null; then
  fail "The denied table reached IRC; create was not atomic."
fi
output_block "HTTP $irc_denied_status — IRC table list does not contain $DENIED_TABLE_NAME:"
pretty_json "$irc_denied_list"
pass 'Policy denied before either Gravitino or IRC created the table.'
pause

step \
  '7/12' \
  'Reject a Spark create with no key' \
  'Exercise the engine path and prove Spark cannot bypass the same deny-create policy by omitting encryption.key-id.'
missing_key_sql=$(render_sql "$SCRIPT_DIR/spark-missing-key.sql")
show_sql "$SCRIPT_DIR/spark-missing-key.sql" "$missing_key_sql"
request_block 'Spark SQL -> Gravitino catalog -> policy evaluation -> IRC only if allowed'
show_command \
  "printf '%s\\n' '<SQL above>' | $SCRIPT_DIR/spark-sql.sh --conf spark.sql.gravitino.metalake=$METALAKE_NAME -f /dev/stdin"
set +e
printf '%s\n' "$missing_key_sql" | "$SCRIPT_DIR/spark-sql.sh" \
  --conf "spark.sql.gravitino.metalake=$METALAKE_NAME" \
  -f /dev/stdin \
  >"$TMP_DIR/spark-missing-key.log" 2>&1
missing_key_spark_status=$?
set -e
[[ "$missing_key_spark_status" -ne 0 ]] \
  || fail "Spark unexpectedly accepted a table create without encryption.key-id."
grep -Fq 'KEY_REQUIRED' "$TMP_DIR/spark-missing-key.log" \
  || fail "Spark did not expose the expected KEY_REQUIRED policy decision."
spark_error_message=$(grep -m1 'KEY_REQUIRED' "$TMP_DIR/spark-missing-key.log" \
  | sed 's/^[[:space:]]*//' \
  | cut -c 1-500)
output_block "Spark exit status $missing_key_spark_status — expected typed rejection:"
printf '    type: EncryptionPolicyViolationException\n'
printf '    reason: KEY_REQUIRED\n'
printf '    message: %s\n' "$spark_error_message"
note "Raw Spark output is available during this run at $TMP_DIR/spark-missing-key.log"

missing_key_file="$TMP_DIR/missing-key-table.json"
request_block \
  "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$MISSING_KEY_TABLE_NAME"
show_command \
  "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$MISSING_KEY_TABLE_NAME' | jq ."
missing_key_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$MISSING_KEY_TABLE_NAME" \
  "$missing_key_file")
expect_code "$missing_key_status" 404 "$missing_key_file" "missing-key IRC absence check"
output_block "HTTP $missing_key_status — IRC confirms no table was created."
pass "Spark status=$missing_key_spark_status, reason=KEY_REQUIRED, persistence=none."
pause

step \
  '8/12' \
  'Create and write an approved encrypted table' \
  'Use the allowed key ID so policy admits the create and Spark writes a real format-v3 Iceberg table through Gravitino and IRC.'
approved_sql=$(render_sql "$SCRIPT_DIR/spark-cuj.sql")
show_sql "$SCRIPT_DIR/spark-cuj.sql" "$approved_sql"
request_block 'Spark SQL -> Gravitino policy gate -> IRC commit -> encrypted warehouse objects'
show_command \
  "printf '%s\\n' '<SQL above>' | $SCRIPT_DIR/spark-sql.sh --conf spark.sql.gravitino.metalake=$METALAKE_NAME -f /dev/stdin"
set +e
printf '%s\n' "$approved_sql" | "$SCRIPT_DIR/spark-sql.sh" \
  --conf "spark.sql.gravitino.metalake=$METALAKE_NAME" \
  -f /dev/stdin >"$TMP_DIR/spark-write.log" 2>&1
spark_write_status=$?
set -e
if [[ "$spark_write_status" -ne 0 ]]; then
  tail -n 80 "$TMP_DIR/spark-write.log" >&2
  fail "Approved Spark create/write exited with status $spark_write_status."
fi
grep -Fq 'APPROVED_SPARK_WRITE_OK' "$TMP_DIR/spark-write.log" \
  || fail "Spark did not report the approved write proof."
spark_write_proof=$(grep -m1 'APPROVED_SPARK_WRITE_OK' "$TMP_DIR/spark-write.log" \
  | sed 's/^[[:space:]]*//')
output_block "Spark exit status $spark_write_status — query proof:"
printf '    %s\n' "$spark_write_proof"
pass "Spark created and wrote $CATALOG_NAME.$SCHEMA_NAME.$ENCRYPTED_TABLE_NAME."
pause

step \
  '9/12' \
  'Keep IRC keyless and fail closed' \
  'Prove IRC can serve encrypted table metadata without a token, while server-side readers refuse ciphertext with a typed limitation.'
server_load_file="$TMP_DIR/server-load.json"
request_block \
  "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME"
show_command \
  "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME' | jq '{metadataLocation: .[\"metadata-location\"], keyId: .metadata.properties[\"encryption.key-id\"]}'"
server_load_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME" \
  "$server_load_file")
expect_code "$server_load_status" 200 "$server_load_file" "keyless IRC metadata load"
jq -e \
  '.metadata.properties["encryption.key-id"] == "customer-pii-v1"' \
  "$server_load_file" >/dev/null \
  || fail "IRC load-table did not return the encrypted table metadata."
output_block "HTTP $server_load_status — keyless metadata load succeeded:"
jq "${JQ_OUTPUT_ARGS[@]}" '{
  metadataLocation: .["metadata-location"],
  keyId: .metadata.properties["encryption.key-id"]
}' "$server_load_file"
pass 'Normal IRC load-table needed no KMS token.'

server_plan_file="$TMP_DIR/server-plan.json"
request_block \
  "POST $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME/plan"
input_json '{}'
show_command \
  "curl -sS -X POST -H 'Content-Type: application/json' --data '{}' '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME/plan' | jq '.error | {code, type, message}'"
server_plan_status=$(request \
  POST \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME/plan" \
  "$server_plan_file" \
  '{}')
expect_code "$server_plan_status" 400 "$server_plan_file" "keyless IRC plan scan guard"
jq -e \
  '.error.code == 400
    and .error.type == "EncryptedTableServerSideReadException"
    and (.error.message | contains("ENCRYPTED_TABLE_SERVER_READ_UNSUPPORTED"))' \
  "$server_plan_file" >/dev/null \
  || fail "IRC did not return the typed encrypted server-read guard."
output_block "HTTP $server_plan_status — typed server-side read guard:"
jq "${JQ_OUTPUT_ARGS[@]}" '.error | {code, type, message}' "$server_plan_file"
pass 'Server-side plan scan stopped with a typed 400, never a key/file error.'
pause

step \
  '10/12' \
  'Prevent key-ID mutation' \
  'Prove the logical encryption key reference is create-time-only on both mutation paths and remains unchanged after rejection.'
gravitino_update_file="$TMP_DIR/gravitino-key-update.json"
request_block \
  "PUT $API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME"
input_json "$GRAVITINO_KEY_UPDATE_PAYLOAD"
show_command \
  "curl -sS -X PUT -H 'Content-Type: application/json' --data-binary '<JSON above>' '$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME' | jq '{code, type, message}'"
gravitino_update_status=$(request \
  PUT \
  "$API_BASE/metalakes/$METALAKE_NAME/catalogs/$CATALOG_NAME/schemas/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME" \
  "$gravitino_update_file" \
  "$GRAVITINO_KEY_UPDATE_PAYLOAD")
expect_code "$gravitino_update_status" 400 "$gravitino_update_file" "Gravitino key-ID update"
jq -e \
  '.type == "EncryptionKeyIdImmutableException"
    and ((.message // "") | contains("decisionId="))
    and ((.message // "") | contains("reason=KEY_ID_IMMUTABLE"))' \
  "$gravitino_update_file" >/dev/null \
  || fail "Gravitino did not return a typed key-ID immutability error."
output_block "HTTP $gravitino_update_status — Gravitino mutation guard:"
pretty_error "$gravitino_update_file"

irc_update_file="$TMP_DIR/irc-key-update.json"
request_block \
  "POST $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME"
input_json "$IRC_KEY_UPDATE_PAYLOAD"
show_command \
  "curl -sS -X POST -H 'Content-Type: application/json' --data-binary '<JSON above>' '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME' | jq '.error | {code, type, message}'"
irc_update_status=$(request \
  POST \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME" \
  "$irc_update_file" \
  "$IRC_KEY_UPDATE_PAYLOAD")
expect_code "$irc_update_status" 400 "$irc_update_file" "direct IRC key-ID update"
jq -e \
  '(.type // .error.type // "") == "EncryptionKeyIdImmutableException"
    and ((.message // .error.message // "") | contains("decisionId="))
    and ((.message // .error.message // "") | contains("reason=KEY_ID_IMMUTABLE"))' \
  "$irc_update_file" >/dev/null \
  || fail "IRC did not return a typed key-ID immutability error."
output_block "HTTP $irc_update_status — direct IRC mutation guard:"
pretty_error "$irc_update_file"

immutable_reload_file="$TMP_DIR/immutable-reload.json"
request_block \
  "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME"
show_command \
  "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME' | jq '.metadata.properties[\"encryption.key-id\"]'"
immutable_reload_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME" \
  "$immutable_reload_file")
expect_code "$immutable_reload_status" 200 "$immutable_reload_file" "immutable key-ID reload"
jq -e '.metadata.properties["encryption.key-id"] == "customer-pii-v1"' \
  "$immutable_reload_file" >/dev/null \
  || fail "A rejected update changed the original encryption key ID."
output_block "HTTP $immutable_reload_status — encryption.key-id=customer-pii-v1"
pass 'IRC reload still reports the original logical key ID.'

evidence_block 'Search service logs for the rejected mutation:'
show_command \
  "docker compose logs --no-color --tail 200 gravitino iceberg-rest | grep -Ei 'key ID|encryption.key-id'"
"${COMPOSE[@]}" logs --no-color --tail 200 gravitino iceberg-rest \
  >"$TMP_DIR/key-update.log" 2>&1 || true
if grep -Ei 'key ID|encryption.key-id' "$TMP_DIR/key-update.log" >"$TMP_DIR/key-update-lines.log"; then
  output_block 'Matching service log lines:'
  cat "$TMP_DIR/key-update-lines.log"
else
  note 'The typed 400 bodies are the available audit evidence; this POC has no durable audit sink.'
fi
pause

step \
  '11/12' \
  'Inspect ciphertext and the wrapped key' \
  'Correlate IRC metadata to warehouse files and prove data and manifests are encrypted without revealing any token or key material.'
irc_list_file="$TMP_DIR/irc-list.json"
request_block "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables"
show_command "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables' | jq ."
irc_list_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables" \
  "$irc_list_file")
expect_code "$irc_list_status" 200 "$irc_list_file" "IRC table list"
output_block "HTTP $irc_list_status — tables visible directly through IRC:"
pretty_json "$irc_list_file"

irc_table_file="$TMP_DIR/irc-table.json"
request_block \
  "GET $IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME"
show_command \
  "curl -sS '$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME' | jq '{metadataLocation: .[\"metadata-location\"], formatVersion: .metadata[\"format-version\"], keyId: .metadata.properties[\"encryption.key-id\"], encryptionKeyCount: (.metadata[\"encryption-keys\"] | length), responseConfig: .config}'"
irc_table_status=$(request \
  GET \
  "$IRC_BASE/v1/namespaces/$SCHEMA_NAME/tables/$ENCRYPTED_TABLE_NAME" \
  "$irc_table_file")
expect_code "$irc_table_status" 200 "$irc_table_file" "IRC encrypted-table load"
if ! jq -e '
  [(.config // {})
    | to_entries[]
    | select(.key | test("token"; "i"))]
  | length == 0' "$irc_table_file" >/dev/null; then
  fail "IRC load-table config exposed an unsafe token property."
fi
output_block "HTTP $irc_table_status — projected metadata with wrapped-key bytes withheld:"
jq "${JQ_OUTPUT_ARGS[@]}" '{
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
metadata_dir=${metadata_path%/*}
table_root=${metadata_dir%/*}
evidence_block "Correlate metadata-location to the shared warehouse path $metadata_path"
show_command "docker compose exec -T iceberg-rest test -f '$metadata_path'"
"${COMPOSE[@]}" exec -T iceberg-rest test -f "$metadata_path" \
  || fail "IRC metadata-location does not exist in the shared warehouse."
pass "IRC metadata-location exists under this run's table root: $table_root"

evidence_block 'Locate and size every object for this table:'
show_command \
  "docker compose exec -T iceberg-rest sh -c 'find \"\$1\" -type f -print | sort' _ '$table_root'"
output_block 'Current table files:'
"${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'find "$1" -type f -print | sort' _ "$table_root"
show_command \
  "docker compose exec -T iceberg-rest sh -c 'find \"\$1\" -type f -exec ls -lh {} \\; | sort -k9' _ '$table_root'"
output_block 'Current table file sizes:'
"${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'find "$1" -type f -exec ls -lh {} \; | sort -k9' _ "$table_root"

evidence_block 'Read the exact metadata.json named by IRC (wrapped ciphertext is safe to display):'
show_command "docker compose exec -T iceberg-rest cat '$metadata_path'"
output_block 'Raw Iceberg metadata.json:'
"${COMPOSE[@]}" exec -T iceberg-rest cat "$metadata_path"
printf '\n'

data_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'find "$1" -type f -name "*.parquet" -print | sort' _ "$table_root")
data_file=${data_files%%$'\n'*}
data_file=${data_file%$'\r'}
avro_files=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c \
  'find "$1" -type f -name "*.avro" -print | sort' _ "$table_root")
avro_file=${avro_files%%$'\n'*}
avro_file=${avro_file%$'\r'}
[[ -n "$data_file" && -n "$avro_file" ]] \
  || fail "Expected encrypted Parquet and Avro objects were not written."

evidence_block 'Inspect only the first 64 bytes of one data file and one manifest:'
for binary_file in "$data_file" "$avro_file"; do
  show_command \
    "docker compose exec -T iceberg-rest sh -c 'head -c 64 \"\$1\" | od -An -tx1c' _ '$binary_file'"
  output_block "First 64 bytes of $binary_file:"
  "${COMPOSE[@]}" exec -T iceberg-rest sh -c \
    'head -c 64 "$1" | od -An -tx1c' _ "$binary_file"
done

data_magic=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c 'head -c 4 "$1"' _ "$data_file")
avro_magic=$("${COMPOSE[@]}" exec -T iceberg-rest sh -c 'head -c 4 "$1"' _ "$avro_file")
[[ "$data_magic" == PARE ]] || fail "Data object magic was $data_magic, not PARE."
[[ "$avro_magic" == AGS1 ]] || fail "Manifest object magic was $avro_magic, not AGS1."
pass 'Encrypted object magic is data=PARE and manifest=AGS1.'

evidence_block 'Search raw ciphertext for the known plaintext marker:'
show_command \
  "docker compose exec -T iceberg-rest grep -aF 'governed-encryption-poc-marker' '$data_file' '$avro_file'"
if "${COMPOSE[@]}" exec -T iceberg-rest \
  grep -aF 'governed-encryption-poc-marker' "$data_file" "$avro_file" >/dev/null 2>&1; then
  fail "A raw encrypted object exposed the known plaintext marker."
fi
output_block 'grep exit status is nonzero: marker not found.'
pass 'The known plaintext marker is absent from raw data and manifest bytes.'

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
wrapped_remainder=${wrapped_envelope#*:}
wrapped_prefix="vault:${wrapped_remainder%%:*}:"
pass 'Iceberg key metadata decodes to a Vault Transit ciphertext; decoded value not printed.'

request_block 'POST OpenBao /v1/transit/decrypt/customer-pii-v1 (inside the credentialed bootstrap container)'
input_block "ciphertext=${wrapped_prefix}<redacted>; token=<mounted secret, not printed>"
show_command \
  "docker compose run --rm --no-deps openbao-kms-bootstrap bao write transit/decrypt/customer-pii-v1 ciphertext='${wrapped_prefix}<redacted>'"
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
output_block 'OpenBao returned plaintext key material to the credentialed process; output withheld.'
pass 'KMS unwrap succeeded without exposing the unwrapped key.'
note 'Transit unwraps Iceberg key metadata only; whole PARE/AGS1 files are decrypted by Iceberg, not bao.'
pause

step \
  '12/12' \
  'Read through a fresh Spark session' \
  'Reload IRC metadata in a new client process, unwrap through KMS, and let Iceberg decrypt the AGS1/PARE layers into the original row.'
readback_sql=$(render_sql "$SCRIPT_DIR/spark-readback.sql")
show_sql "$SCRIPT_DIR/spark-readback.sql" "$readback_sql"
request_block 'Fresh Spark SQL process -> IRC metadata -> OpenBao unwrap -> Iceberg file decryption'
show_command \
  "printf '%s\\n' '<SQL above>' | $SCRIPT_DIR/spark-sql.sh --conf spark.sql.gravitino.metalake=$METALAKE_NAME -f /dev/stdin"
set +e
printf '%s\n' "$readback_sql" | "$SCRIPT_DIR/spark-sql.sh" \
  --conf "spark.sql.gravitino.metalake=$METALAKE_NAME" \
  -f /dev/stdin >"$TMP_DIR/spark-read.log" 2>&1
spark_read_status=$?
set -e
if [[ "$spark_read_status" -ne 0 ]]; then
  tail -n 80 "$TMP_DIR/spark-read.log" >&2
  fail "Fresh Spark readback exited with status $spark_read_status."
fi
grep -Fq 'APPROVED_SPARK_READ_OK' "$TMP_DIR/spark-read.log" \
  || fail "Spark did not report the approved read proof."
grep -Fq 'governed-encryption-poc-marker' "$TMP_DIR/spark-read.log" \
  || fail "Spark did not recover the known plaintext marker through Iceberg and KMS."
spark_read_proof=$(grep -m1 'APPROVED_SPARK_READ_OK' "$TMP_DIR/spark-read.log" \
  | sed 's/^[[:space:]]*//')
spark_marker_proof=$(grep -m1 'governed-encryption-poc-marker' "$TMP_DIR/spark-read.log" \
  | sed 's/^[[:space:]]*//')
output_block "Spark exit status $spark_read_status — decrypted query result:"
printf '    %s\n' "$spark_read_proof"
if [[ "$spark_marker_proof" != "$spark_read_proof" ]]; then
  printf '    %s\n' "$spark_marker_proof"
fi
pass 'A fresh Spark session recovered the expected row through layered decryption.'

printf '\n%bCUJ PASSED%b  run=%s  metalake=%s  schema=%s\n' \
  "${COLOR_BOLD}${COLOR_GREEN}" "$COLOR_RESET" "$RUN_SUFFIX" "$METALAKE_NAME" "$SCHEMA_NAME"
printf '  The persistent cluster and this run remain available for inspection.\n'
printf '\n  %bNEXT%b\n' "${COLOR_BOLD}${COLOR_CYAN}" "$COLOR_RESET"
printf '  Direct IRC boundary: DEMO_RUN_SUFFIX=%s %s/direct-irc-boundary.sh\n' \
  "$RUN_SUFFIX" "$SCRIPT_DIR"
printf '  Key rotation:       DEMO_RUN_SUFFIX=%s %s/rotation-checkpoint.sh\n' \
  "$RUN_SUFFIX" "$SCRIPT_DIR"
printf '  Stop cluster:       %s/down.sh\n' "$SCRIPT_DIR"
