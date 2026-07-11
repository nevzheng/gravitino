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

# Creates the metalake -> catalog -> schema fixtures that hooks.py pins path
# parameters to. Idempotent-ish: re-running just yields 409s, which is fine.
set -u
BASE="${GRAVITINO_URL:-http://localhost:8090/api}"
CT="Content-Type: application/json"

echo "creating fixtures against ${BASE}"
curl -s -o /dev/null -w "  metalake test_ml -> %{http_code}\n" -X POST "${BASE}/metalakes" -H "${CT}" \
  -d '{"name":"test_ml","comment":"contract-test fixture","properties":{}}'
curl -s -o /dev/null -w "  catalog  test_cat -> %{http_code}\n" -X POST "${BASE}/metalakes/test_ml/catalogs" -H "${CT}" \
  -d '{"name":"test_cat","type":"FILESET","provider":"hadoop","comment":"fixture","properties":{"location":"/tmp/gravitino-contract-test"}}'
curl -s -o /dev/null -w "  schema   test_sch -> %{http_code}\n" -X POST "${BASE}/metalakes/test_ml/catalogs/test_cat/schemas" -H "${CT}" \
  -d '{"name":"test_sch","comment":"fixture","properties":{}}'
