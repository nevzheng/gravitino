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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests V1 response-boundary enforcement for HTTP authentication challenges. */
public class TestV1ErrorResponseFilter {

  private final V1ErrorResponseFilter filter = new V1ErrorResponseFilter();

  @Test
  public void testFrameworkUnauthorizedResponseWithoutChallengeFailsClosed() throws Exception {
    ContainerRequestContext requestContext = requestContext("GET");
    ContainerResponseContext responseContext = responseContext(401, new Object());
    MultivaluedMap<String, Object> headers = responseContext.getHeaders();

    filter.filter(requestContext, responseContext);

    verify(responseContext).setStatus(500);
    ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
    verify(responseContext).setEntity(entity.capture());
    V1ErrorResponse body = (V1ErrorResponse) entity.getValue();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
    assertNull(headers.get(AuthConstants.HTTP_CHALLENGE_HEADER));
  }

  @Test
  public void testFrameworkUnauthorizedResponsePreservesMultipleChallenges() throws Exception {
    ContainerRequestContext requestContext = requestContext("GET");
    ContainerResponseContext responseContext = responseContext(401, new Object());
    MultivaluedMap<String, Object> headers = responseContext.getHeaders();
    headers.add(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");
    headers.add(AuthConstants.HTTP_CHALLENGE_HEADER, "Basic realm=\"Gravitino\"");

    filter.filter(requestContext, responseContext);

    verify(responseContext).setStatus(401);
    ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
    verify(responseContext).setEntity(entity.capture());
    V1ErrorResponse body = (V1ErrorResponse) entity.getValue();
    assertEquals("UNAUTHENTICATED", body.getError().getType());
    assertEquals(
        List.of("Bearer", "Basic realm=\"Gravitino\""),
        headers.get(AuthConstants.HTTP_CHALLENGE_HEADER));
  }

  @Test
  public void testHeadUnauthorizedResponseKeepsChallengeWithoutBody() throws Exception {
    ContainerRequestContext requestContext = requestContext("HEAD");
    ContainerResponseContext responseContext = responseContext(401, new Object());
    MultivaluedMap<String, Object> headers = responseContext.getHeaders();
    headers.add(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");

    filter.filter(requestContext, responseContext);

    ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
    verify(responseContext, times(2)).setEntity(entity.capture());
    assertNotNull(entity.getAllValues().get(0));
    assertNull(entity.getAllValues().get(1));
    assertEquals(List.of("Bearer"), headers.get(AuthConstants.HTTP_CHALLENGE_HEADER));
  }

  private static ContainerRequestContext requestContext(String method) {
    ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/v1/metalakes/demo"));
    when(requestContext.getUriInfo()).thenReturn(uriInfo);
    when(requestContext.getMethod()).thenReturn(method);
    return requestContext;
  }

  private static ContainerResponseContext responseContext(int status, Object entity) {
    ContainerResponseContext responseContext = mock(ContainerResponseContext.class);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    when(responseContext.getStatus()).thenReturn(status);
    when(responseContext.getEntity()).thenReturn(entity);
    when(responseContext.getHeaders()).thenReturn(headers);
    return responseContext;
  }
}
