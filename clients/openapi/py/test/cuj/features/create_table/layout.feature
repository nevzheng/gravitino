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

@cuj @api @create_table @layout
Feature: Create a table with documented physical layout
  Partitioning, sorting, distribution, and indexes are verified only for
  connectors whose documentation says the combination is supported.

  Scenario Outline: Create a table with documented partitioning and ordering
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "<use_case>" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented layout metadata
    And the fixture cleans up the created table

    Examples:
      | provider | use_case |
      | hive | identity_partition_hash_distribution_sort |
      | glue | hive_identity_partition_hash_distribution_sort |
      | glue | iceberg_partition_transforms |
      | iceberg | partition_sort_and_distribution |
      | paimon | identity_partition_and_primary_key |
      | doris | partition_and_required_distribution |
      | starrocks | partition_and_required_distribution |
      | clickhouse | engine_partition_sort_and_primary_key |
      | hologres | distribution_and_partitioning |

  Scenario Outline: Create a table with primary and unique indexes where documented
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "primary_and_unique_indexes" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented layout metadata
    And the fixture cleans up the created table

    Examples:
      | provider |
      | mysql |
      | postgresql |
      | oceanbase |

  Scenario Outline: Create a table with the connector's documented primary-key index form
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "primary_key_index" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented layout metadata
    And the fixture cleans up the created table

    Examples:
      | provider |
      | paimon |
      | doris |
      | hologres |

  Scenario: Create a ClickHouse MergeTree table with its documented key form
    Given a configured "clickhouse" create-table environment
    And a unique table name
    When I submit the "mergetree_order_and_primary_key" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented layout metadata
    And the fixture cleans up the created table

  Scenario: Create a ClickHouse table with a documented data-skipping index
    Given a configured "clickhouse" create-table environment
    And a unique table name
    When I submit the "data_skipping_index" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented layout metadata
    And the fixture cleans up the created table

  @expected_error
  Scenario Outline: Surface an unsupported layout as a non-blocking documented API error
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "unsupported_layout" documented create-table use case
    Then the documented expected API error is recorded without blocking the suite

    Examples:
      | provider |
      | lance |
      | delta |
      | starrocks |
