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
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.apache.gravitino.server.web.RequestContextFilter;

/** Provides Jersey-layer defense in depth for route-versioned V1 request-header validation. */
@PreMatching
@javax.annotation.Priority(Priorities.HEADER_DECORATOR)
public final class V1MediaTypeFilter implements ContainerRequestFilter {

  private static final String V1_API_PREFIX = "/api/v1";
  private static final String V1_RESOURCE_PREFIX = "/v1";

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!isV1Request(requestContext)) {
      return;
    }
    MultivaluedMap<String, String> headers = requestContext.getHeaders();
    Response validationFailure =
        V1RequestContract.validationFailure(
            requestContext.getHeaderString(RequestContextFilter.REQUEST_ID_HEADER),
            headers == null ? null : headers.get(HttpHeaders.ACCEPT),
            requestContext.getMethod(),
            headers == null ? null : headers.get(HttpHeaders.CONTENT_TYPE));
    if (validationFailure != null) {
      requestContext.abortWith(validationFailure);
    }
  }

  private boolean isV1Request(ContainerRequestContext requestContext) {
    String path = requestContext.getUriInfo().getRequestUri().getPath();
    return hasV1Prefix(path, V1_API_PREFIX) || hasV1Prefix(path, V1_RESOURCE_PREFIX);
  }

  private static boolean hasV1Prefix(String path, String prefix) {
    return prefix.equals(path) || path.startsWith(prefix + "/");
  }
}
