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

/** Describes a safe public V1 request-field validation failure. */
public final class V1FieldViolationErrorDetail implements V1ErrorDetail {

  /** The stable discriminator for field-violation details. */
  public static final String KIND = "FIELD_VIOLATION";

  private final String field;
  private final String description;

  /**
   * Creates a public field-violation error detail.
   *
   * @param field public request field path or header name.
   * @param description safe description of the violated rule.
   */
  @JsonCreator
  public V1FieldViolationErrorDetail(
      @JsonProperty("field") String field, @JsonProperty("description") String description) {
    this.field = requireNonEmpty(field, "field");
    this.description = requireNonEmpty(description, "description");
  }

  @Override
  @JsonProperty("kind")
  public String getKind() {
    return KIND;
  }

  /**
   * Returns the public request field or header name.
   *
   * @return the field name.
   */
  public String getField() {
    return field;
  }

  /**
   * Returns a safe explanation of the violated rule.
   *
   * @return the description.
   */
  public String getDescription() {
    return description;
  }

  private static String requireNonEmpty(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " cannot be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " cannot be empty");
    }
    return value;
  }
}
