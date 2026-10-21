/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Test suites, defined once for every module that applies this plugin.
// SPIP: design-docs/testing/organization/SPIP-test-organization.md (apache/gravitino#14093).

plugins {
  java
}

// `check` runs only static checks (Spotless, license headers, lints). Every test task, `test`
// included, is run by name. The Java plugin wires `check` to the `test` suite, so drop it here.
tasks.named("check") {
  setDependsOn(dependsOn.filterNot { it.refersToTestSuite() })
}

fun Any.refersToTestSuite(): Boolean =
  when (this) {
    is Task -> name == "test"
    is NamedDomainObjectProvider<*> -> name == "test" // the test suite or its task provider
    is String -> this == "test"
    else -> false
  }
