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

@cuj @api @lifecycle
Feature: Drive the complete V1 resource hierarchy lifecycle
  The route-versioned V1 API is exercised directly through a Playwright
  APIRequestContext. No browser, generated-client write call, or legacy /api
  fallback can satisfy these scenarios.

  Scenario: Create, read, list, fully replace, and delete every parent resource
    Given a configured V1 parent hierarchy
    When I create the metalake, catalog, and schema through V1
    And I get each V1 parent resource
    And I list each V1 parent collection
    And I fully replace each supported V1 parent state with its current ETag
    Then each parent V1 PUT preserves its full supported desired state
    When I delete the schema, catalog, and metalake through V1 with their current ETags
    Then the V1 parent hierarchy is deleted

  @expected_error
  Scenario Outline: Drive a table lifecycle through a configured writable connector
    Given a configured "<provider>" V1 table lifecycle environment
    When I create the V1 parent hierarchy for the table
    And I create the documented minimal V1 table
    And I get the V1 table
    And I list the V1 table collection
    And I fully replace supported V1 table metadata with its current ETag
    Then the table V1 PUT preserves comment and full properties desired state
    When I attempt a V1 table full replacement with a "stale" precondition
    Then the table API returns the strict non-retryable PRECONDITION_FAILED error
    When I attempt a V1 table full replacement with a "missing" precondition
    Then the table API returns the strict non-retryable PRECONDITION_FAILED error
    When I attempt a structural V1 table replacement with its current ETag
    Then the documented table-structural capability error is recorded without blocking the suite
    When I delete the V1 table with its current ETag
    Then the V1 table is deleted

    Examples:
      | provider |
      | lance |
      | iceberg |
      | hive |
      | glue |
      | paimon |
      | mysql |
      | postgresql |
      | doris |
      | starrocks |
      | oceanbase |
      | clickhouse |
      | hologres |
