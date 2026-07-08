#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Milestone 1 smoke test: prove the native metadata API can load an Iceberg V3 table
# containing a `variant` column (PR #11932). See VALIDATION_PLAN.md.
#
# Assumes a Gravitino server from the iceberg-v3-type-e2e branch is running locally with
# the default config (native API :8090, embedded IRC :9001, memory backend).
#
#   distribution/package/bin/gravitino.sh start
#   dev/e2e/iceberg-v3/smoke.sh

set -uo pipefail

IRC="${IRC:-http://localhost:9001/iceberg/v1}"
GRAV="${GRAV:-http://localhost:8090/api}"
IRC_URI_FOR_CATALOG="${IRC_URI_FOR_CATALOG:-http://localhost:9001/iceberg}"
ML="${ML:-v3check}"
NS="v3_test"
TBL="variant_probe"

j() { printf '%s' "$1"; }
say() { printf '\n=== %s ===\n' "$1"; }

say "0. create metalake ${ML} (native API)"
curl -s -X POST "${GRAV}/metalakes" -H 'Content-Type: application/json' \
  -d "{\"name\":\"${ML}\",\"comment\":\"iceberg v3 e2e\",\"properties\":{}}" >/dev/null
echo "ok (idempotent — 409 if it already exists)"

say "1. create a format-version-3 table with a variant column via the IRC (:9001)"
curl -s -X POST "${IRC}/namespaces" -H 'Content-Type: application/json' \
  -d '{"namespace":["'"${NS}"'"]}' >/dev/null
curl -s -X POST "${IRC}/namespaces/${NS}/tables" -H 'Content-Type: application/json' -d '{
  "name": "'"${TBL}"'",
  "schema": {"type":"struct","schema-id":0,"fields":[
    {"id":1,"name":"id","required":true,"type":"long"},
    {"id":2,"name":"payload","required":false,"type":"variant"}]},
  "properties": {"format-version":"3"}
}' >/dev/null
echo "created ${NS}.${TBL}"

say "2. round-trip via the IRC (:9001) — baseline (worked pre-fix)"
IRC_LOAD=$(curl -s "${IRC}/namespaces/${NS}/tables/${TBL}")
if j "${IRC_LOAD}" | grep -q '"variant"'; then
  echo "IRC round-trip OK: variant field present"
else
  echo "WARN: IRC did not return a variant field — check IRC/backend setup"
  j "${IRC_LOAD}" | head -c 400; echo
fi

say "3. register IRC as a native catalog (REST backend, NO warehouse — issue #11943)"
curl -s -X POST "${GRAV}/metalakes/${ML}/catalogs" -H 'Content-Type: application/json' -d '{
  "name":"irc_probe",
  "type":"RELATIONAL",
  "provider":"lakehouse-iceberg",
  "comment":"probe without warehouse",
  "properties":{"catalog-backend":"rest","uri":"'"${IRC_URI_FOR_CATALOG}"'"}
}' >/dev/null
echo "registered catalog irc_probe"

say "4. THE ASSERTION: load the variant table through the NATIVE API (:8090)"
NATIVE_LOAD=$(curl -s "${GRAV}/metalakes/${ML}/catalogs/irc_probe/schemas/${NS}/tables/${TBL}")
echo "${NATIVE_LOAD}" | head -c 600; echo

if j "${NATIVE_LOAD}" | grep -q 'UnsupportedOperationException'; then
  echo; echo "FAIL: native API still throws UnsupportedOperationException — fix not effective"
  exit 1
elif j "${NATIVE_LOAD}" | grep -q '"variant"'; then
  echo; echo "PASS: native API loaded the table and surfaced the column as variant"
  exit 0
else
  echo; echo "INCONCLUSIVE: no exception, but no variant token found — inspect the payload above"
  exit 2
fi
