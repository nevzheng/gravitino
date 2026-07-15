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

@cuj @api @create_table @failures
Feature: Report public create-table errors
  Validation errors are contractual: the response must use the documented
  public envelope, machine-readable type, retryability, and safe details.

  Scenario Outline: Reject an invalid create-table request
    Given a configured "<provider>" create-table environment
    When I submit the "<use_case>" invalid create-table request
    Then the API returns the non-retryable "INVALID_ARGUMENT" error with status 400
    And the error identifies the invalid request field

    Examples:
      | provider | use_case |
      | lance | blank_table_name |
      | lance | missing_columns |
      | lance | duplicate_column_names |
      | lance | unknown_layout_field |
      | mysql | auto_increment_without_key |
      | oceanbase | auto_increment_without_key |

  Scenario Outline: Report a missing table parent with a public not-found error
    Given a configured "lance" create-table environment with a missing "<parent>"
    And a unique table name
    When I submit the "minimal" documented create-table use case
    Then the API returns the non-retryable "<error_type>" error with status 404
    And the error identifies the missing resource

    Examples:
      | parent | error_type |
      | metalake | METALAKE_NOT_FOUND |
      | catalog | CATALOG_NOT_FOUND |
      | schema | SCHEMA_NOT_FOUND |

  Scenario: Reject a duplicate table with the documented conflict error
    Given a configured "lance" create-table environment
    And an existing table created by the "minimal" documented use case
    When I submit the same documented create-table use case again
    Then the API returns the documented duplicate-table conflict error
    And the error is non-retryable

  Scenario: Reject an unauthenticated caller
    Given a configured "lance" create-table environment without credentials
    And a unique table name
    When I submit the "minimal" documented create-table use case
    Then the API returns the non-retryable "UNAUTHENTICATED" error with status 401

  Scenario: Reject an unauthorized caller
    Given a configured "lance" create-table environment with insufficient create-table privileges
    And a unique table name
    When I submit the "minimal" documented create-table use case
    Then the API returns the non-retryable "PERMISSION_DENIED" error with status 403

  @expected_error
  Scenario Outline: Record a documented connector limitation without blocking the suite
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "<use_case>" documented create-table use case
    Then the documented expected API error is recorded without blocking the suite

    Examples:
      | provider | use_case |
      | hive | default_values |
      | iceberg | default_values |
      | glue | default_values |
      | paimon | column_field_default_values |
      | paimon | auto_increment |
      | lance | auto_increment |
      | hologres | auto_increment |
      | starrocks | indexes |
