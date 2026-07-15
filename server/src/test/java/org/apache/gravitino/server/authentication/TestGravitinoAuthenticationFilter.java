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
package org.apache.gravitino.server.authentication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Vector;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests primary-server V1 authentication failures before Jersey takes ownership of the request. */
public class TestGravitinoAuthenticationFilter {

  @AfterEach
  public void cleanup() {
    RequestContext.clear();
  }

  @Test
  public void testV1AuthenticationFailureUsesThePublicErrorEnvelope() throws Exception {
    ExposedAuthenticationFilter filter = new ExposedAuthenticationFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter output = new StringWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/metalakes/demo");
    when(response.getWriter()).thenReturn(new PrintWriter(output));
    RequestContext.setRequestId("request-auth-401");

    filter.writeFailure(
        request, response, new UnauthorizedException("credential internals", "Bearer"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(response).setHeader(RequestContextFilter.REQUEST_ID_HEADER, "request-auth-401");
    verify(response).setHeader(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");
    String body = output.toString();
    assertTrue(body.contains("\"code\":401"));
    assertTrue(body.contains("\"type\":\"UNAUTHENTICATED\""));
    assertTrue(body.contains("\"details\":[]"));
    assertFalse(body.contains("credential internals"));
  }

  @Test
  public void testV1HeadAuthenticationFailureDoesNotWriteAnErrorBody() throws Exception {
    ExposedAuthenticationFilter filter = new ExposedAuthenticationFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getRequestURI()).thenReturn("/api/v1/metalakes/demo");
    when(request.getMethod()).thenReturn("HEAD");
    RequestContext.setRequestId("request-auth-head-401");

    filter.writeFailure(
        request, response, new UnauthorizedException("credential internals", "Bearer"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(response).setHeader(RequestContextFilter.REQUEST_ID_HEADER, "request-auth-head-401");
    verify(response).setHeader(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");
    verify(response, never()).getWriter();
  }

  @Test
  public void testV1AuthenticationFallbackChallengesArePreserved() throws Exception {
    Authenticator authenticator = mock(Authenticator.class);
    GravitinoAuthenticationFilter filter =
        new GravitinoAuthenticationFilter(List.of(authenticator));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter output = new StringWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/metalakes/demo");
    when(request.getHeaders(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn(new Vector<String>().elements());
    when(response.getWriter()).thenReturn(new PrintWriter(output));
    when(authenticator.authenticationChallenges())
        .thenReturn(List.of("Bearer", "Basic realm=\"Gravitino\""));

    filter.doFilter(request, response, mock(FilterChain.class));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(response).setHeader(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");
    verify(response).addHeader(AuthConstants.HTTP_CHALLENGE_HEADER, "Basic realm=\"Gravitino\"");
    assertTrue(output.toString().contains("\"type\":\"UNAUTHENTICATED\""));
  }

  @Test
  public void testV1AuthenticationFailureWithoutAChallengeFailsClosed() throws Exception {
    ExposedAuthenticationFilter filter = new ExposedAuthenticationFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter output = new StringWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/metalakes/demo");
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    filter.writeFailure(request, response, new UnauthorizedException("credential internals"));

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    verify(response, never()).setHeader(AuthConstants.HTTP_CHALLENGE_HEADER, "Bearer");
    assertTrue(output.toString().contains("\"type\":\"INTERNAL_ERROR\""));
    assertFalse(output.toString().contains("credential internals"));
    assertFalse(output.toString().contains("\"type\":\"UNAUTHENTICATED\""));
  }

  private static class ExposedAuthenticationFilter extends GravitinoAuthenticationFilter {

    private void writeFailure(
        HttpServletRequest request, HttpServletResponse response, Exception exception)
        throws Exception {
      sendAuthErrorResponse(request, response, exception);
    }
  }
}
