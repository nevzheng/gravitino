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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A typed, public detail attached to a V1 API error. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = V1FieldViolationErrorDetail.class, name = "FIELD_VIOLATION"),
  @JsonSubTypes.Type(value = V1ResourceInfoErrorDetail.class, name = "RESOURCE_INFO")
})
public interface V1ErrorDetail {

  /**
   * Returns the stable discriminator for this error detail.
   *
   * @return the public error-detail type.
   */
  String getKind();
}
