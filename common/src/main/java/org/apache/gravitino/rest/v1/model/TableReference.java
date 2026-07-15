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
package org.apache.gravitino.rest.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A lightweight table reference returned by a public Gravitino V1 collection endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TableReference {

  @JsonProperty(value = "resourceName", required = true)
  private final String resourceName;

  @JsonProperty(value = "name", required = true)
  private final String name;

  /**
   * Creates a public V1 table reference.
   *
   * @param resourceName canonical table resource name.
   * @param name local table name.
   */
  @JsonCreator
  public TableReference(
      @JsonProperty(value = "resourceName", required = true) String resourceName,
      @JsonProperty(value = "name", required = true) String name) {
    this.resourceName = ModelSupport.requireNonEmpty(resourceName, "resourceName");
    this.name = ModelSupport.requireNonEmpty(name, "name");
  }

  /**
   * @return canonical table resource name.
   */
  public String getResourceName() {
    return resourceName;
  }

  /**
   * @return local table name.
   */
  public String getName() {
    return name;
  }
}
