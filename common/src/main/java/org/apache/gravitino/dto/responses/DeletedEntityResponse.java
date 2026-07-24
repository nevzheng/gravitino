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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.gravitino.dto.DeletedEntityDTO;

/** Represents one exact soft-deletion generation. */
@Getter
@EqualsAndHashCode(callSuper = true)
public class DeletedEntityResponse extends BaseResponse {

  @JsonProperty("deletedEntity")
  private final DeletedEntityDTO deletedEntity;

  /** Default constructor for Jackson deserialization. */
  public DeletedEntityResponse() {
    super();
    this.deletedEntity = null;
  }

  /**
   * Creates an exact deleted-entity response.
   *
   * @param deletedEntity the selected deletion generation
   */
  public DeletedEntityResponse(DeletedEntityDTO deletedEntity) {
    super(0);
    this.deletedEntity = deletedEntity;
  }

  /** Validates this response and the selected deletion generation. */
  @Override
  public void validate() {
    super.validate();
    Preconditions.checkArgument(deletedEntity != null, "deletedEntity cannot be null");
    deletedEntity.validate();
  }
}
