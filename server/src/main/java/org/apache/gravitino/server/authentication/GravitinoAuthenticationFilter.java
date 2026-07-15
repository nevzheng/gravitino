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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.VersioningFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorContext;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicErrorTranslator;

/**
 * Authentication filter for the primary Gravitino server that emits V1 public JSON errors before a
 * V1 request reaches Jersey while preserving legacy API authentication responses.
 */
public class GravitinoAuthenticationFilter extends AuthenticationFilter {

  private static final String HEAD_METHOD = "HEAD";

  @VisibleForTesting
  GravitinoAuthenticationFilter(List<Authenticator> authenticators) {
    super(authenticators);
  }

  /** Creates the primary-server authentication filter. */
  public GravitinoAuthenticationFilter() {}

  @Override
  protected void sendAuthErrorResponse(
      HttpServletRequest request, HttpServletResponse response, Exception exception)
      throws IOException {
    sendAuthErrorResponse(request, response, exception, authenticationChallenges(exception));
  }

  @Override
  protected void sendAuthErrorResponse(
      HttpServletRequest request,
      HttpServletResponse response,
      Exception exception,
      List<String> authenticationChallenges)
      throws IOException {
    if (!VersioningFilter.isV1Request(request)) {
      super.sendAuthErrorResponse(request, response, exception, authenticationChallenges);
      return;
    }

    Response publicResponse =
        V1PublicErrorTranslator.toResponse(
            exception, V1ErrorContext.empty(), authenticationChallenges);
    response.setStatus(publicResponse.getStatus());
    for (Map.Entry<String, List<String>> header : publicResponse.getStringHeaders().entrySet()) {
      writeHeaders(response, header.getKey(), header.getValue());
    }
    if (HEAD_METHOD.equalsIgnoreCase(request.getMethod())) {
      return;
    }
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    ObjectMapperProvider.objectMapper()
        .writeValue(response.getWriter(), publicResponse.getEntity());
  }

  private static List<String> authenticationChallenges(Exception exception) {
    if (!(exception instanceof UnauthorizedException)) {
      return Collections.emptyList();
    }
    return ((UnauthorizedException) exception).getChallenges();
  }

  private static void writeHeaders(
      HttpServletResponse response, String headerName, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      if (index == 0) {
        response.setHeader(headerName, values.get(index));
      } else {
        response.addHeader(headerName, values.get(index));
      }
    }
  }
}
