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
package org.apache.gravitino.di;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * Qualifies the catalog manager for a downstream module that builds its own dispatcher chains.
 *
 * <p>The operation dispatchers at the bottom of the schema, table, fileset, topic, model and view
 * chains take the {@code CatalogManager} itself. Upstream builds them over the manager it binds
 * {@link Undecorated}, a qualifier only this package can name. A downstream distribution that
 * replaces a chain with its own operation dispatchers injects the same manager instance under this
 * qualifier. Any other code injects {@code CatalogAccess}, which has no method that changes a
 * catalog; no component method returns the manager under this qualifier.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface ForDispatcherChains {}
