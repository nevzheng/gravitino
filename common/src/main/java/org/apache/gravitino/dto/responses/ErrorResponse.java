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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RESTException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;

/** Represents an error response. */
@Getter
@EqualsAndHashCode(callSuper = true)
public class ErrorResponse extends BaseResponse {

  @JsonProperty("type")
  private String type;

  @JsonProperty("message")
  private String message;

  @Nullable
  @JsonProperty("stack")
  private List<String> stack;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("reason")
  private RecoveryConflictReason reason;

  private ErrorResponse(int code, String type, String message, List<String> stack) {
    this(code, type, message, stack, null);
  }

  private ErrorResponse(
      int code,
      String type,
      String message,
      List<String> stack,
      @Nullable RecoveryConflictReason reason) {
    super(code);
    this.type = type;
    this.message = message;
    this.stack = stack;
    this.reason = reason;
  }

  private ErrorResponse() {
    super();
    this.type = null;
    this.message = null;
    this.stack = null;
    this.reason = null;
  }

  /** Validates the error response. */
  @Override
  public void validate() {
    super.validate();

    Preconditions.checkArgument(type != null && !type.isEmpty(), "type cannot be null or empty");
    Preconditions.checkArgument(
        message != null && !message.isEmpty(), "message cannot be null or empty");
    Preconditions.checkArgument(
        getCode() != ErrorConstants.RECOVERY_CONFLICT_CODE || reason != null,
        "reason cannot be null for a recovery conflict");
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("ErrorResponse(")
        .append("code=")
        .append(super.getCode())
        .append(", type=")
        .append(type)
        .append(", message=")
        .append(message);

    if (reason != null) {
      sb.append(", reason=").append(reason);
    }
    sb.append(")");

    if (stack != null && !stack.isEmpty()) {
      for (String s : stack) {
        sb.append("\n\t").append(s);
      }
    }

    return sb.toString();
  }

  /**
   * Creates a new rest error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse restError(String message) {
    return new ErrorResponse(
        ErrorConstants.REST_ERROR_CODE, RESTException.class.getSimpleName(), message, null);
  }

  /**
   * Create a new illegal arguments error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse illegalArguments(String message) {
    return illegalArguments(message, null);
  }

  /**
   * Create a new illegal arguments error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse illegalArguments(String message, Throwable throwable) {
    return illegalArguments(IllegalArgumentException.class.getSimpleName(), message, throwable);
  }

  /**
   * Create a new illegal arguments error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse illegalArguments(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.ILLEGAL_ARGUMENTS_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new connection failed error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse connectionFailed(String message) {
    return connectionFailed(message, null);
  }

  /**
   * Create a new connection failed error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse connectionFailed(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.CONNECTION_FAILED_CODE,
        ConnectionFailedException.class.getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Create a new not found error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse notFound(String type, String message) {
    return notFound(type, message, null);
  }

  /**
   * Create a new not found error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse notFound(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.NOT_FOUND_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new internal error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse internalError(String message) {
    return internalError(message, null);
  }

  /**
   * Create a new internal error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse internalError(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.INTERNAL_ERROR_CODE,
        RuntimeException.class.getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Create a new already exists error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse alreadyExists(String type, String message) {
    return alreadyExists(type, message, null);
  }

  /**
   * Create a new already exists error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse alreadyExists(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.ALREADY_EXISTS_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new not in use error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse notInUse(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.NOT_IN_USE_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new entity in use error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse inUse(String type, String message, Throwable throwable) {
    return new ErrorResponse(ErrorConstants.IN_USE_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new non-empty error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse nonEmpty(String type, String message) {
    return nonEmpty(type, message, null);
  }

  /**
   * Create a new non-empty error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse nonEmpty(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.NON_EMPTY_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Create a new unknown error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse unknownError(String message) {
    return new ErrorResponse(
        ErrorConstants.UNKNOWN_ERROR_CODE, RuntimeException.class.getSimpleName(), message, null);
  }

  /**
   * Create a new oauth error instance of {@link ErrorResponse}.
   *
   * @param code The code of the error.
   * @param type The type of the error.
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse oauthError(int code, String type, String message) {
    return new ErrorResponse(code, type, message, null);
  }

  /**
   * Create a new unsupported operation error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @return The new instance.
   */
  public static ErrorResponse unsupportedOperation(String message) {
    return unsupportedOperation(message, null);
  }

  /**
   * Create a new unsupported operation error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse unsupportedOperation(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.UNSUPPORTED_OPERATION_CODE,
        throwable == null
            ? UnsupportedOperationException.class.getSimpleName()
            : throwable.getClass().getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Create a new forbidden operation error instance of {@link ErrorResponse}.
   *
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse forbidden(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.FORBIDDEN_CODE,
        ForbiddenException.class.getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Create a new unauthorized error instance of {@link ErrorResponse}.
   *
   * @param type The type of the error.
   * @param message The message of the error.
   * @param throwable The throwable that caused the error.
   * @return The new instance.
   */
  public static ErrorResponse unauthorized(String type, String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.UNAUTHORIZED_CODE, type, message, getStackTrace(throwable));
  }

  /**
   * Creates an expired-tombstone error response.
   *
   * @param message The error message.
   * @param throwable The throwable that caused the error.
   * @return The new response.
   */
  public static ErrorResponse tombstoneExpired(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.TOMBSTONE_EXPIRED_CODE,
        throwable == null
            ? TombstoneExpiredException.class.getSimpleName()
            : throwable.getClass().getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Creates a changed-tombstone error response.
   *
   * @param message The error message.
   * @param throwable The throwable that caused the error.
   * @return The new response.
   */
  public static ErrorResponse tombstoneChanged(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.TOMBSTONE_CHANGED_CODE,
        throwable == null
            ? TombstoneChangedException.class.getSimpleName()
            : throwable.getClass().getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Creates a precondition-required error response.
   *
   * @param message The error message.
   * @param throwable The throwable that caused the error.
   * @return The new response.
   */
  public static ErrorResponse preconditionRequired(String message, Throwable throwable) {
    return new ErrorResponse(
        ErrorConstants.PRECONDITION_REQUIRED_CODE,
        throwable == null
            ? PreconditionRequiredException.class.getSimpleName()
            : throwable.getClass().getSimpleName(),
        message,
        getStackTrace(throwable));
  }

  /**
   * Creates a recovery-conflict error response with a stable reason.
   *
   * @param reason The stable recovery-conflict reason.
   * @param message The error message.
   * @param throwable The throwable that caused the error.
   * @return The new response.
   */
  public static ErrorResponse recoveryConflict(
      RecoveryConflictReason reason, String message, Throwable throwable) {
    Preconditions.checkArgument(reason != null, "reason cannot be null");
    return new ErrorResponse(
        ErrorConstants.RECOVERY_CONFLICT_CODE,
        throwable == null
            ? RecoveryConflictException.class.getSimpleName()
            : throwable.getClass().getSimpleName(),
        message,
        getStackTrace(throwable),
        reason);
  }

  private static List<String> getStackTrace(Throwable throwable) {
    if (throwable == null) {
      return null;
    }

    StringWriter sw = new StringWriter();
    try (PrintWriter pw = new PrintWriter(sw)) {
      throwable.printStackTrace(pw);
    }
    return Arrays.asList(sw.toString().split("\n"));
  }
}
