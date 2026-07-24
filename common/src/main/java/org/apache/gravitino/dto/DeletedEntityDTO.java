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
package org.apache.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.time.Instant;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.RecoveryEntityType;

/** Represents one deletion generation of a metadata entity. */
@Getter
@Builder(setterPrefix = "with")
@Jacksonized
@EqualsAndHashCode
@ToString
public class DeletedEntityDTO implements DeletedEntity {

  @Builder.Default
  @JsonProperty("deleted")
  private final Boolean deleted = true;

  @JsonProperty("id")
  private final String id;

  @JsonProperty("deletionId")
  private final String deletionId;

  @JsonProperty("name")
  private final String name;

  @JsonProperty("type")
  private final RecoveryEntityType type;

  @JsonProperty("deletedAt")
  private final Long deletedAt;

  @JsonProperty("expiresAt")
  private final Long expiresAt;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("deletedBy")
  private final String deletedBy;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("version")
  private final Long version;

  @JsonProperty("etag")
  private final String etag;

  @JsonProperty("latestForName")
  private final Boolean latestForName;

  @JsonProperty("restorable")
  private final Boolean restorable;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("reason")
  private final String reason;

  /** {@inheritDoc} */
  @Override
  public boolean deleted() {
    return Boolean.TRUE.equals(deleted);
  }

  /** {@inheritDoc} */
  @Override
  public String id() {
    return id;
  }

  /** {@inheritDoc} */
  @Override
  public String deletionId() {
    return deletionId;
  }

  /** {@inheritDoc} */
  @Override
  public String name() {
    return name;
  }

  /** {@inheritDoc} */
  @Override
  public RecoveryEntityType type() {
    return type;
  }

  /** {@inheritDoc} */
  @Override
  public Instant deletedAt() {
    return Instant.ofEpochMilli(deletedAt);
  }

  /** {@inheritDoc} */
  @Override
  public Instant expiresAt() {
    return Instant.ofEpochMilli(expiresAt);
  }

  /** {@inheritDoc} */
  @Override
  @Nullable
  public String deletedBy() {
    return deletedBy;
  }

  /** {@inheritDoc} */
  @Override
  @Nullable
  public Long version() {
    return version;
  }

  /** {@inheritDoc} */
  @Override
  public String etag() {
    return etag;
  }

  /** {@inheritDoc} */
  @Override
  public boolean latestForName() {
    return Boolean.TRUE.equals(latestForName);
  }

  /** {@inheritDoc} */
  @Override
  public boolean restorable() {
    return Boolean.TRUE.equals(restorable);
  }

  /** {@inheritDoc} */
  @Override
  @Nullable
  public String reason() {
    return reason;
  }

  /**
   * Validates this deleted entity.
   *
   * @throws IllegalArgumentException If a required field is missing or the fields are inconsistent.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        Boolean.TRUE.equals(deleted), "\"deleted\" must be true for a deleted entity");
    Preconditions.checkArgument(StringUtils.isNotBlank(id), "\"id\" must not be null or empty");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(deletionId), "\"deletionId\" must not be null or empty");
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "\"name\" must not be null or empty");
    Preconditions.checkArgument(type != null, "\"type\" must not be null");
    Preconditions.checkArgument(deletedAt != null && deletedAt > 0, "\"deletedAt\" must be > 0");
    Preconditions.checkArgument(
        expiresAt != null && expiresAt > deletedAt,
        "\"expiresAt\" must be greater than \"deletedAt\"");
    Preconditions.checkArgument(
        deletedBy == null || StringUtils.isNotBlank(deletedBy), "\"deletedBy\" must not be empty");
    Preconditions.checkArgument(version == null || version > 0, "\"version\" must be > 0");
    Preconditions.checkArgument(StringUtils.isNotBlank(etag), "\"etag\" must not be null or empty");
    Preconditions.checkArgument(latestForName != null, "\"latestForName\" must not be null");
    Preconditions.checkArgument(restorable != null, "\"restorable\" must not be null");
    Preconditions.checkArgument(
        reason == null || StringUtils.isNotBlank(reason), "\"reason\" must not be empty");
    Preconditions.checkArgument(
        restorable || StringUtils.isNotBlank(reason),
        "\"reason\" must not be null or empty when the entity is not restorable");
  }
}
