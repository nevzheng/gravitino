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
package org.apache.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.DeletedEntityDTO;

/** Represents a response containing deleted metadata entities. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class DeletedEntityListResponse extends BaseResponse {

  @JsonProperty("deletedEntities")
  private final DeletedEntityDTO[] deletedEntities;

  /**
   * Creates a deleted entity list response.
   *
   * @param deletedEntities The deleted metadata entities in the response.
   */
  public DeletedEntityListResponse(DeletedEntityDTO[] deletedEntities) {
    super(0);
    this.deletedEntities = deletedEntities;
  }

  /** Default constructor for Jackson deserialization. */
  public DeletedEntityListResponse() {
    super();
    this.deletedEntities = null;
  }

  /**
   * Validates the response and every contained deleted entity.
   *
   * @throws IllegalArgumentException If the response contains invalid data.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(deletedEntities != null, "\"deletedEntities\" must not be null");
    Arrays.stream(deletedEntities)
        .forEach(
            deletedEntity -> {
              Preconditions.checkArgument(deletedEntity != null, "deleted entity must not be null");
              deletedEntity.validate();
            });
  }
}
