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

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.Sync
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

buildscript {
  configurations.classpath {
    // Gradle 8.2 cannot instrument jackson-core's Java 21 multi-release
    // classes. Keep the plugin/core at 7.23.0 while aligning its Jackson
    // buildscript dependencies with the repository's Java 17-compatible set.
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
  alias(libs.plugins.node)
}

apply(plugin = "org.openapi.generator")

val openApiToolingDirectory = rootProject.layout.projectDirectory.dir("dev/openapi")
val legacyOpenApiDirectory = layout.projectDirectory.dir("open-api")
val v1OpenApiDirectory = legacyOpenApiDirectory.dir("v1")
val v1SourceSpec = v1OpenApiDirectory.file("openapi.yaml")

configure<NodeExtension> {
  version.set("22.12.0")
  npmVersion.set("10.9.0")
  download.set(true)
  nodeProjectDir.set(openApiToolingDirectory)
  npmInstallCommand.set("ci")
}

fun GenerateTask.configureClientGeneration(
  generator: String,
  input: RegularFile,
  output: Provider<Directory>
) {
  generatorName.set(generator)
  inputSpec.set(input)
  outputDir.set(output)
  cleanupOutput.set(true)
  workerIsolation.set("process")
  validateSpec.set(true)
  generateApiTests.set(false)
  generateModelTests.set(false)
  generateApiDocumentation.set(false)
  generateModelDocumentation.set(false)
  version.set("0.0.0")
}

// The legacy API gate remains expose-first and non-blocking in CI. These tasks
// replace the former unpinned npx invocation with the repository-pinned Gradle
// plugin while preserving that policy.
val legacyBundledSpec = openApiToolingDirectory.file("build/openapi.json")
val bundleOpenApiLegacy by tasks.registering(NpmTask::class) {
  group = "verification"
  description = "Bundles the legacy OpenAPI contract with the pinned npm toolchain."
  dependsOn(tasks.named("npmInstall"))
  args.set(listOf("run", "bundle"))
  inputs.files(
    fileTree(legacyOpenApiDirectory) {
      exclude("v1/**")
      include("**/*.json", "**/*.yaml", "**/*.yml")
    }
  )
  inputs.files(
    openApiToolingDirectory.file("package-lock.json"),
    openApiToolingDirectory.file("package.json"),
    openApiToolingDirectory.file("redocly.yaml")
  )
  outputs.file(legacyBundledSpec)
  outputs.file(openApiToolingDirectory.file("build/openapi.yaml"))
}

val generateOpenApiLegacyRust by tasks.registering(GenerateTask::class) {
  group = "verification"
  description = "Generates a disposable Rust client from the legacy contract."
  dependsOn(bundleOpenApiLegacy)
  configureClientGeneration(
    "rust",
    legacyBundledSpec,
    layout.buildDirectory.dir("generated/openapi/legacy/rust")
  )
  packageName.set("gravitino_openapi_legacy")
}

val generateOpenApiLegacyTypeScript by tasks.registering(GenerateTask::class) {
  group = "verification"
  description = "Generates a disposable TypeScript client from the legacy contract."
  dependsOn(bundleOpenApiLegacy)
  configureClientGeneration(
    "typescript-axios",
    legacyBundledSpec,
    layout.buildDirectory.dir("generated/openapi/legacy/typescript")
  )
  configOptions.set(
    mapOf(
      "npmName" to "@apache-gravitino/openapi-legacy-smoke",
      "npmVersion" to "0.0.0",
      "supportsES6" to "true"
    )
  )
}

tasks.register("openApiLegacyCodegen") {
  group = "verification"
  description = "Generates disposable Rust and TypeScript clients from the legacy contract."
  dependsOn(generateOpenApiLegacyRust, generateOpenApiLegacyTypeScript)
}

// V1 has no legacy debt: linting, bundling, validation, generation, and client
// compilation are all blocking as soon as its root specification exists.
if (v1SourceSpec.asFile.exists()) {
  val v1SpecFiles =
    fileTree(v1OpenApiDirectory) {
      include("**/*.json", "**/*.yaml", "**/*.yml")
    }
  val v1BundledSpec = openApiToolingDirectory.file("build/v1/openapi.json")
  val v1RustOutput = layout.buildDirectory.dir("generated/openapi/v1/rust")
  val v1TypeScriptOutput = layout.buildDirectory.dir("generated/openapi/v1/typescript")
  val v1RustCheckDirectory = layout.buildDirectory.dir("openapi-check/v1/rust")

  val lintOpenApiV1WithRedocly by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Lints the V1 source contract with blocking Redocly rules."
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "lint:v1:redocly"))
    inputs.files(v1SpecFiles)
    inputs.files(
      openApiToolingDirectory.file("package-lock.json"),
      openApiToolingDirectory.file("package.json"),
      openApiToolingDirectory.file("redocly.yaml")
    )
  }

  val bundleOpenApiV1 by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Bundles the V1 contract into reproducible JSON and YAML artifacts."
    dependsOn(lintOpenApiV1WithRedocly)
    args.set(listOf("run", "bundle:v1"))
    inputs.files(v1SpecFiles)
    inputs.files(
      openApiToolingDirectory.file("package-lock.json"),
      openApiToolingDirectory.file("package.json"),
      openApiToolingDirectory.file("redocly.yaml")
    )
    outputs.file(v1BundledSpec)
    outputs.file(openApiToolingDirectory.file("build/v1/openapi.yaml"))
  }

  val lintOpenApiV1 by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Lints the bundled V1 contract with blocking Spectral rules."
    dependsOn(bundleOpenApiV1)
    args.set(listOf("run", "lint:v1:spectral"))
    inputs.file(v1BundledSpec)
    inputs.files(
      openApiToolingDirectory.file(".spectral-v1.yaml"),
      openApiToolingDirectory.file(".spectral.yaml"),
      openApiToolingDirectory.file("package-lock.json"),
      openApiToolingDirectory.file("package.json")
    )
  }

  val testOpenApiV1Rules by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Regression-tests the null-safe V1 Spectral rules."
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "test:rules:v1"))
    inputs.files(
      openApiToolingDirectory.file(".spectral-v1.yaml"),
      openApiToolingDirectory.file(".spectral.yaml"),
      openApiToolingDirectory.dir("fixtures"),
      openApiToolingDirectory.file("package-lock.json"),
      openApiToolingDirectory.file("package.json"),
      openApiToolingDirectory.file("test-v1-rules.sh")
    )
  }

  val validateOpenApiV1 by tasks.registering(ValidateTask::class) {
    group = "verification"
    description = "Validates the bundled V1 contract with OpenAPI Generator 7.23.0."
    dependsOn(bundleOpenApiV1)
    inputSpec.set(v1BundledSpec)
    recommend.set(true)
    treatWarningsAsErrors.set(true)
  }

  val generateOpenApiV1Rust by tasks.registering(GenerateTask::class) {
    group = "verification"
    description = "Generates a disposable Rust client from the bundled V1 contract."
    dependsOn(validateOpenApiV1)
    configureClientGeneration("rust", v1BundledSpec, v1RustOutput)
    packageName.set("gravitino_openapi_v1")
  }

  val generateOpenApiV1TypeScript by tasks.registering(GenerateTask::class) {
    group = "verification"
    description = "Generates a disposable TypeScript client from the bundled V1 contract."
    dependsOn(validateOpenApiV1)
    configureClientGeneration("typescript-axios", v1BundledSpec, v1TypeScriptOutput)
    configOptions.set(
      mapOf(
        "axiosVersion" to "1.18.1",
        "npmName" to "@apache-gravitino/openapi-v1-smoke",
        "npmVersion" to "0.0.0",
        "supportsES6" to "true"
      )
    )
  }

  tasks.register("openApiV1Codegen") {
    group = "verification"
    description = "Generates disposable Rust and TypeScript clients from the V1 contract."
    dependsOn(generateOpenApiV1Rust, generateOpenApiV1TypeScript)
  }

  val prepareOpenApiV1RustCheck by tasks.registering(Sync::class) {
    dependsOn(generateOpenApiV1Rust)
    from(v1RustOutput)
    into(v1RustCheckDirectory)
  }

  val lockOpenApiV1RustClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Resolves the generated Rust client's dependencies into a lockfile."
    dependsOn(prepareOpenApiV1RustCheck)
    workingDir(v1RustCheckDirectory)
    commandLine("cargo", "generate-lockfile")
    inputs.file(v1RustCheckDirectory.map { it.file("Cargo.toml") })
    outputs.file(v1RustCheckDirectory.map { it.file("Cargo.lock") })
  }

  val checkOpenApiV1RustClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the generated Rust client against its resolved lockfile."
    dependsOn(lockOpenApiV1RustClient)
    workingDir(v1RustCheckDirectory)
    commandLine("cargo", "check", "--locked", "--all-targets")
    inputs.files(
      fileTree(v1RustCheckDirectory) {
        exclude("target/**")
      }
    )
    outputs.dir(v1RustCheckDirectory.map { it.dir("target") })
  }

  val checkOpenApiV1TypeScriptClient by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Type-checks the generated TypeScript client with pinned dependencies."
    dependsOn(tasks.named("npmInstall"), generateOpenApiV1TypeScript)
    args.set(listOf("run", "typecheck:v1"))
    inputs.dir(v1TypeScriptOutput)
    inputs.files(
      openApiToolingDirectory.file("package-lock.json"),
      openApiToolingDirectory.file("package.json"),
      openApiToolingDirectory.file("tsconfig.v1-codegen.json")
    )
  }

  val openApiV1ClientCheck by tasks.registering {
    group = "verification"
    description = "Generates and compiles disposable Rust and TypeScript V1 clients."
    dependsOn(checkOpenApiV1RustClient, checkOpenApiV1TypeScriptClient)
  }

  val openApiV1Check by tasks.registering {
    group = "verification"
    description = "Runs every blocking V1 OpenAPI contract check."
    dependsOn(lintOpenApiV1, testOpenApiV1Rules, validateOpenApiV1, openApiV1ClientCheck)
  }

  tasks.named("check") {
    dependsOn(openApiV1Check)
  }
  tasks.named("build") {
    dependsOn(openApiV1Check)
  }
}
