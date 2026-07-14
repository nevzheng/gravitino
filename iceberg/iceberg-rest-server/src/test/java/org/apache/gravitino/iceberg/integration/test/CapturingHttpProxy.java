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
package org.apache.gravitino.iceberg.integration.test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CapturingHttpProxy implements AutoCloseable {

  private static final Set<String> RESTRICTED_REQUEST_HEADERS =
      Set.of("connection", "content-length", "expect", "host", "upgrade");
  private static final Set<String> RESTRICTED_RESPONSE_HEADERS =
      Set.of("connection", "content-length", "transfer-encoding");

  private final int targetPort;
  private final HttpClient client;
  private final HttpServer server;
  private final List<CapturedExchange> exchanges;

  private CapturingHttpProxy(int targetPort) throws IOException {
    this.targetPort = targetPort;
    this.client = HttpClient.newHttpClient();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.exchanges = Collections.synchronizedList(new ArrayList<>());
    server.createContext("/", this::forward);
    server.start();
  }

  static CapturingHttpProxy start(int targetPort) throws IOException {
    return new CapturingHttpProxy(targetPort);
  }

  String catalogUri() {
    return String.format("http://127.0.0.1:%d/iceberg/", server.getAddress().getPort());
  }

  List<CapturedExchange> exchanges() {
    synchronized (exchanges) {
      return List.copyOf(exchanges);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void forward(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    URI requestUri = exchange.getRequestURI();
    URI target = URI.create(String.format("http://127.0.0.1:%d%s", targetPort, requestUri));

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target);
    copyRequestHeaders(exchange.getRequestHeaders(), requestBuilder);
    requestBuilder.method(
        exchange.getRequestMethod(),
        requestBody.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(requestBody));

    try {
      HttpResponse<byte[]> response =
          client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
      copyResponseHeaders(response, exchange.getResponseHeaders());
      byte[] responseBody = response.body();
      exchanges.add(
          new CapturedExchange(
              exchange.getRequestMethod(),
              requestUri.toString(),
              new String(requestBody, StandardCharsets.UTF_8),
              response.statusCode(),
              new String(responseBody, StandardCharsets.UTF_8)));
      exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
      exchange.getResponseBody().write(responseBody);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exchange.sendResponseHeaders(502, -1);
    } finally {
      exchange.close();
    }
  }

  private static void copyRequestHeaders(Headers headers, HttpRequest.Builder requestBuilder) {
    headers.forEach(
        (name, values) -> {
          if (!RESTRICTED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            values.forEach(value -> requestBuilder.header(name, value));
          }
        });
  }

  private static void copyResponseHeaders(HttpResponse<byte[]> response, Headers responseHeaders) {
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!RESTRICTED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> responseHeaders.add(name, value));
              }
            });
  }

  record CapturedExchange(
      String method, String uri, String requestBody, int statusCode, String responseBody) {}
}
