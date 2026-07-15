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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import javax.annotation.Nullable;

/** A schema resource returned by the public Gravitino V1 API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SchemaResource {

  @JsonProperty(value = "resourceName", required = true)
  private final String resourceName;

  @JsonProperty(value = "name", required = true)
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  @Nullable
  @JsonProperty("audit")
  private final Audit audit;

  /**
   * Creates a public V1 schema resource.
   *
   * @param resourceName canonical schema resource name.
   * @param name local schema name.
   * @param comment optional schema comment.
   * @param properties public schema properties.
   * @param audit optional, potentially partial audit record.
   */
  @JsonCreator
  public SchemaResource(
      @JsonProperty(value = "resourceName", required = true) String resourceName,
      @JsonProperty(value = "name", required = true) String name,
      @Nullable @JsonProperty("comment") String comment,
      @JsonProperty(value = "properties", required = true) Map<String, String> properties,
      @Nullable @JsonProperty("audit") Audit audit) {
    this.resourceName = ModelSupport.requireNonEmpty(resourceName, "resourceName");
    this.name = ModelSupport.requireNonEmpty(name, "name");
    this.comment = comment;
    this.properties = ModelSupport.immutableMap(properties, "properties");
    this.audit = audit;
  }

  /**
   * @return canonical schema resource name.
   */
  public String getResourceName() {
    return resourceName;
  }

  /**
   * @return local schema name.
   */
  public String getName() {
    return name;
  }

  /**
   * @return optional schema comment.
   */
  @Nullable
  public String getComment() {
    return comment;
  }

  /**
   * @return immutable public schema properties.
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  /**
   * @return optional, potentially partial audit record.
   */
  @Nullable
  public Audit getAudit() {
    return audit;
  }
}
