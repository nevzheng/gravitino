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
package org.apache.gravitino.gradle

import javax.inject.Inject
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * True when `docker info` succeeds. A value source, so the configuration cache re-checks it on
 * every build instead of storing a stale answer, and nothing runs `docker` unless a task asks.
 */
abstract class DockerAvailable @Inject constructor(private val exec: ExecOperations) :
  ValueSource<Boolean, ValueSourceParameters.None> {

  override fun obtain(): Boolean =
    try {
      exec
        .exec {
          commandLine("docker", "info")
          isIgnoreExitValue = true
          standardOutput = java.io.OutputStream.nullOutputStream()
          errorOutput = java.io.OutputStream.nullOutputStream()
        }
        .exitValue == 0
    } catch (e: Exception) {
      false // no docker binary on PATH
    }
}
