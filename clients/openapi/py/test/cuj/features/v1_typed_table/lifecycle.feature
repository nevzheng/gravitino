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

@cuj @api @lifecycle @expected_error
Feature: Exercise the typed V1 table contract across connector profiles
  The target V1 POST shape uses shared storage and zero or one typed provider
  options block. These scenarios are explicitly opt-in: each profile needs a
  deployed typed V1 server and its own externally provisioned catalog.

  Scenario Outline: Create, get, metadata-update, and delete a typed V1 table
    Given a configured "<provider>" typed V1 table contract environment
    When I create the minimal typed V1 table
    Then the typed V1 table create outcome is accepted or recorded
    When I get the typed V1 table
    Then the typed V1 GET preserves shared storage and provider options
    When I fully replace typed V1 table metadata with its current ETag
    Then the typed V1 PUT preserves shared storage and provider options
    When I delete the typed V1 table with its current ETag
    Then the typed V1 table is deleted

    Examples:
      | provider |
      | lance |
      | delta |
      | iceberg |
      | hive |
      | glue |
      | mysql |
      | postgresql |
      | doris |
      | starrocks |
      | oceanbase |
      | clickhouse |
      | hologres |

  Scenario: Record the Paimon typed V1 create limitation without blocking
    Given a configured "paimon" typed V1 table contract environment
    When I create the minimal typed V1 table
    Then the typed V1 table create outcome is accepted or recorded

  Scenario: Record the Hudi typed V1 create limitation without blocking
    Given a configured "hudi" typed V1 table contract environment
    When I create the minimal typed V1 table
    Then the typed V1 table create outcome is accepted or recorded
