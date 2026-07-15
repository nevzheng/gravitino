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

/** The full desired mutable state of a schema for a public Gravitino V1 PUT request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class SchemaUpdateRequest {

  @Nullable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @JsonProperty(value = "comment", required = true)
  private final String comment;

  @JsonProperty(value = "properties", required = true)
  private final Map<String, String> properties;

  /**
   * Creates a public V1 schema desired-state update.
   *
   * @param comment replacement comment, or null to clear it.
   * @param properties complete desired schema properties, potentially empty.
   */
  @JsonCreator
  public SchemaUpdateRequest(
      @Nullable @JsonProperty(value = "comment", required = true) String comment,
      @JsonProperty(value = "properties", required = true) Map<String, String> properties) {
    this.comment = ModelSupport.requireNullableComment(comment, "comment");
    this.properties = ModelSupport.immutableRequestProperties(properties, "properties", true);
  }

  /**
   * @return replacement comment, or null when the comment should be cleared.
   */
  @Nullable
  public String getComment() {
    return comment;
  }

  /**
   * @return immutable complete desired schema properties.
   */
  public Map<String, String> getProperties() {
    return properties;
  }
}
