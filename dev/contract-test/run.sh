#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Run the Schemathesis contract test against a locally-running Gravitino.
# Prereqs: a Gravitino server running at $GRAVITINO_URL (see README), Node
# (for redocly bundle) and Python 3.10+.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="${GRAVITINO_URL:-http://localhost:8090/api}"
SPEC_SRC="${HERE}/../../docs/open-api/openapi.yaml"
MAX_EXAMPLES="${MAX_EXAMPLES:-25}"

# 1. bundle the multi-file spec into a single document
npx --yes @redocly/cli@2 bundle "${SPEC_SRC}" -o "${HERE}/openapi.json"

# 2. python env with schemathesis
if [ ! -d "${HERE}/.venv" ]; then python3 -m venv "${HERE}/.venv"; fi
"${HERE}/.venv/bin/pip" install --quiet -r "${HERE}/requirements.txt"

# 3. seed fixtures the hooks pin to
GRAVITINO_URL="${BASE}" bash "${HERE}/fixtures.sh"

# 4. run. Report-only.
#    --phases fuzzing: the coverage phase injects deterministic path values
#      (e.g. "0") that bypass the pinning hook and hit nonexistent metalakes,
#      re-triggering the metalake-500 bug. Fuzzing keeps the pin effective.
#    -c ...: only the four contract-shape checks. The auth checks (ignored_auth,
#      missing_required_header) and unsupported_method are excluded — a no-auth
#      dev server accepts everything, so they are noise until an auth-configured
#      server is tested. See README for the two-job plan.
cd "${HERE}"
SCHEMATHESIS_HOOKS=hooks "${HERE}/.venv/bin/schemathesis" run openapi.json \
  --url "${BASE}" \
  --max-examples "${MAX_EXAMPLES}" \
  --workers 4 \
  --phases fuzzing \
  --report vcr --report-vcr-path "${HERE}/cassette.yaml" \
  -c not_a_server_error \
  -c response_schema_conformance \
  -c content_type_conformance \
  -c status_code_conformance
