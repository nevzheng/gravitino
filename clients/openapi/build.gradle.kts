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

import io.github.piyushroshan.python.VenvTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

buildscript {
  configurations.classpath {
    // Gradle 8.2 cannot instrument jackson-core's Java 21 multi-release
    // classes. Keep the generator at the repository-pinned version while
    // resolving its buildscript-only Jackson dependencies compatibly.
    resolutionStrategy.eachDependency {
      if (requested.group?.startsWith("com.fasterxml.jackson") == true) {
        useVersion(libs.versions.jackson.get())
        because("Keep OpenAPI tooling compatible with the Gradle 8.2 runtime")
      }
    }
  }
  dependencies {
    classpath("org.openapitools:openapi-generator-gradle-plugin:${libs.versions.openapi.generator.get()}")
  }
}

plugins {
  id("io.github.piyushroshan.python-gradle-miniforge-plugin") version "1.0.0"
}

apply(plugin = "org.openapi.generator")

pythonPlugin {
  pythonVersion.set(project.rootProject.extra["pythonVersion"].toString())
}

val pythonPackageName = "gravitino_client"
val pythonPackageVersion = "1.0.0.dev0"
val v1BundledSpec = rootProject.layout.projectDirectory.file("dev/openapi/build/v1/openapi.json")
val generatedPythonClient = layout.buildDirectory.dir("generated/openapi/v1/python")
val checkedInPythonRoot = layout.projectDirectory.dir("py")
val checkedInPythonPackage = checkedInPythonRoot.dir(pythonPackageName)
val externallyManagedBaseUrl =
  providers
    .gradleProperty("gravitinoOpenapiBaseUrl")
    .orElse(providers.environmentVariable("GRAVITINO_OPENAPI_BASE_URL"))
val pythonTestServerBaseUrl = externallyManagedBaseUrl.getOrElse("http://localhost:8090")
val managesPythonTestServer = AtomicBoolean(false)
val apacheLicenseHeader =
  """
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
  """
    .trimIndent()

fun waitForServerReady(host: String = "http://localhost", port: Int = 8090, timeoutMs: Long = 60000) {
  val startTime = System.currentTimeMillis()
  val healthUrl = "$host:$port/api/version"
  var lastFailure: Exception? = null

  while (System.currentTimeMillis() - startTime < timeoutMs) {
    try {
      val connection = URL(healthUrl).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 1000
      connection.readTimeout = 1000
      try {
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
          return
        }
        lastFailure = GradleException("Received HTTP ${connection.responseCode} from $healthUrl")
      } finally {
        connection.disconnect()
      }
    } catch (exception: Exception) {
      lastFailure = exception
    }
    Thread.sleep(500)
  }

  throw GradleException("Timed out waiting for Gravitino at $healthUrl", lastFailure)
}

fun serverResponds(host: String = "http://localhost", port: Int = 8090): Boolean {
  return try {
    val connection = URL("$host:$port/api/version").openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 1000
    connection.readTimeout = 1000
    try {
      connection.responseCode == HttpURLConnection.HTTP_OK
    } finally {
      connection.disconnect()
    }
  } catch (_: Exception) {
    false
  }
}

fun runGravitinoServer(operation: String) {
  val script = rootProject.layout.projectDirectory.file("distribution/package/bin/gravitino.sh").asFile
  if (!script.isFile) {
    throw GradleException(
      "Missing ${script.absolutePath}. Run :compileDistribution before executing OpenAPI Python tests."
    )
  }

  val process = ProcessBuilder(script.absolutePath, operation).inheritIO().start()
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    throw GradleException("gravitino.sh $operation failed with exit code $exitCode")
  }
}

/**
 * Corrects OpenAPI Generator's Pydantic output for boolean JSON Schema constants.
 *
 * The pinned generator writes a boolean constant such as {@code false} as the string literal
 * {@code 'false'} in a {@code StrictBool} enum validator. The generated model consequently
 * rejects a conforming JSON error body. Keep the contract strict and apply this narrow,
 * deterministic workaround until the generator emits Python boolean literals itself.
 */
fun patchPythonBooleanConstantValidators(directory: File) {
  var patchedFiles = 0

  directory
    .walkTopDown()
    .filter { it.isFile && it.extension == "py" }
    .forEach { file ->
      val source = file.readText()
      if (!source.contains("StrictBool")) {
        return@forEach
      }

      val patched =
        source
          .replace("set(['true'])", "set([True])")
          .replace("set(['false'])", "set([False])")
      if (patched != source) {
        file.writeText(patched)
        patchedFiles++
      }
    }

  if (patchedFiles > 0) {
    project.logger.lifecycle(
      "Patched boolean constant validators in $patchedFiles generated Python model file(s)."
    )
  }
}

/** Adds the ASF header to generated Python templates that do not render the shared header. */
fun addApacheHeadersToGeneratedPython(directory: File) {
  var patchedFiles = 0

  directory
    .walkTopDown()
    .filter { it.isFile && it.extension == "py" }
    .forEach { file ->
      val source = file.readText()
      if (!source.startsWith("# Licensed to the Apache Software Foundation")) {
        file.writeText("$apacheLicenseHeader\n\n$source")
        patchedFiles++
      }
    }

  if (patchedFiles > 0) {
    project.logger.lifecycle("Added ASF headers to $patchedFiles generated Python source file(s).")
  }
}

/** Removes generator whitespace noise so checked-in sources pass the repository diff checks. */
fun normalizeGeneratedPythonWhitespace(directory: File) {
  var patchedFiles = 0

  directory
    .walkTopDown()
    .filter { it.isFile && it.extension == "py" }
    .forEach { file ->
      val source = file.readText()
      val normalizedLines = source.lineSequence().map { it.trimEnd() }.toList()
      val normalized =
        normalizedLines
          .dropLastWhile { it.isEmpty() }
          .joinToString("\n", postfix = "\n")
      if (normalized != source) {
        file.writeText(normalized)
        patchedFiles++
      }
    }

  if (patchedFiles > 0) {
    project.logger.lifecycle("Normalized whitespace in $patchedFiles generated Python source file(s).")
  }
}

val generatePythonClient by tasks.registering(GenerateTask::class) {
  group = "openapi"
  description = "Generates the Python V1 client from the bundled OpenAPI contract."
  dependsOn(":docs:bundleOpenApiV1")

  generatorName.set("python")
  inputSpec.set(v1BundledSpec)
  outputDir.set(generatedPythonClient)
  cleanupOutput.set(true)
  workerIsolation.set("process")
  validateSpec.set(true)
  generateApiTests.set(false)
  generateModelTests.set(false)
  generateApiDocumentation.set(false)
  generateModelDocumentation.set(false)
  configOptions.set(
    mapOf(
      "library" to "urllib3",
      "packageName" to pythonPackageName,
      "projectName" to "apache-gravitino-openapi",
      "packageVersion" to pythonPackageVersion,
      "disallowAdditionalPropertiesIfNotPresent" to "false",
      "hideGenerationTimestamp" to "true"
    )
  )

  doLast {
    val generatedPackage = generatedPythonClient.get().dir(pythonPackageName).asFile
    addApacheHeadersToGeneratedPython(generatedPackage)
    normalizeGeneratedPythonWhitespace(generatedPackage)
    patchPythonBooleanConstantValidators(generatedPackage)
  }
}

tasks.register<Sync>("regeneratePythonClient") {
  group = "openapi"
  description = "Updates the checked-in Python client from the V1 OpenAPI contract."
  dependsOn(generatePythonClient)
  from(generatedPythonClient.map { it.dir(pythonPackageName) })
  exclude("**/__pycache__/**", "**/*.pyc")
  into(checkedInPythonPackage)
}

val verifyGeneratedPythonClient by tasks.registering {
  group = "verification"
  description = "Fails when the checked-in Python client differs from the V1 OpenAPI contract."
  dependsOn(generatePythonClient)
  inputs.dir(generatedPythonClient)
  // Do not declare checkedInPythonPackage as an input: regeneratePythonClient owns that output,
  // and declaring it makes Gradle reject an explicit regenerate+verify invocation.
  outputs.upToDateWhen { false }

  doLast {
    val generated = generatedPythonClient.get().dir(pythonPackageName).asFile
    val checkedIn = checkedInPythonPackage.asFile
    if (!checkedIn.isDirectory) {
      throw GradleException(
        "Missing checked-in Python client at $checkedIn. "
            + "Run :clients:openapi:regeneratePythonClient."
      )
    }

    val exitCode =
      ProcessBuilder(
          "diff",
          "-ruN",
          "-x",
          "__pycache__",
          "-x",
          "*.pyc",
          generated.absolutePath,
          checkedIn.absolutePath
        )
        .inheritIO()
        .start()
        .waitFor()
    if (exitCode != 0) {
      throw GradleException(
        "The checked-in Python client is stale. "
            + "Run :clients:openapi:regeneratePythonClient and commit the result."
      )
    }
  }
}

val installPythonClient by tasks.registering(VenvTask::class) {
  group = "verification"
  description = "Installs the checked-in OpenAPI Python client into the managed test environment."
  dependsOn(verifyGeneratedPythonClient)
  venvExec = "pip"
  args = listOf("install", "--editable", "${checkedInPythonRoot.asFile.absolutePath}[dev]")
}

val stopPythonTestServer by tasks.registering {
  group = "verification"
  description = "Stops the local Gravitino server used by OpenAPI Python endpoint tests."
  doLast {
    if (!managesPythonTestServer.get()) {
      return@doLast
    }
    try {
      runGravitinoServer("stop")
    } catch (exception: GradleException) {
      // Preserve the original test or startup failure when no local server was started.
      project.logger.warn("Unable to stop the OpenAPI Python test server: ${exception.message}")
    }
  }
}

val pytestEndpointUnit by tasks.registering(VenvTask::class) {
  group = "verification"
  description = "Runs no-server pytest checks for generated OpenAPI endpoint clients."
  dependsOn(installPythonClient)
  workingDir = checkedInPythonRoot.asFile
  venvExec = "pytest"
  args = listOf("test/endpoint/unit")
}

val pytestEndpointContract by tasks.registering(VenvTask::class) {
  group = "verification"
  description = "Runs real-server pytest endpoint contract checks for generated OpenAPI clients."
  dependsOn(installPythonClient)
  workingDir = checkedInPythonRoot.asFile
  venvExec = "pytest"
  args = listOf("test/endpoint/contract")
  environment = mapOf("GRAVITINO_OPENAPI_BASE_URL" to pythonTestServerBaseUrl)
  doFirst {
    if (externallyManagedBaseUrl.isPresent) {
      project.logger.lifecycle("Using externally managed Gravitino at $pythonTestServerBaseUrl")
      return@doFirst
    }
    if (serverResponds()) {
      throw GradleException(
        "A Gravitino server is already responding at $pythonTestServerBaseUrl. "
            + "Refusing to manage it; set -PgravitinoOpenapiBaseUrl=$pythonTestServerBaseUrl "
            + "to run against that external server."
      )
    }
    runGravitinoServer("start")
    managesPythonTestServer.set(true)
    waitForServerReady()
  }
  finalizedBy(stopPythonTestServer)
}

val pytestEndpoint by tasks.registering {
  group = "verification"
  description = "Runs generated OpenAPI endpoint unit and real-server contract tests."
  dependsOn(pytestEndpointUnit, pytestEndpointContract)
}

val pythonDistribution by tasks.registering(VenvTask::class) {
  group = "build"
  description = "Builds source and wheel distributions for the OpenAPI Python client."
  dependsOn(installPythonClient)
  venvExec = "python"
  args =
    listOf(
      "-m",
      "build",
      "--sdist",
      "--wheel",
      "--outdir",
      layout.buildDirectory.dir("dist").get().asFile.absolutePath,
      checkedInPythonRoot.asFile.absolutePath
    )
}

tasks.register("test") {
  group = "verification"
  description = "Verifies generation and runs no-server OpenAPI Python endpoint unit tests."
  dependsOn(pytestEndpointUnit)
}

tasks.register("build") {
  group = "build"
  description = "Builds the OpenAPI Python client distribution after no-server verification."
  dependsOn(pytestEndpointUnit, pythonDistribution)
}

tasks.register<Delete>("clean") {
  delete(layout.buildDirectory)
}
