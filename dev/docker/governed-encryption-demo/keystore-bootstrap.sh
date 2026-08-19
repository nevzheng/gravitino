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

KEYSTORE_DIR=${KEYSTORE_DIR:-/run/secrets/kms}
KEYSTORE_PATH=${KEYSTORE_PATH:-$KEYSTORE_DIR/demo.p12}
PASSWORD_FILE=${PASSWORD_FILE:-$KEYSTORE_DIR/password}
ALIAS=${KEY_ALIAS:-customer-pii-v1}
PASSWORD=${KEYSTORE_PASSWORD:-changeit}

mkdir -p "$KEYSTORE_DIR"
printf '%s' "$PASSWORD" >"$PASSWORD_FILE"
chmod 600 "$PASSWORD_FILE"

# PKCS12 secret-key entry used as the Iceberg wrapping key alias.
keytool -genseckey \
  -alias "$ALIAS" \
  -keyalg AES \
  -keysize 256 \
  -keystore "$KEYSTORE_PATH" \
  -storetype PKCS12 \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD"

chmod 600 "$KEYSTORE_PATH"
printf 'Wrote keystore %s (alias %s)\n' "$KEYSTORE_PATH" "$ALIAS"
