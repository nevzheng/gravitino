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

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

run_invalid_fixture() {
  local fixture="$1"
  shift
  local output_file
  local error_file
  local exit_code
  local expected_rule

  output_file="$(mktemp)"
  error_file="$(mktemp)"
  trap 'rm -f "${output_file}" "${error_file}"' RETURN

  set +e
  "${script_directory}/node_modules/.bin/spectral" lint "${fixture}" \
    --ruleset "${script_directory}/.spectral-v1.yaml" \
    --format json >"${output_file}" 2>"${error_file}"
  exit_code=$?
  set -e

  if [[ ${exit_code} -ne 1 ]]; then
    cat "${error_file}" >&2
    cat "${output_file}" >&2
    echo "Expected Spectral to reject ${fixture}; got exit code ${exit_code}." >&2
    exit 1
  fi

  if grep -q "Error running Spectral" "${error_file}"; then
    cat "${error_file}" >&2
    echo "Spectral crashed while evaluating ${fixture}." >&2
    exit 1
  fi

  for expected_rule in "$@"; do
    if ! grep -q "\"code\": \"${expected_rule}\"" "${output_file}"; then
      cat "${output_file}" >&2
      echo "Expected ${fixture} to trigger ${expected_rule}." >&2
      exit 1
    fi
  done
}

run_invalid_fixture \
  "${script_directory}/fixtures/spectral-null-and-duplicate-enum.openapi.yaml" \
  gravitino-v1-no-duplicate-enum

run_invalid_fixture \
  "${script_directory}/fixtures/spectral-v1-missing-dialect.openapi.yaml" \
  gravitino-v1-json-schema-dialect

run_invalid_fixture \
  "${script_directory}/fixtures/spectral-v1-governance-invalid.openapi.yaml" \
  gravitino-v1-component-parameter-names-pascal-case \
  gravitino-v1-operation-summary \
  gravitino-v1-operation-security-explicit \
  gravitino-v1-get-head-no-request-body \
  gravitino-v1-head-no-content \
  gravitino-v1-bodyless-status-no-content \
  gravitino-v1-no-default-response \
  gravitino-v1-created-location \
  gravitino-v1-not-modified-etag \
  gravitino-v1-unauthorized-challenge \
  gravitino-v1-method-not-allowed-allow \
  gravitino-v1-rate-limited-retry-after \
  gravitino-v1-media-type-schema-and-example \
  gravitino-v1-response-correlation-and-cache \
  gravitino-v1-path-parameter-contract \
  gravitino-v1-no-authorization-parameter-component \
  gravitino-v1-no-authorization-parameter-inline \
  gravitino-v1-json-schema-dialect \
  gravitino-v1-openapi-version \
  gravitino-v1-request-schemas-closed \
  gravitino-v1-response-schemas-open \
  gravitino-v1-error-envelope \
  gravitino-v1-error-fields \
  gravitino-v1-error-responses-400 \
  gravitino-v1-error-responses-500 \
  gravitino-v1-route-prefix \
  gravitino-v1-semantic-version

run_invalid_fixture \
  "${script_directory}/fixtures/spectral-v1-referenced-response-invalid.openapi.yaml" \
  gravitino-v1-unauthorized-challenge

echo "V1 Spectral governance regression checks passed."
