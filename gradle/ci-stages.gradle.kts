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

// CI test stages for .github/workflows/build.yml. Each stage is one task, so a CI job and a
// local run use the same command:
//
//   ./gradlew ciSmallTest -PskipITs -PskipDockerTests=false
//   ./gradlew ciMediumTestJdbc -PskipITs -PskipDockerTests=false
//
// Until each test size has its own suite, a module's whole `test` task goes to one stage:
// modules whose `src/test` has Docker-tagged tests are medium, all others are small. A module
// with Docker-tagged tests that is missing below still runs in ciSmallTest with Docker on, so
// no test is dropped; it only runs in the wrong stage.

// Medium shards: name -> modules whose `test` task needs Docker.
val ciMediumShards: Map<String, List<String>> = mapOf(
  "hive" to listOf(":catalogs:hive-metastore-common"),
  "jdbc" to listOf(
    ":catalogs:catalog-jdbc-doris",
    ":catalogs:catalog-jdbc-mysql",
    ":catalogs:catalog-jdbc-postgresql",
    ":catalogs:catalog-jdbc-starrocks"
  ),
  "others" to listOf(
    ":catalogs:catalog-lakehouse-paimon",
    ":iceberg:iceberg-rest-server",
    ":maintenance:jobs",
    ":plugins:idp-basic"
  )
)

// Tests these stages do not run: core runs as lanes in the core-test job, the Python client in
// python-integration-test.yml, and the contrib catalogs below in contrib-catalog-test.yml.
val ciSkippedTestModules: Set<String> = setOf(
  ":core",
  ":clients:client-python",
  ":catalogs-contrib:catalog-jdbc-clickhouse",
  ":catalogs-contrib:catalog-jdbc-hologres",
  ":catalogs-contrib:catalog-jdbc-oceanbase"
)

val ciMediumTestModules: Set<String> = ciMediumShards.values.flatten().toSet()

fun ciMediumTaskName(shard: String): String = "ciMediumTest" + shard.replaceFirstChar { it.uppercase() }

val ciSmallTest = tasks.register("ciSmallTest") {
  group = "verification"
  description = "Runs the small CI stage: every module's test task except medium and skipped ones."
}

ciMediumShards.forEach { (shard, modules) ->
  tasks.register(ciMediumTaskName(shard)) {
    group = "verification"
    description = "Runs the '$shard' shard of the medium CI stage: ${modules.joinToString()}."
    dependsOn(modules.map { "$it:test" })
  }
}

subprojects {
  if (path !in ciMediumTestModules && path !in ciSkippedTestModules) {
    val moduleTests = tasks.matching { it.name == "test" }
    ciSmallTest.configure { dependsOn(moduleTests) }
  }
}

tasks.register("ciShards") {
  group = "help"
  description = "Writes the medium CI shards as JSON, to \$GITHUB_OUTPUT in GitHub Actions."
  val shardsJson = ciMediumShards.keys.joinToString(",", "[", "]") {
    "{\"shard\":\"$it\",\"task\":\"${ciMediumTaskName(it)}\"}"
  }
  val githubOutput = providers.environmentVariable("GITHUB_OUTPUT")
  doLast {
    logger.lifecycle("medium-shards=$shardsJson")
    if (githubOutput.isPresent) {
      File(githubOutput.get()).appendText("medium-shards=$shardsJson\n")
    }
  }
}
