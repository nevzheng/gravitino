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
package org.apache.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.rest.RESTRequest;

/** Represents a merge-patch request that restores a deleted metadata entity. */
@JsonIgnoreProperties(ignoreUnknown = false)
@Getter
@EqualsAndHashCode
@ToString
public class EntityRestoreRequest implements RESTRequest {

  @JsonProperty("deleted")
  private final Boolean deleted;

  /** Default constructor for Jackson deserialization. */
  public EntityRestoreRequest() {
    this(null);
  }

  /**
   * Creates a request to transition an entity's deleted state.
   *
   * @param deleted The requested deleted state. Recoverable deletion accepts only {@code false}.
   */
  public EntityRestoreRequest(Boolean deleted) {
    this.deleted = deleted;
  }

  /**
   * Validates that the request represents the only supported transition, {@code deleted=false}.
   *
   * @throws IllegalArgumentException If {@code deleted} is absent, null, or true.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        Boolean.FALSE.equals(deleted), "\"deleted\" field is required and must be false");
  }
}
