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
//
//   test                   src/test             hermetic; the presubmit gate
//   integrationTest        src/integrationTest  may start containers and a Gravitino server
//   deployIntegrationTest  src/integrationTest  the same classes against the built package
//
// None of them is part of `check`: every test task is run by name.

import org.apache.gravitino.gradle.DockerAvailable
import org.apache.gravitino.gradle.registerVariant

plugins {
  java
  `jvm-test-suite`
}

val junitVersion =
  the<VersionCatalogsExtension>().named("libs").findVersion("junit").get().requiredVersion

testing {
  suites {
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter(junitVersion)
      dependencies {
        implementation(project())
      }
    }
  }
}

// Moved tests keep their dependency lines as testImplementation for now (SPIP Q2, item 3).
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// `check` runs only static checks (Spotless, license headers, lints). The Java plugin wires
// `check` to the `test` suite, so drop that link here.
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

registerVariant(suite = "integrationTest", name = "deployIntegrationTest") {
  dependsOn(":compileDistribution")
  // Read by the root build's Test setup in place of -PtestMode.
  extensions.extraProperties["gravitinoTestMode"] = "deploy"
}

// Transition shim (SPIP S3 to S7): while any of the root build's flags is on the command line, the
// flags keep selecting the same tests as before the moves. `test` pulls in `integrationTest`, and
// the root build's Test setup applies -PskipTests, -PskipDockerTests and -PtestMode to it as it
// does to `test`. Without those flags, the suites behave as the SPIP says.
val legacyTestFlags =
  setOf("skipTests", "skipITs", "skipDockerTests", "testMode")
    .any { it in gradle.startParameter.projectProperties }

if (legacyTestFlags) {
  tasks.named("test") { dependsOn("integrationTest") }
}

val requireDocker =
  tasks.register("requireDocker") {
    group = "verification"
    description = "Fails unless `docker info` succeeds."
    val dockerAvailable = providers.of(DockerAvailable::class.java) {}
    doLast {
      if (!dockerAvailable.get()) {
        throw GradleException(
          "Docker is not available (`docker info` failed). Integration tests need Docker; " +
            "start it and run the task again."
        )
      }
    }
  }

val sharedTestEnvironmentLock =
  gradle.sharedServices.registrations.findByName("sharedTestEnvironmentLock")?.service

tasks.withType<Test>()
  .matching { it.name == "integrationTest" || it.name == "deployIntegrationTest" }
  .configureEach {
    // An embedded Gravitino server loads the catalog from the module jar.
    dependsOn(tasks.named("jar"))
    // Integration tests share containers and server state across modules: one at a time.
    sharedTestEnvironmentLock?.let { usesService(it) }
    if (legacyTestFlags) {
      if ("skipITs" in gradle.startParameter.projectProperties) {
        exclude("**/integration/test/**")
      }
    } else {
      dependsOn(requireDocker)
      // Read by the root build's Test setup: run Docker-tagged tests without -PskipDockerTests.
      extensions.extraProperties["includeDockerTaggedTests"] = true
    }
  }
