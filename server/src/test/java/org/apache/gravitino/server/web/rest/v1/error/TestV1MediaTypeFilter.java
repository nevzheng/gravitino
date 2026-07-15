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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests route-versioned request-header validation and legacy media rejection. */
public class TestV1MediaTypeFilter {

  private final V1MediaTypeFilter filter = new V1MediaTypeFilter();

  @Test
  public void testRejectsInvalidRequestIdWithPublicFieldViolation() throws Exception {
    ContainerRequestContext requestContext = requestContext("/api/v1/metalakes/demo");
    when(requestContext.getHeaderString(RequestContextFilter.REQUEST_ID_HEADER))
        .thenReturn("invalid request id");

    filter.filter(requestContext);

    ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
    verify(requestContext).abortWith(responseCaptor.capture());
    Response response = responseCaptor.getValue();
    assertEquals(400, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INVALID_ARGUMENT", body.getError().getType());
    assertEquals("FIELD_VIOLATION", body.getError().getDetails().get(0).getKind());
  }

  @Test
  public void testRejectsLegacyVendorMediaTypeOnV1Route() throws Exception {
    ContainerRequestContext requestContext = requestContext("/api/v1/metalakes/demo");
    MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
    headers.add(HttpHeaders.ACCEPT, "application/vnd.gravitino.v1+json");
    when(requestContext.getHeaders()).thenReturn(headers);

    filter.filter(requestContext);

    ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
    verify(requestContext).abortWith(responseCaptor.capture());
    Response response = responseCaptor.getValue();
    assertEquals(406, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("NOT_ACCEPTABLE", body.getError().getType());
  }

  private static ContainerRequestContext requestContext(String path) {
    ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
    when(requestContext.getUriInfo()).thenReturn(uriInfo);
    return requestContext;
  }
}
