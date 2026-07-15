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
package org.apache.gravitino.iceberg.kms;

import static org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient.ENDPOINT_PROPERTY;
import static org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY;
import static org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient.TRANSIT_MOUNT_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestOpenBaoKeyManagementClient {

  private static final String TOKEN = "test-client-token";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private ProtocolStub stub;
  private Path tokenFile;

  @BeforeEach
  void setUp() throws IOException {
    stub = new ProtocolStub();
    tokenFile = tempDir.resolve("openbao-token");
    Files.writeString(tokenFile, TOKEN + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  @AfterEach
  void tearDown() {
    stub.close();
  }

  @Test
  void testWrapKeyUsesTransitProtocolAndPreservesBuffer() throws IOException {
    stub.respond(200, "{\"data\":{\"ciphertext\":\"vault:v1:wrapped\"}}");
    OpenBaoKeyManagementClient client = initializedClient(Map.of());
    ByteBuffer key = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 100});
    key.position(1);
    key.limit(4);

    ByteBuffer wrapped = client.wrapKey(key, "customer pii+v1");

    assertEquals("vault:v1:wrapped", StandardCharsets.UTF_8.decode(wrapped).toString());
    assertEquals(1, key.position());
    assertEquals(4, key.limit());
    assertEquals("/v1/transit/encrypt/customer%20pii%2Bv1", stub.request().rawPath);
    assertEquals(TOKEN, stub.request().token);
    JsonNode request = MAPPER.readTree(stub.request().body);
    assertEquals("AQID", request.path("plaintext").textValue());
    assertFalse(request.has("ciphertext"));
  }

  @Test
  void testUnwrapKeyUsesCustomTransitMount() throws IOException {
    stub.respond(200, "{\"data\":{\"plaintext\":\"AQIDBA==\"}}");
    OpenBaoKeyManagementClient client =
        initializedClient(Map.of(TRANSIT_MOUNT_PROPERTY, "team/transit"));
    ByteBuffer wrapped =
        ByteBuffer.wrap("!vault:v1:wrapped?".getBytes(StandardCharsets.UTF_8), 1, 16).slice();

    ByteBuffer plaintext = client.unwrapKey(wrapped, "customer-pii-v1");

    assertArrayEquals(new byte[] {1, 2, 3, 4}, remainingBytes(plaintext));
    assertEquals("/v1/team/transit/decrypt/customer-pii-v1", stub.request().rawPath);
    JsonNode request = MAPPER.readTree(stub.request().body);
    assertEquals("vault:v1:wrapped", request.path("ciphertext").textValue());
    assertFalse(request.has("plaintext"));
  }

  @Test
  void testDirectByteBufferAndTokenRotation() throws IOException {
    stub.respond(200, "{\"data\":{\"ciphertext\":\"vault:v1:wrapped\"}}");
    OpenBaoKeyManagementClient client = initializedClient(Map.of());
    ByteBuffer directKey = ByteBuffer.allocateDirect(3);
    directKey.put(new byte[] {1, 2, 3}).flip();

    client.wrapKey(directKey, "key");
    assertEquals(TOKEN, stub.request().token);
    assertEquals("AQID", MAPPER.readTree(stub.request().body).path("plaintext").textValue());

    Files.writeString(tokenFile, "rotated-client-token", StandardCharsets.UTF_8);
    client.wrapKey(directKey, "key");
    assertEquals("rotated-client-token", stub.request().token);
    assertEquals(0, directKey.position());
  }

  @Test
  void testClientCanBeSerializedWithoutSerializingToken() throws Exception {
    stub.respond(200, "{\"data\":{\"ciphertext\":\"vault:v1:wrapped\"}}");
    OpenBaoKeyManagementClient client = initializedClient(Map.of());

    byte[] serialized;
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(client);
      serialized = bytes.toByteArray();
    }

    assertFalse(new String(serialized, StandardCharsets.ISO_8859_1).contains(TOKEN));

    OpenBaoKeyManagementClient copy;
    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
      copy = (OpenBaoKeyManagementClient) input.readObject();
    }

    assertEquals(
        "vault:v1:wrapped",
        StandardCharsets.UTF_8
            .decode(copy.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .toString());
  }

  @Test
  void testAuthenticationAndServiceErrorsAreRedacted() {
    stub.respond(403, "{\"errors\":[\"sensitive server detail\"]}");
    OpenBaoKeyManagementClient client = initializedClient(Map.of());

    SecurityException forbidden =
        assertThrows(
            SecurityException.class, () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"));
    assertFalse(forbidden.getMessage().contains("sensitive"));
    assertFalse(forbidden.getMessage().contains(TOKEN));

    stub.respond(503, "{\"errors\":[\"another sensitive detail\"]}");
    IllegalStateException unavailable =
        assertThrows(
            IllegalStateException.class,
            () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"));
    assertEquals("OpenBao KMS is unavailable", unavailable.getMessage());
  }

  @Test
  void testMalformedAndOversizedResponsesAreRejected() {
    OpenBaoKeyManagementClient client = initializedClient(Map.of());

    stub.respond(200, "{\"data\":{}}");
    assertEquals(
        "OpenBao KMS returned a malformed response",
        assertThrows(
                IllegalStateException.class,
                () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .getMessage());

    stub.respond(200, "x".repeat(1024 * 1024 + 1));
    assertEquals(
        "OpenBao KMS response exceeded the allowed size",
        assertThrows(
                IllegalStateException.class,
                () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .getMessage());
  }

  @Test
  void testInvalidPlaintextAndWrappedKeyAreRejected() {
    stub.respond(200, "{\"data\":{\"plaintext\":\"not base64!\"}}");
    OpenBaoKeyManagementClient client = initializedClient(Map.of());
    assertEquals(
        "OpenBao KMS returned a malformed response",
        assertThrows(
                IllegalStateException.class,
                () ->
                    client.unwrapKey(
                        ByteBuffer.wrap("vault:v1:value".getBytes(StandardCharsets.UTF_8)), "key"))
            .getMessage());

    assertThrows(
        IllegalArgumentException.class,
        () -> client.unwrapKey(ByteBuffer.wrap(new byte[] {(byte) 0xC3, 0x28}), "key"));
  }

  @Test
  void testConfigurationValidationAndTokenFileHandling() throws IOException {
    OpenBaoKeyManagementClient client = new OpenBaoKeyManagementClient();

    assertThrows(IllegalStateException.class, () -> client.wrapKey(ByteBuffer.allocate(1), "key"));
    assertThrows(IllegalArgumentException.class, () -> client.initialize(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.initialize(
                Map.of(
                    ENDPOINT_PROPERTY,
                    "ftp://openbao",
                    TOKEN_FILE_PROPERTY,
                    tokenFile.toString())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.initialize(
                Map.of(ENDPOINT_PROPERTY, stub.endpoint(), TOKEN_FILE_PROPERTY, "relative-token")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.initialize(
                Map.of(
                    ENDPOINT_PROPERTY,
                    stub.endpoint(),
                    TOKEN_FILE_PROPERTY,
                    tokenFile.toString(),
                    TRANSIT_MOUNT_PROPERTY,
                    "/transit")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.initialize(
                Map.of(
                    ENDPOINT_PROPERTY,
                    stub.endpoint(),
                    TOKEN_FILE_PROPERTY,
                    tokenFile.toString(),
                    TRANSIT_MOUNT_PROPERTY,
                    "team/../transit")));

    client.initialize(
        Map.of(ENDPOINT_PROPERTY, stub.endpoint(), TOKEN_FILE_PROPERTY, tokenFile.toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "team/key"));

    Files.writeString(tokenFile, "  ", StandardCharsets.UTF_8);
    client.initialize(
        Map.of(ENDPOINT_PROPERTY, stub.endpoint(), TOKEN_FILE_PROPERTY, tokenFile.toString()));
    assertEquals(
        "OpenBao KMS token file is empty",
        assertThrows(
                IllegalStateException.class,
                () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .getMessage());

    Files.writeString(tokenFile, "x".repeat(16 * 1024 + 1), StandardCharsets.UTF_8);
    assertEquals(
        "OpenBao KMS token file exceeded the allowed size",
        assertThrows(
                IllegalStateException.class,
                () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .getMessage());
  }

  @Test
  void testOnlySuccessStatusesAreAccepted() {
    OpenBaoKeyManagementClient client = initializedClient(Map.of());

    stub.respond(404, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "missing-key"));

    stub.respond(302, "{}");
    assertEquals(
        "OpenBao KMS returned an unexpected status",
        assertThrows(
                IllegalStateException.class,
                () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1}), "key"))
            .getMessage());
  }

  private OpenBaoKeyManagementClient initializedClient(Map<String, String> overrides) {
    OpenBaoKeyManagementClient client = new OpenBaoKeyManagementClient();
    Map<String, String> properties =
        new HashMap<>(
            Map.of(ENDPOINT_PROPERTY, stub.endpoint(), TOKEN_FILE_PROPERTY, tokenFile.toString()));
    properties.putAll(overrides);
    client.initialize(properties);
    return client;
  }

  private static byte[] remainingBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  private static final class ProtocolStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicReference<RecordedRequest> request = new AtomicReference<>();
    private final AtomicReference<Response> response =
        new AtomicReference<>(new Response(500, "{}"));

    private ProtocolStub() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      executor = Executors.newCachedThreadPool();
      server.setExecutor(executor);
      server.start();
    }

    private String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private RecordedRequest request() {
      return request.get();
    }

    private void respond(int status, String body) {
      response.set(new Response(status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        request.set(
            new RecordedRequest(
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("X-Vault-Token"),
                exchange.getRequestBody().readAllBytes()));
        Response configuredResponse = response.get();
        byte[] body = configuredResponse.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(configuredResponse.status, body.length);
        exchange.getResponseBody().write(body);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static final class RecordedRequest {
    private final String rawPath;
    private final String token;
    private final byte[] body;

    private RecordedRequest(String rawPath, String token, byte[] body) {
      this.rawPath = rawPath;
      this.token = token;
      this.body = body;
    }
  }

  private static final class Response {
    private final int status;
    private final String body;

    private Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }
}
