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

@cuj @api @create_table @lifecycle
Feature: Create a table with provider lifecycle semantics
  Lakehouse-generic table formats have important creation and storage
  lifecycle behavior that cannot be covered by a generic create request.

  Scenario Outline: Create a fresh Lance table using a documented lifecycle mode
    Given a configured "lance" create-table environment
    And a unique table name
    When I submit the "<use_case>" documented create-table use case
    Then the create operation succeeds
    And the documented provider side effect exists
    And the fixture cleans up the created table

    Examples:
      | use_case |
      | managed_table |
      | external_table |
      | declared_table |

  Scenario: Register a pre-existing Lance dataset
    Given a configured "lance" create-table environment
    And a unique table name
    And a pre-existing provider dataset for that table
    When I submit the "registered_table" documented create-table use case
    Then the create operation succeeds
    And the documented provider side effect exists
    And the fixture cleans up the created table

  @expected_error
  Scenario: Observe Lance registration schema discovery without caller columns
    Given a configured "lance" create-table environment
    And a unique table name
    And a pre-existing provider dataset for that table
    When I submit the "registered_table_schema_discovery" documented create-table use case
    Then the documented registration-schema behavior is recorded without blocking the suite

  Scenario Outline: Apply a Lance creation mode to an existing table
    Given a configured "lance" create-table environment
    And an existing table created by the "managed_table" documented use case
    When I submit the "<use_case>" documented create-table use case
    Then the create operation succeeds
    And the documented provider side effect exists
    And the fixture cleans up the created table

    Examples:
      | use_case |
      | creation_mode_exist_ok |
      | creation_mode_overwrite |

  Scenario: Register an existing external Delta table
    Given a configured "delta" create-table environment
    And a unique table name
    When I submit the "register_existing_delta" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented metadata
    And the fixture cleans up the created table

  Scenario: Register an existing Iceberg table through AWS Glue
    Given a configured "glue" create-table environment
    And a unique table name
    And a pre-existing provider dataset for that table
    When I submit the "register_existing_iceberg" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented metadata
    And the fixture cleans up the created table

  @expected_error
  Scenario: A read-only Hudi catalog reports table creation as unsupported
    Given a configured "hudi" create-table environment
    And a unique table name
    When I submit the "minimal" documented create-table use case
    Then the documented expected API error is recorded without blocking the suite
