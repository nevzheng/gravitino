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
package org.apache.gravitino.rest.v1.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Identifies the public resource involved in a V1 API error. */
public final class V1ResourceInfoErrorDetail implements V1ErrorDetail {

  /** The stable discriminator for resource-information details. */
  public static final String KIND = "RESOURCE_INFO";

  private final String resourceType;
  private final String resourceName;

  /**
   * Creates a public resource-information error detail.
   *
   * @param resourceType the public resource type.
   * @param resourceName the canonical resource name.
   */
  @JsonCreator
  public V1ResourceInfoErrorDetail(
      @JsonProperty("resourceType") String resourceType,
      @JsonProperty("resourceName") String resourceName) {
    this.resourceType = requireNonEmpty(resourceType, "resourceType");
    this.resourceName = requireNonEmpty(resourceName, "resourceName");
  }

  @Override
  @JsonProperty("kind")
  public String getKind() {
    return KIND;
  }

  /**
   * Returns the public resource type.
   *
   * @return the resource type.
   */
  public String getResourceType() {
    return resourceType;
  }

  /**
   * Returns the canonical resource name.
   *
   * @return the resource name.
   */
  public String getResourceName() {
    return resourceName;
  }

  private static String requireNonEmpty(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " cannot be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " cannot be empty");
    }
    return value;
  }
}
