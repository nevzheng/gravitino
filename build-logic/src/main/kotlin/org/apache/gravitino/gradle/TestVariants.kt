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

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers another `Test` task over the classes of [suite]: the same tests under another runtime
 * (for example against the built package) or another tag filter. The variant is never wired into
 * `check`; it runs by name.
 */
fun Project.registerVariant(
  suite: String,
  name: String,
  configure: Action<in Test> = Action {},
): TaskProvider<Test> {
  val sources = the<SourceSetContainer>().named(suite)
  return tasks.register<Test>(name) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the $suite suite as the $name variant."
    testClassesDirs = sources.get().output.classesDirs
    classpath = sources.get().runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(suite)
    configure.execute(this)
  }
}
