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

@cuj @api @create_table @common
Feature: Create a basic table through the public API
  A caller can create a table in a configured writable schema and reliably
  discover its durable metadata afterwards.

  Scenario Outline: Create a minimal documented table
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "minimal" documented create-table use case
    Then the create operation succeeds
    And the created table can be loaded through the public API
    And the created table is listed in its schema
    And the fixture cleans up the created table

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

  Scenario Outline: Preserve common table metadata
    Given a configured "<provider>" create-table environment
    And a unique table name
    When I submit the "comment_and_properties" documented create-table use case
    Then the create operation succeeds
    And the loaded table preserves the documented metadata
    And the fixture cleans up the created table

    Examples:
      | provider |
      | lance |
      | iceberg |
      | hive |
      | glue |
      | paimon |
      | mysql |
      | doris |
      | starrocks |
      | clickhouse |
      | hologres |
