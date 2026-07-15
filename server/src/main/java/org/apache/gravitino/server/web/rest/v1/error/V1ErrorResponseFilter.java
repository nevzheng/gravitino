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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.utils.RequestContext;

/** Normalizes framework and authorization failures into the V1 public error contract. */
@javax.annotation.Priority(Priorities.USER)
public final class V1ErrorResponseFilter implements ContainerResponseFilter {

  private static final String V1_API_PREFIX = "/api/v1";
  private static final String V1_RESOURCE_PREFIX = "/v1";

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext)
      throws IOException {
    if (!isV1Request(requestContext)) {
      return;
    }

    String requestId = RequestContext.getRequestId();
    if (requestId != null) {
      responseContext.getHeaders().putSingle(RequestContextFilter.REQUEST_ID_HEADER, requestId);
    }

    if (responseContext.getStatus() < 400) {
      return;
    }

    if (responseContext.getStatus() == 401
        || !(responseContext.getEntity() instanceof V1ErrorResponse)) {
      Response publicResponse =
          V1PublicErrorTranslator.toResponseForStatus(
              responseContext.getStatus(), authenticationChallenges(responseContext.getHeaders()));
      applyPublicError(responseContext, publicResponse);
    }

    if ("HEAD".equalsIgnoreCase(requestContext.getMethod())) {
      responseContext.setEntity(null);
    }
  }

  private boolean isV1Request(ContainerRequestContext requestContext) {
    String path = requestContext.getUriInfo().getRequestUri().getPath();
    return hasV1Prefix(path, V1_API_PREFIX) || hasV1Prefix(path, V1_RESOURCE_PREFIX);
  }

  private static boolean hasV1Prefix(String path, String prefix) {
    return prefix.equals(path) || path.startsWith(prefix + "/");
  }

  private static void applyPublicError(
      ContainerResponseContext responseContext, Response publicResponse) {
    MultivaluedMap<String, Object> headers = responseContext.getHeaders();
    responseContext.setStatus(publicResponse.getStatus());
    responseContext.setEntity(publicResponse.getEntity());
    headers.putSingle(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_TYPE);
    headers.putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
    headers.remove(HttpHeaders.RETRY_AFTER);
    removeAuthenticationChallenges(headers);
    List<String> challenges =
        publicResponse.getStringHeaders().get(AuthConstants.HTTP_CHALLENGE_HEADER);
    if (challenges != null) {
      for (String challenge : challenges) {
        headers.add(AuthConstants.HTTP_CHALLENGE_HEADER, challenge);
      }
    }
  }

  private static List<String> authenticationChallenges(MultivaluedMap<String, Object> headers) {
    List<String> challenges = new ArrayList<>();
    for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
      if (!AuthConstants.HTTP_CHALLENGE_HEADER.equalsIgnoreCase(entry.getKey())) {
        continue;
      }
      for (Object value : entry.getValue()) {
        if (value instanceof String) {
          challenges.add((String) value);
        }
      }
    }
    return challenges;
  }

  private static void removeAuthenticationChallenges(MultivaluedMap<String, Object> headers) {
    headers
        .entrySet()
        .removeIf(entry -> AuthConstants.HTTP_CHALLENGE_HEADER.equalsIgnoreCase(entry.getKey()));
  }
}
