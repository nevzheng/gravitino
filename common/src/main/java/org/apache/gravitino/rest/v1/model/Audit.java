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
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import javax.annotation.Nullable;

/** Partial audit information in the public Gravitino V1 wire contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Audit {

  @Nullable
  @JsonProperty("creator")
  private final String creator;

  @Nullable
  @JsonProperty("createTime")
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private final Instant createTime;

  @Nullable
  @JsonProperty("lastModifier")
  private final String lastModifier;

  @Nullable
  @JsonProperty("lastModifiedTime")
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private final Instant lastModifiedTime;

  /**
   * Creates audit information. Each field is independently optional because connectors may only
   * provide part of the audit record.
   *
   * @param creator optional creator identity.
   * @param createTime optional creation time.
   * @param lastModifier optional last modifier identity.
   * @param lastModifiedTime optional last modification time.
   */
  @JsonCreator
  public Audit(
      @Nullable @JsonProperty("creator") String creator,
      @Nullable @JsonProperty("createTime") Instant createTime,
      @Nullable @JsonProperty("lastModifier") String lastModifier,
      @Nullable @JsonProperty("lastModifiedTime") Instant lastModifiedTime) {
    this.creator = creator;
    this.createTime = createTime;
    this.lastModifier = lastModifier;
    this.lastModifiedTime = lastModifiedTime;
  }

  /**
   * @return optional creator identity.
   */
  @Nullable
  public String getCreator() {
    return creator;
  }

  /**
   * @return optional creation time.
   */
  @Nullable
  public Instant getCreateTime() {
    return createTime;
  }

  /**
   * @return optional last modifier identity.
   */
  @Nullable
  public String getLastModifier() {
    return lastModifier;
  }

  /**
   * @return optional last modification time.
   */
  @Nullable
  public Instant getLastModifiedTime() {
    return lastModifiedTime;
  }
}
