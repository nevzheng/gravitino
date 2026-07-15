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

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "Docker is required but was not found on PATH."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."
docker info >/dev/null 2>&1 || fail "The Docker daemon is not available."

printf '[1/3] Building the current source artifacts...\n'
"$SCRIPT_DIR/build.sh"

printf '[2/3] Starting Transit, IRC, Gravitino, and Spark...\n'
"${COMPOSE[@]}" up --detach --build --wait --wait-timeout 480

printf '[3/3] Demo cluster is ready and will remain running.\n'
printf '  Gravitino REST: http://localhost:%s\n' "${GRAVITINO_HOST_PORT:-8090}"
printf '  Iceberg REST:   http://localhost:%s/iceberg\n' "${ICEBERG_REST_HOST_PORT:-9001}"
printf '  Transit API:    http://localhost:%s\n' "${TRANSIT_HOST_PORT:-18200}"
printf '  IRC warehouse:  file:///warehouse (shared Docker volume)\n'
printf '\nRun the customer journey with:\n  %s/demo-cuj.sh\n' "$SCRIPT_DIR"
printf 'Or open a configured Spark SQL shell with:\n  %s/spark-sql.sh\n' "$SCRIPT_DIR"
printf 'Stop and remove the demo with:\n  %s/down.sh\n' "$SCRIPT_DIR"
