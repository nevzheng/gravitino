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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.HttpHeaders;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests servlet-layer V1 request validation that runs before authentication. */
public class TestV1RequestContractFilter {

  private final V1RequestContractFilter filter = new V1RequestContractFilter();

  @AfterEach
  public void clearRequestContext() {
    RequestContext.clear();
  }

  @Test
  public void testRejectsInvalidRequestIdBeforeDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    StringWriter output = new StringWriter();
    RequestContext.setRequestId("generated-request-id");
    when(request.getHeader(RequestContextFilter.REQUEST_ID_HEADER)).thenReturn("not a valid id");
    when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.emptyEnumeration());
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    filter.doFilter(request, response, authenticationChain);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(response).setHeader(RequestContextFilter.REQUEST_ID_HEADER, "generated-request-id");
    verify(authenticationChain, never()).doFilter(request, response);
    assertTrue(output.toString().contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(output.toString().contains("\"field\":\"X-Request-Id\""));
  }

  @Test
  public void testRejectsLegacyVendorMediaTypeBeforeDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    StringWriter output = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeaders(HttpHeaders.ACCEPT))
        .thenReturn(
            new Vector<>(List.of("application/json, application/vnd.gravitino.v1+json"))
                .elements());
    when(request.getHeaders(HttpHeaders.CONTENT_TYPE))
        .thenReturn(new Vector<>(List.of("text/plain")).elements());
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    filter.doFilter(request, response, authenticationChain);

    verify(response).setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
    verify(authenticationChain, never()).doFilter(request, response);
    assertTrue(output.toString().contains("\"type\":\"NOT_ACCEPTABLE\""));
  }

  @Test
  public void testRejectsUnsupportedContentTypeBeforeDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    StringWriter output = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.emptyEnumeration());
    when(request.getHeaders(HttpHeaders.CONTENT_TYPE))
        .thenReturn(new Vector<>(List.of("text/plain")).elements());
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    filter.doFilter(request, response, authenticationChain);

    verify(response).setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
    verify(authenticationChain, never()).doFilter(request, response);
    assertTrue(output.toString().contains("\"type\":\"UNSUPPORTED_MEDIA_TYPE\""));
    assertTrue(output.toString().contains("\"field\":\"Content-Type\""));
  }

  @Test
  public void testRejectsMissingContentTypeBeforeDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    StringWriter output = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.emptyEnumeration());
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    filter.doFilter(request, response, authenticationChain);

    verify(response).setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
    verify(authenticationChain, never()).doFilter(request, response);
    assertTrue(output.toString().contains("\"type\":\"UNSUPPORTED_MEDIA_TYPE\""));
  }

  @Test
  public void testHeadValidationFailureDoesNotWriteABody() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    when(request.getMethod()).thenReturn("HEAD");
    when(request.getHeader(RequestContextFilter.REQUEST_ID_HEADER))
        .thenReturn("invalid request id");
    when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.emptyEnumeration());

    filter.doFilter(request, response, authenticationChain);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(response, never()).getWriter();
    verify(authenticationChain, never()).doFilter(request, response);
  }

  @Test
  public void testPassesValidV1RequestToDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    when(request.getHeader(RequestContextFilter.REQUEST_ID_HEADER)).thenReturn("client-request-42");
    when(request.getHeaders(HttpHeaders.ACCEPT))
        .thenReturn(new Vector<>(List.of("application/json")).elements());

    filter.doFilter(request, response, authenticationChain);

    verify(authenticationChain).doFilter(request, response);
  }

  @Test
  public void testPassesJsonMutationRequestToDownstreamAuthentication() throws Exception {
    HttpServletRequest request = v1Request();
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    when(request.getMethod()).thenReturn("PUT");
    when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.emptyEnumeration());
    when(request.getHeaders(HttpHeaders.CONTENT_TYPE))
        .thenReturn(new Vector<>(List.of("application/json; charset=UTF-8")).elements());

    filter.doFilter(request, response, authenticationChain);

    verify(authenticationChain).doFilter(request, response);
  }

  @Test
  public void testDoesNotApplyV1RulesToLegacyRoute() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain authenticationChain = mock(FilterChain.class);
    when(request.getRequestURI()).thenReturn("/api/metalakes/demo");
    when(request.getHeader(RequestContextFilter.REQUEST_ID_HEADER))
        .thenReturn("invalid request id");
    when(request.getHeaders(HttpHeaders.ACCEPT))
        .thenReturn(new Vector<>(List.of("application/vnd.gravitino.v1+json")).elements());

    filter.doFilter(request, response, authenticationChain);

    verify(authenticationChain).doFilter(request, response);
  }

  private static HttpServletRequest v1Request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/metalakes/demo");
    return request;
  }
}
