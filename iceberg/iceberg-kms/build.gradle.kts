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

import org.gradle.api.attributes.java.TargetJvmVersion

description = "iceberg-kms"

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  compileOnly(libs.iceberg.core)
  compileOnly(libs.jackson.databind)
  compileOnly(libs.guava)
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  testImplementation(libs.iceberg.core)
  testImplementation(libs.jackson.databind)
  testImplementation(libs.guava)
  testImplementation(libs.hadoop3.client.api)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

// Iceberg 1.11 publishes Java 17 dependency metadata. Resolve it with JDK 17 while the root build
// emits Java 8 bytecode; the adapter itself uses only Java 8 APIs and remains compatible with the
// Spark connector's published variants.
configurations.named("compileClasspath") {
  attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}
