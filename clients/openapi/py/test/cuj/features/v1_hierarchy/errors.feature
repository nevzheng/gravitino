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
Feature: Report V1 hierarchy conditional-write capability outcomes
  Missing and stale validators are normal contract errors and must be asserted.
  A capability gap is recorded without blocking only when the published V1
  contract explicitly documents that the underlying mutation does not exist.

  Scenario Outline: Reject a stale or missing parent full-replacement precondition
    Given a configured V1 parent hierarchy
    When I create the metalake, catalog, and schema through V1
    And I attempt a "<resource>" V1 full replacement with a "<precondition>" precondition
    Then the parent API returns the strict non-retryable PRECONDITION_FAILED error

    Examples:
      | resource | precondition |
      | metalake | stale |
      | catalog | missing |
      | schema | stale |

  @expected_error
  Scenario: Record the current schema comment replacement capability limit
    Given a configured V1 parent hierarchy
    When I create the metalake, catalog, and schema through V1
    And I attempt a V1 schema comment replacement
    Then the documented schema-comment capability error is recorded without blocking the suite
