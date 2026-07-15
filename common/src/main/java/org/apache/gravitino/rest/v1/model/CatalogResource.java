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
import java.util.Objects;
import javax.annotation.Nullable;

/** A catalog resource returned by the public Gravitino V1 API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CatalogResource {

  @JsonProperty(value = "resourceName", required = true)
  private final String resourceName;

  @JsonProperty(value = "name", required = true)
  private final String name;

  @JsonProperty(value = "type", required = true)
  private final CatalogType type;

  @JsonProperty(value = "provider", required = true)
  private final String provider;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  @Nullable
  @JsonProperty("audit")
  private final Audit audit;

  /**
   * Creates a public V1 catalog resource.
   *
   * @param resourceName canonical catalog resource name.
   * @param name local catalog name.
   * @param type catalog type.
   * @param provider effective catalog provider.
   * @param comment optional catalog comment.
   * @param properties public catalog properties.
   * @param audit optional, potentially partial audit record.
   */
  @JsonCreator
  public CatalogResource(
      @JsonProperty(value = "resourceName", required = true) String resourceName,
      @JsonProperty(value = "name", required = true) String name,
      @JsonProperty(value = "type", required = true) CatalogType type,
      @JsonProperty(value = "provider", required = true) String provider,
      @Nullable @JsonProperty("comment") String comment,
      @JsonProperty(value = "properties", required = true) Map<String, String> properties,
      @Nullable @JsonProperty("audit") Audit audit) {
    this.resourceName = ModelSupport.requireNonEmpty(resourceName, "resourceName");
    this.name = ModelSupport.requireNonEmpty(name, "name");
    this.type = Objects.requireNonNull(type, "type cannot be null");
    this.provider = ModelSupport.requireNonEmpty(provider, "provider");
    this.comment = comment;
    this.properties = ModelSupport.immutableMap(properties, "properties");
    this.audit = audit;
  }

  /**
   * @return canonical catalog resource name.
   */
  public String getResourceName() {
    return resourceName;
  }

  /**
   * @return local catalog name.
   */
  public String getName() {
    return name;
  }

  /**
   * @return catalog type.
   */
  public CatalogType getType() {
    return type;
  }

  /**
   * @return effective catalog provider.
   */
  public String getProvider() {
    return provider;
  }

  /**
   * @return optional catalog comment.
   */
  @Nullable
  public String getComment() {
    return comment;
  }

  /**
   * @return immutable public catalog properties.
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
