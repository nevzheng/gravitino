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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.gravitino.server.web.VersioningFilter;

/**
 * Enforces route-versioned V1 request-header rules before authentication can terminate a request.
 *
 * <p>Jersey also performs these checks as defense in depth, but authentication is a servlet filter
 * and therefore runs before Jersey. Keeping the primary check here preserves V1's documented {@code
 * 400}, {@code 406}, and {@code 415} outcomes for unauthenticated requests.
 */
public final class V1RequestContractFilter implements Filter {

  private static final String HEAD_METHOD = "HEAD";

  /** Creates the V1 servlet-layer request-contract filter. */
  public V1RequestContractFilter() {}

  /** Initializes the filter. */
  @Override
  public void init(FilterConfig filterConfig) {}

  /**
   * Validates V1 request headers and stops invalid requests before the authentication filter.
   *
   * @param request the incoming servlet request.
   * @param response the outgoing servlet response.
   * @param chain the remaining servlet filter chain.
   * @throws IOException if an error response cannot be written.
   * @throws ServletException if the remaining filter chain fails.
   */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    if (!VersioningFilter.isV1Request(httpRequest)) {
      chain.doFilter(request, response);
      return;
    }

    Response validationFailure =
        V1RequestContract.validationFailure(
            httpRequest.getHeader(RequestContextFilter.REQUEST_ID_HEADER),
            headerValues(httpRequest, HttpHeaders.ACCEPT),
            httpRequest.getMethod(),
            headerValues(httpRequest, HttpHeaders.CONTENT_TYPE));
    if (validationFailure == null) {
      chain.doFilter(request, response);
      return;
    }

    writePublicError(httpRequest, httpResponse, validationFailure);
  }

  /** Destroys the filter. */
  @Override
  public void destroy() {}

  private static List<String> headerValues(HttpServletRequest request, String headerName) {
    Enumeration<String> values = request.getHeaders(headerName);
    if (values == null) {
      return new ArrayList<>();
    }

    List<String> headerValues = new ArrayList<>();
    while (values.hasMoreElements()) {
      headerValues.add(values.nextElement());
    }
    return headerValues;
  }

  private static void writePublicError(
      HttpServletRequest request, HttpServletResponse response, Response publicResponse)
      throws IOException {
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
