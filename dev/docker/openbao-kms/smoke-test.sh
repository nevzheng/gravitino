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
CLEANUP_STARTED=0
TRANSIT_IMAGE_NAME=${TRANSIT_IMAGE:-openbao/openbao:2.6.0}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ "$CLEANUP_STARTED" -eq 1 ]]; then
    return
  fi
  CLEANUP_STARTED=1

  printf '[4/4] Removing the OpenBao containers, network, and client-token volume...\n'
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v docker >/dev/null 2>&1 || fail "Docker is required but was not found on PATH."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."
docker info >/dev/null 2>&1 || fail "The Docker daemon is not available."

# Remove leftovers from an interrupted earlier run before creating a fresh, ephemeral key.
"${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true

printf '[1/4] Starting the ephemeral Transit service (%s)...\n' "$TRANSIT_IMAGE_NAME"
"${COMPOSE[@]}" up --detach --wait openbao-kms

printf '[2/4] Creating customer-pii-v1 and a generated least-privilege client token...\n'
"${COMPOSE[@]}" run --rm --no-deps openbao-kms-bootstrap

printf '[3/4] Exercising Transit with the least-privilege token...\n'
"${COMPOSE[@]}" run --rm --no-deps --entrypoint /bin/sh openbao-kms-bootstrap -eu -c '
  token_file=/run/openbao-client/token
  plaintext_base64=Z3Jhdml0aW5vLWdvdmVybmVkLWVuY3J5cHRpb24tZGVtbw==

  if [ ! -s "$token_file" ]; then
    printf "ERROR: Bootstrap did not create the client token file.\n" >&2
    exit 1
  fi

  client_token=$(cat "$token_file")
  export BAO_ADDR="${BAO_ADDR:-$VAULT_ADDR}"
  export VAULT_ADDR="$BAO_ADDR"
  export BAO_TOKEN="$client_token"
  export VAULT_TOKEN="$client_token"
  unset client_token

  ciphertext=$(bao write -field=ciphertext \
    transit/encrypt/customer-pii-v1 plaintext="$plaintext_base64")
  case "$ciphertext" in
    vault:v*:*) ;;
    *)
      printf "ERROR: OpenBao returned an unexpected Transit ciphertext.\n" >&2
      exit 1
      ;;
  esac

  decrypted_base64=$(bao write -field=plaintext \
    transit/decrypt/customer-pii-v1 ciphertext="$ciphertext")
  if [ "$decrypted_base64" != "$plaintext_base64" ]; then
    printf "ERROR: Approved-key encryption did not round-trip.\n" >&2
    exit 1
  fi
  printf "  PASS approved key: encrypt/decrypt round-trip succeeded.\n"

  if bao write -field=ciphertext \
    transit/encrypt/unapproved-key plaintext="$plaintext_base64" >/dev/null 2>&1; then
    printf "ERROR: The least-privilege token used an unapproved key.\n" >&2
    exit 1
  fi
  printf "  PASS unapproved key: Transit request was rejected.\n"
'

printf 'OpenBao KMS smoke test passed.\n'
