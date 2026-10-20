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
package org.apache.gravitino.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a pre-dependency-injection call site of {@code GravitinoEnv.getInstance()} that still has
 * to be migrated to constructor injection.
 *
 * <p>{@code GravitinoEnv.getInstance()} is restricted with Error Prone's {@code RestrictedApi}
 * check. Production code may call it only from a type, method or constructor that carries this
 * annotation, so every remaining service-locator lookup is visible and counted by the
 * dependency-injection ratchet in {@code dev/di}. Do not add this annotation to new code: take the
 * dependency as a constructor parameter instead. Remove the annotation when the call site is
 * migrated. The annotation is deleted together with {@code GravitinoEnv.getInstance()} at the end
 * of the migration.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface LegacyEnvAccess {}
