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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** The stable, machine-readable public error carried by every V1 error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
  "code",
  "type",
  "message",
  "retryable",
  "retryAfterSeconds",
  "requestId",
  "details"
})
public final class V1Error {

  private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");

  private final int code;
  private final String type;
  private final String message;
  private final boolean retryable;
  @Nullable private final Integer retryAfterSeconds;
  private final String requestId;
  private final List<V1ErrorDetail> details;

  /**
   * Creates a V1 public error.
   *
   * @param code the HTTP status code.
   * @param type the stable public error type.
   * @param message a safe, human-readable message.
   * @param retryable whether an unchanged, safe request may be retried automatically.
   * @param retryAfterSeconds an optional server-selected retry delay in seconds.
   * @param requestId the request correlation identifier.
   * @param details typed public details, empty when no detail is applicable.
   */
  @JsonCreator
  public V1Error(
      @JsonProperty("code") int code,
      @JsonProperty("type") String type,
      @JsonProperty("message") String message,
      @JsonProperty("retryable") boolean retryable,
      @JsonProperty("retryAfterSeconds") @Nullable Integer retryAfterSeconds,
      @JsonProperty("requestId") String requestId,
      @JsonProperty(value = "details", required = true) List<V1ErrorDetail> details) {
    if (code < 400 || code > 599) {
      throw new IllegalArgumentException("code must be a HTTP error status");
    }
    this.code = code;
    this.type = requireType(type);
    this.message = requireNonEmpty(message, "message");
    this.retryable = retryable;
    if (!retryable && retryAfterSeconds != null) {
      throw new IllegalArgumentException("retryAfterSeconds requires retryable=true");
    }
    if (retryAfterSeconds != null && retryAfterSeconds < 0) {
      throw new IllegalArgumentException("retryAfterSeconds cannot be negative");
    }
    this.retryAfterSeconds = retryAfterSeconds;
    this.requestId = requireNonEmpty(requestId, "requestId");
    this.details = immutableDetails(Objects.requireNonNull(details, "details cannot be null"));
  }

  /**
   * Returns the HTTP status code represented in the body.
   *
   * @return the HTTP status code.
   */
  public int getCode() {
    return code;
  }

  /**
   * Returns the stable public error type.
   *
   * @return the error type.
   */
  public String getType() {
    return type;
  }

  /**
   * Returns a safe human-readable explanation.
   *
   * @return the error message.
   */
  public String getMessage() {
    return message;
  }

  /**
   * Returns whether the client may automatically retry a safe unchanged request.
   *
   * @return whether automatic retry is allowed.
   */
  public boolean isRetryable() {
    return retryable;
  }

  /**
   * Returns the optional server-selected retry delay in seconds.
   *
   * @return the retry delay, or {@code null} when no delay is prescribed.
   */
  @Nullable
  public Integer getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  /**
   * Returns the request correlation identifier.
   *
   * @return the request identifier.
   */
  public String getRequestId() {
    return requestId;
  }

  /**
   * Returns typed details for the error.
   *
   * @return an immutable detail list, empty when no public detail is applicable.
   */
  public List<V1ErrorDetail> getDetails() {
    return details;
  }

  private static String requireType(String value) {
    String type = requireNonEmpty(value, "type");
    if (!TYPE_PATTERN.matcher(type).matches()) {
      throw new IllegalArgumentException("type must be UPPER_SNAKE_CASE");
    }
    return type;
  }

  private static String requireNonEmpty(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " cannot be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " cannot be empty");
    }
    return value;
  }

  private static List<V1ErrorDetail> immutableDetails(List<V1ErrorDetail> values) {
    ArrayList<V1ErrorDetail> copy = new ArrayList<>(values.size());
    for (V1ErrorDetail value : values) {
      copy.add(Objects.requireNonNull(value, "details cannot contain null values"));
    }
    return Collections.unmodifiableList(copy);
  }
}
