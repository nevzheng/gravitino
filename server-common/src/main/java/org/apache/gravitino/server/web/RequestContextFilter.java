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

package org.apache.gravitino.server.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.utils.RequestContext;
import org.slf4j.MDC;

/**
 * A servlet filter that captures request-scoped data from each HTTP request and stores it in {@link
 * RequestContext} so that audit event constructors and public API error responses can read it on
 * the same thread.
 *
 * <p>When a reverse proxy is in use, the real client IP is taken from the first entry of the {@code
 * X-Forwarded-For} header (a de-facto standard header set by reverse proxies; note that it is
 * trusted unconditionally — deployments where the server is reachable directly, without a trusted
 * reverse proxy, should be aware that clients can spoof this header). If the header is absent,
 * {@link HttpServletRequest#getRemoteAddr()} is used instead.
 *
 * <p>The stored value is always cleared in a {@code finally} block to prevent thread-pool leaks.
 */
public class RequestContextFilter implements Filter {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  /** The request correlation header accepted and returned by the HTTP server. */
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  private static final String MDC_REQUEST_ID_KEY = "requestId";
  private static final Pattern REQUEST_ID_PATTERN =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    try {
      if (request instanceof HttpServletRequest) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        RequestContext.setRemoteAddress(resolveClientAddress(httpRequest));
        String requestId = resolveRequestId(httpRequest);
        RequestContext.setRequestId(requestId);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        if (response instanceof HttpServletResponse) {
          ((HttpServletResponse) response).setHeader(REQUEST_ID_HEADER, requestId);
        }
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_REQUEST_ID_KEY);
      RequestContext.clear();
    }
  }

  @Override
  public void destroy() {}

  /**
   * Returns whether a caller-supplied request ID conforms to the public bounded header grammar.
   *
   * @param requestId candidate header value.
   * @return whether the candidate is nonblank and safe to echo into logs and response headers.
   */
  public static boolean isValidRequestId(String requestId) {
    return StringUtils.isNotBlank(requestId) && REQUEST_ID_PATTERN.matcher(requestId).matches();
  }

  private String resolveClientAddress(HttpServletRequest request) {
    String xForwardedFor = request.getHeader(X_FORWARDED_FOR);
    if (StringUtils.isNotBlank(xForwardedFor)) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private String resolveRequestId(HttpServletRequest request) {
    String suppliedRequestId = request.getHeader(REQUEST_ID_HEADER);
    if (isValidRequestId(suppliedRequestId)) {
      return suppliedRequestId;
    }
    return UUID.randomUUID().toString();
  }
}
