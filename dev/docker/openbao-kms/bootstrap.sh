#!/bin/sh
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

set -eu

if ! bao secrets list -format=json | grep -q '"transit/"'; then
  bao secrets enable -path=transit transit
fi

# The runtime token only has update access, but disabling upsert also protects against a future
# policy mistake accidentally creating an unapproved key on first use.
bao write transit/config/keys disable_upsert=true >/dev/null
bao write -f transit/keys/customer-pii-v1 type=aes256-gcm96 >/dev/null
bao policy write gravitino-iceberg-encryption /bootstrap/transit-policy.hcl >/dev/null

umask 077
token_file=/run/openbao-client/token
temporary_token_file=${token_file}.tmp
bao token create \
  -field=token \
  -orphan \
  -no-default-policy \
  -policy=gravitino-iceberg-encryption \
  -ttl=24h \
  -renewable=false > "${temporary_token_file}"
mv "${temporary_token_file}" "${token_file}"

# The named volume is mounted only into explicitly authorized demo containers. World-readable
# permissions within that volume avoid coupling the OpenBao, Gravitino, and Spark image user IDs.
chmod 0444 "${token_file}"
