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
package org.apache.gravitino.server.web.rest.v1.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.MetalakeAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.NotInUseException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.rest.v1.error.V1Error;
import org.apache.gravitino.rest.v1.error.V1ErrorDetail;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.apache.gravitino.rest.v1.error.V1FieldViolationErrorDetail;
import org.apache.gravitino.rest.v1.error.V1ResourceInfoErrorDetail;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.utils.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Translates internal failures into the stable public error contract for Gravitino V1. */
public final class V1PublicErrorTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(V1PublicErrorTranslator.class);

  private static final String FALLBACK_REQUEST_ID = "unknown";

  private static final int MAX_AUTHENTICATION_CHALLENGE_LENGTH = 4096;

  // RFC 9110 challenge = auth-scheme [ 1*SP ( token68 / #auth-param ) ]. This intentionally
  // validates the safe outer shape rather than trying to parse every authentication scheme.
  private static final Pattern AUTHENTICATION_CHALLENGE_PATTERN =
      Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+(?:[ \\t]+[\\x20-\\x7E\\t]+)?$");

  private V1PublicErrorTranslator() {}

  /**
   * Builds a full V1 JSON response for an internal failure.
   *
   * @param throwable the internal failure.
   * @param errorContext public context safe to disclose.
   * @return the public V1 error response.
   */
  public static Response toResponse(Throwable throwable, V1ErrorContext errorContext) {
    return toResponse(throwable, errorContext, authenticationChallenges(throwable));
  }

  /**
   * Builds a full V1 JSON response for an internal failure with selected authentication challenges.
   *
   * <p>A 401 response is only emitted when at least one supplied challenge is a safe,
   * syntactically-valid HTTP authentication challenge. Otherwise the response fails closed as a
   * sanitized 500 error.
   *
   * @param throwable the internal failure.
   * @param errorContext public context safe to disclose.
   * @param authenticationChallenges selected {@code WWW-Authenticate} challenge values.
   * @return the public V1 error response.
   */
  public static Response toResponse(
      Throwable throwable, V1ErrorContext errorContext, List<String> authenticationChallenges) {
    PublicError publicError = translate(throwable, errorContext, authenticationChallenges);
    if (publicError.status >= 500) {
      LOG.error(
          "V1 API request {} failed with public error type {}",
          requestId(),
          publicError.type,
          throwable);
    }
    return response(publicError);
  }

  /**
   * Builds a V1 JSON response for a framework-generated HTTP failure.
   *
   * @param status the framework response status.
   * @return the normalized V1 error response.
   */
  public static Response toResponseForStatus(int status) {
    return toResponseForStatus(status, Collections.emptyList());
  }

  /**
   * Builds a V1 JSON response for a framework-generated HTTP failure with selected authentication
   * challenges.
   *
   * @param status the framework response status.
   * @param authenticationChallenges selected {@code WWW-Authenticate} challenge values.
   * @return the normalized V1 error response.
   */
  public static Response toResponseForStatus(int status, List<String> authenticationChallenges) {
    return response(fromStatus(status, authenticationChallenges));
  }

  /**
   * Returns a public V1 error body for an internal failure.
   *
   * @param throwable the internal failure.
   * @param errorContext public request context.
   * @return the normalized public error body.
   */
  public static V1ErrorResponse errorResponse(Throwable throwable, V1ErrorContext errorContext) {
    return toErrorResponse(translate(throwable, errorContext, authenticationChallenges(throwable)));
  }

  /**
   * Builds a V1 invalid-argument response with one safe field-violation detail.
   *
   * @param field public request field or header name.
   * @param description safe explanation of the violated rule.
   * @return the complete public error response.
   */
  public static Response toInvalidArgumentResponse(String field, String description) {
    return response(
        PublicError.of(
            400,
            "INVALID_ARGUMENT",
            "The request is invalid.",
            false,
            null,
            Collections.singletonList(new V1FieldViolationErrorDetail(field, description))));
  }

  private static Response response(PublicError publicError) {
    Response.ResponseBuilder responseBuilder =
        Response.status(publicError.status)
            .entity(toErrorResponse(publicError))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .header(RequestContextFilter.REQUEST_ID_HEADER, requestId())
            .header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (publicError.retryAfterSeconds != null) {
      responseBuilder.header("Retry-After", publicError.retryAfterSeconds);
    }
    for (String challenge : publicError.authenticationChallenges) {
      responseBuilder.header(AuthConstants.HTTP_CHALLENGE_HEADER, challenge);
    }
    return responseBuilder.build();
  }

  private static V1ErrorResponse toErrorResponse(PublicError publicError) {
    return new V1ErrorResponse(
        new V1Error(
            publicError.status,
            publicError.type,
            publicError.message,
            publicError.retryable,
            publicError.retryAfterSeconds,
            requestId(),
            publicError.details));
  }

  private static PublicError translate(
      Throwable throwable, V1ErrorContext errorContext, List<String> authenticationChallenges) {
    Throwable cause = unwrap(throwable);
    if (cause instanceof UnauthorizedException) {
      return unauthenticated(authenticationChallenges);
    }
    if (cause instanceof ForbiddenException) {
      return PublicError.of(403, "PERMISSION_DENIED", "Permission is denied.");
    }
    if (cause instanceof NoSuchMetalakeException) {
      return notFound(
          "METALAKE_NOT_FOUND", "The requested metalake was not found.", "METALAKE", errorContext);
    }
    if (cause instanceof NoSuchCatalogException) {
      return notFound(
          "CATALOG_NOT_FOUND", "The requested catalog was not found.", "CATALOG", errorContext);
    }
    if (cause instanceof NoSuchSchemaException) {
      return notFound(
          "SCHEMA_NOT_FOUND", "The requested schema was not found.", "SCHEMA", errorContext);
    }
    if (cause instanceof NoSuchTableException) {
      return notFound(
          "TABLE_NOT_FOUND", "The requested table was not found.", "TABLE", errorContext);
    }
    if (cause instanceof MetalakeAlreadyExistsException) {
      return alreadyExists(
          "METALAKE_ALREADY_EXISTS", "The metalake already exists.", "METALAKE", errorContext);
    }
    if (cause instanceof CatalogAlreadyExistsException) {
      return alreadyExists(
          "CATALOG_ALREADY_EXISTS", "The catalog already exists.", "CATALOG", errorContext);
    }
    if (cause instanceof SchemaAlreadyExistsException) {
      return alreadyExists(
          "SCHEMA_ALREADY_EXISTS", "The schema already exists.", "SCHEMA", errorContext);
    }
    if (cause instanceof TableAlreadyExistsException) {
      return alreadyExists(
          "TABLE_ALREADY_EXISTS", "The table already exists.", "TABLE", errorContext);
    }
    if (cause instanceof V1PreconditionFailedException) {
      return PublicError.of(
          412,
          "PRECONDITION_FAILED",
          V1PreconditionFailedException.SAFE_DESCRIPTION,
          false,
          null,
          deepestResourceDetails(errorContext));
    }
    if (cause instanceof V1ClientInputException) {
      V1ClientInputException inputException = (V1ClientInputException) cause;
      return PublicError.of(
          400,
          "INVALID_ARGUMENT",
          "The request is invalid.",
          false,
          null,
          Collections.singletonList(
              new V1FieldViolationErrorDetail(
                  inputException.field(), inputException.safeDescription())));
    }
    if (cause instanceof NotInUseException) {
      return PublicError.of(409, "RESOURCE_NOT_ACTIVE", "The requested resource is not active.");
    }
    if (cause instanceof UnsupportedOperationException) {
      return PublicError.of(501, "UNSUPPORTED_OPERATION", "This operation is not supported.");
    }
    if (cause instanceof ConnectionFailedException) {
      return PublicError.of(
          502,
          "UPSTREAM_CONNECTION_FAILED",
          "The catalog connection failed.",
          errorContext.safeToRetry(),
          null,
          Collections.emptyList());
    }
    return PublicError.of(500, "INTERNAL_ERROR", "The server encountered an internal error.");
  }

  private static PublicError fromStatus(int status, List<String> authenticationChallenges) {
    switch (status) {
      case 400:
        return PublicError.of(400, "INVALID_ARGUMENT", "The request is invalid.");
      case 401:
        return unauthenticated(authenticationChallenges);
      case 403:
        return PublicError.of(403, "PERMISSION_DENIED", "Permission is denied.");
      case 404:
        return PublicError.of(404, "ROUTE_NOT_FOUND", "The requested route was not found.");
      case 405:
        return PublicError.of(405, "METHOD_NOT_ALLOWED", "The HTTP method is not allowed.");
      case 406:
        return PublicError.of(
            406, "NOT_ACCEPTABLE", "The requested representation is not supported.");
      case 415:
        return PublicError.of(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "The request Content-Type is not supported.",
            false,
            null,
            Collections.singletonList(
                new V1FieldViolationErrorDetail(
                    HttpHeaders.CONTENT_TYPE, "Must be application/json.")));
      case 412:
        return PublicError.of(
            412, "PRECONDITION_FAILED", V1PreconditionFailedException.SAFE_DESCRIPTION);
      default:
        return PublicError.of(500, "INTERNAL_ERROR", "The server encountered an internal error.");
    }
  }

  private static PublicError unauthenticated(List<String> authenticationChallenges) {
    List<String> validChallenges = validAuthenticationChallenges(authenticationChallenges);
    if (validChallenges.isEmpty()) {
      return PublicError.of(500, "INTERNAL_ERROR", "The server encountered an internal error.");
    }
    return PublicError.unauthenticated(validChallenges);
  }

  private static List<String> authenticationChallenges(Throwable throwable) {
    Throwable cause = unwrap(throwable);
    if (!(cause instanceof UnauthorizedException)) {
      return Collections.emptyList();
    }
    return ((UnauthorizedException) cause).getChallenges();
  }

  private static List<String> validAuthenticationChallenges(List<String> authenticationChallenges) {
    if (authenticationChallenges == null || authenticationChallenges.isEmpty()) {
      return new ArrayList<>();
    }

    List<String> validChallenges = new ArrayList<>(authenticationChallenges.size());
    for (String challenge : authenticationChallenges) {
      if (isValidAuthenticationChallenge(challenge)) {
        validChallenges.add(challenge);
      }
    }
    return validChallenges;
  }

  private static boolean isValidAuthenticationChallenge(@Nullable String challenge) {
    return challenge != null
        && challenge.length() <= MAX_AUTHENTICATION_CHALLENGE_LENGTH
        && AUTHENTICATION_CHALLENGE_PATTERN.matcher(challenge).matches();
  }

  private static PublicError notFound(
      String type, String message, String resourceType, V1ErrorContext errorContext) {
    return PublicError.of(
        404, type, message, false, null, resourceDetails(errorContext, resourceType));
  }

  private static PublicError alreadyExists(
      String type, String message, String resourceType, V1ErrorContext errorContext) {
    return PublicError.of(
        409, type, message, false, null, resourceDetails(errorContext, resourceType));
  }

  private static List<V1ErrorDetail> resourceDetails(
      V1ErrorContext errorContext, String resourceType) {
    String resourceName = errorContext.resourceName(resourceType);
    if (resourceName == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(new V1ResourceInfoErrorDetail(resourceType, resourceName));
  }

  private static List<V1ErrorDetail> deepestResourceDetails(V1ErrorContext errorContext) {
    String[] resourceTypes = {"TABLE", "SCHEMA", "CATALOG", "METALAKE"};
    for (String resourceType : resourceTypes) {
      String resourceName = errorContext.resourceName(resourceType);
      if (resourceName != null) {
        return Collections.singletonList(new V1ResourceInfoErrorDetail(resourceType, resourceName));
      }
    }
    return Collections.emptyList();
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null
        && (current instanceof V1ApiException
            || current instanceof java.util.concurrent.ExecutionException
            || current instanceof java.lang.reflect.InvocationTargetException)) {
      current = current.getCause();
    }
    return current;
  }

  private static String requestId() {
    String requestId = RequestContext.getRequestId();
    return requestId == null ? FALLBACK_REQUEST_ID : requestId;
  }

  private static final class PublicError {
    private final int status;
    private final String type;
    private final String message;
    private final boolean retryable;
    @Nullable private final Integer retryAfterSeconds;
    private final List<V1ErrorDetail> details;
    private final List<String> authenticationChallenges;

    private PublicError(
        int status,
        String type,
        String message,
        boolean retryable,
        @Nullable Integer retryAfterSeconds,
        List<V1ErrorDetail> details,
        List<String> authenticationChallenges) {
      this.status = status;
      this.type = type;
      this.message = message;
      this.retryable = retryable;
      this.retryAfterSeconds = retryAfterSeconds;
      this.details = details;
      this.authenticationChallenges = authenticationChallenges;
    }

    private static PublicError of(int status, String type, String message) {
      return of(status, type, message, false, null, Collections.emptyList());
    }

    private static PublicError of(
        int status,
        String type,
        String message,
        boolean retryable,
        @Nullable Integer retryAfterSeconds,
        List<V1ErrorDetail> details) {
      return new PublicError(
          status, type, message, retryable, retryAfterSeconds, details, Collections.emptyList());
    }

    private static PublicError unauthenticated(List<String> authenticationChallenges) {
      return new PublicError(
          401,
          "UNAUTHENTICATED",
          "Authentication is required.",
          false,
          null,
          Collections.emptyList(),
          authenticationChallenges);
    }
  }
}
