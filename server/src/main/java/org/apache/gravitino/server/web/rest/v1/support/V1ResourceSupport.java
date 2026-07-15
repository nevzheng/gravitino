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
package org.apache.gravitino.server.web.rest.v1.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.apache.gravitino.server.web.rest.v1.error.V1PreconditionFailedException;

/** Shared representation-validator support for public Gravitino V1 resource routes. */
public final class V1ResourceSupport {

  private static final int MAX_ENTITY_TAG_HEADER_LENGTH = 4096;

  private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("[^/?#]{1,255}");

  // A V1 If-Match value is exactly one strong entity tag. This intentionally rejects RFC wildcard,
  // weak validators, lists, controls, quote characters, and backslashes in the opaque value.
  private static final Pattern STRONG_ENTITY_TAG_PATTERN =
      Pattern.compile("^\"[\\x21\\x23-\\x5B\\x5D-\\x7E]+\"$");

  private V1ResourceSupport() {}

  /**
   * Returns a strong SHA-256 validator for the canonical JSON representation of a V1 resource.
   *
   * @param resource public V1 resource representation.
   * @return the strong representation validator.
   */
  public static EntityTag entityTag(Object resource) {
    Objects.requireNonNull(resource, "resource cannot be null");
    try {
      byte[] representation =
          ObjectMapperProvider.objectMapper()
              .writeValueAsString(resource)
              .getBytes(StandardCharsets.UTF_8);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(representation);
      StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hexadecimal.append(String.format("%02x", value & 0xff));
      }
      return new EntityTag(hexadecimal.toString());
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to calculate a V1 resource validator.", exception);
    }
  }

  /**
   * Marks a V1 response as not storable by an HTTP cache.
   *
   * <p>V1 routes use this conservative directive until they implement the conditional
   * representation semantics required to make a response safely reusable. Entity tags used only for
   * mutation preconditions do not, by themselves, make a representation cacheable.
   *
   * @param responseBuilder response to mark as not storable.
   * @return the supplied response builder with {@code Cache-Control: no-store}.
   */
  public static Response.ResponseBuilder noStore(Response.ResponseBuilder responseBuilder) {
    Objects.requireNonNull(responseBuilder, "responseBuilder cannot be null");
    return responseBuilder.header(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  /**
   * Requires the request's sole {@code If-Match} field to be the current strong entity tag.
   *
   * <p>V1 deliberately does not accept wildcard, weak, repeated, or list-valued preconditions for
   * mutations. A failure uses one public-safe error regardless of whether the validator is absent,
   * malformed, or stale so the caller can refresh the representation and retry deliberately.
   *
   * @param headers request headers.
   * @param currentEntityTag current strong resource validator.
   * @throws V1PreconditionFailedException if the required strong precondition is not satisfied.
   */
  public static void requireIfMatch(HttpHeaders headers, EntityTag currentEntityTag) {
    Objects.requireNonNull(headers, "headers cannot be null");
    Objects.requireNonNull(currentEntityTag, "currentEntityTag cannot be null");
    if (currentEntityTag.isWeak()) {
      throw new IllegalStateException("V1 resource validators must be strong.");
    }

    List<String> values = headers.getRequestHeader(HttpHeaders.IF_MATCH);
    if (values == null || values.size() != 1) {
      throw new V1PreconditionFailedException();
    }

    String value = values.get(0);
    if (value == null
        || value.length() > MAX_ENTITY_TAG_HEADER_LENGTH
        || !STRONG_ENTITY_TAG_PATTERN.matcher(value).matches()) {
      throw new V1PreconditionFailedException();
    }

    EntityTag requestedEntityTag = EntityTag.valueOf(value);
    if (requestedEntityTag.isWeak()
        || !requestedEntityTag.getValue().equals(currentEntityTag.getValue())) {
      throw new V1PreconditionFailedException();
    }
  }

  /**
   * Validates a decoded V1 resource-name path segment.
   *
   * @param field public path parameter name.
   * @param value decoded path parameter value.
   * @throws V1ClientInputException if the path segment violates the V1 contract.
   */
  public static void validatePathSegment(String field, String value) {
    if (value == null || !PATH_SEGMENT_PATTERN.matcher(value).matches()) {
      throw new V1ClientInputException(
          field, "Must be a 1 to 255 character URI path segment without /, ?, or #.");
    }
  }

  /**
   * Rejects query parameters not explicitly documented by the route.
   *
   * @param uriInfo request URI and query parameters.
   * @param allowedParameterNames parameter names accepted by the route.
   * @throws V1ClientInputException if an unexpected query parameter is supplied.
   */
  public static void rejectUnexpectedQueryParameters(
      UriInfo uriInfo, String... allowedParameterNames) {
    Objects.requireNonNull(uriInfo, "uriInfo cannot be null");
    Set<String> allowed = new HashSet<>(Arrays.asList(allowedParameterNames));
    for (String parameterName : uriInfo.getQueryParameters().keySet()) {
      if (!allowed.contains(parameterName)) {
        throw new V1ClientInputException(
            "query", "This operation does not accept the supplied query parameter.");
      }
    }
  }

  /**
   * Reads one optional V1 boolean query parameter with a strict lowercase JSON boolean value.
   *
   * @param uriInfo request URI and query parameters.
   * @param parameterName documented query parameter name.
   * @return false when absent, otherwise the supplied boolean value.
   * @throws V1ClientInputException if the parameter is repeated or not {@code true} or {@code
   *     false}.
   */
  public static boolean optionalBooleanQueryParameter(UriInfo uriInfo, String parameterName) {
    Objects.requireNonNull(uriInfo, "uriInfo cannot be null");
    Objects.requireNonNull(parameterName, "parameterName cannot be null");
    List<String> values = uriInfo.getQueryParameters().get(parameterName);
    if (values == null || values.isEmpty()) {
      return false;
    }
    if (values.size() != 1) {
      throw invalidBooleanQueryParameter(parameterName);
    }
    if ("true".equals(values.get(0))) {
      return true;
    }
    if ("false".equals(values.get(0))) {
      return false;
    }
    throw invalidBooleanQueryParameter(parameterName);
  }

  private static V1ClientInputException invalidBooleanQueryParameter(String parameterName) {
    return new V1ClientInputException(
        parameterName, "Must be one boolean query parameter with value true or false.");
  }
}
