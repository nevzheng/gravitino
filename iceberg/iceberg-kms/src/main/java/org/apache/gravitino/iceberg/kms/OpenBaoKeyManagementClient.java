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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.iceberg.encryption.KeyManagementClient;

/** An Iceberg key management client backed by the OpenBao Transit secrets engine. */
public final class OpenBaoKeyManagementClient implements KeyManagementClient {

  /** Catalog property containing the base URI of the OpenBao server. */
  public static final String ENDPOINT_PROPERTY = "encryption.kms.openbao.endpoint";

  /** Catalog property containing the local path to the OpenBao client token. */
  public static final String TOKEN_FILE_PROPERTY = "encryption.kms.openbao.token-file";

  /** Catalog property selecting the OpenBao Transit mount path. */
  public static final String TRANSIT_MOUNT_PROPERTY = "encryption.kms.openbao.transit-mount";

  /** Default OpenBao Transit mount path. */
  public static final String DEFAULT_TRANSIT_MOUNT = "transit";

  private static final long serialVersionUID = 1L;
  private static final String TOKEN_HEADER = "X-Vault-Token";
  private static final int MAX_TOKEN_BYTES = 16 * 1024;
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int REQUEST_TIMEOUT_MILLIS = 10_000;

  @Nullable private String endpoint;
  @Nullable private String tokenFile;
  private String transitMount = DEFAULT_TRANSIT_MOUNT;

  @Nullable private transient volatile ObjectMapper objectMapper;

  /** Creates an uninitialized OpenBao key management client. */
  public OpenBaoKeyManagementClient() {}

  /** {@inheritDoc} */
  @Override
  public ByteBuffer wrapKey(ByteBuffer key, String wrappingKeyId) {
    Objects.requireNonNull(key, "Key to wrap must not be null");
    validateKeyId(wrappingKeyId);

    byte[] plaintext = remainingBytes(key);
    String encodedPlaintext = Base64.getEncoder().encodeToString(plaintext);
    byte[] requestBody = writeRequest(Collections.singletonMap("plaintext", encodedPlaintext));
    byte[] responseBody = post("encrypt", wrappingKeyId, requestBody);
    String ciphertext = requiredResponseField(responseBody, "ciphertext");
    return ByteBuffer.wrap(ciphertext.getBytes(StandardCharsets.UTF_8));
  }

  /** {@inheritDoc} */
  @Override
  public ByteBuffer unwrapKey(ByteBuffer wrappedKey, String wrappingKeyId) {
    Objects.requireNonNull(wrappedKey, "Wrapped key must not be null");
    validateKeyId(wrappingKeyId);

    String ciphertext = decodeUtf8(wrappedKey);
    byte[] requestBody = writeRequest(Collections.singletonMap("ciphertext", ciphertext));
    byte[] responseBody = post("decrypt", wrappingKeyId, requestBody);
    String encodedPlaintext = requiredResponseField(responseBody, "plaintext");
    try {
      return ByteBuffer.wrap(Base64.getDecoder().decode(encodedPlaintext));
    } catch (IllegalArgumentException e) {
      throw malformedResponse(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void initialize(Map<String, String> properties) {
    Objects.requireNonNull(properties, "KMS properties must not be null");

    String configuredEndpoint = requireProperty(properties, ENDPOINT_PROPERTY);
    String configuredTokenFile = requireProperty(properties, TOKEN_FILE_PROPERTY);
    String configuredTransitMount =
        properties.getOrDefault(TRANSIT_MOUNT_PROPERTY, DEFAULT_TRANSIT_MOUNT).trim();

    validateEndpoint(configuredEndpoint);
    validateTokenFile(configuredTokenFile);
    validateTransitMount(configuredTransitMount);

    this.endpoint = stripTrailingSlash(configuredEndpoint.trim());
    this.tokenFile = configuredTokenFile.trim();
    this.transitMount = configuredTransitMount;
    this.objectMapper = null;
  }

  /** {@inheritDoc} */
  @Override
  public void close() {
    objectMapper = null;
  }

  private byte[] post(String operation, String wrappingKeyId, byte[] requestBody) {
    HttpURLConnection connection = openConnection(operationUri(operation, wrappingKeyId));
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
      connection.setReadTimeout(REQUEST_TIMEOUT_MILLIS);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty(TOKEN_HEADER, readToken());
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(requestBody.length);

      try (OutputStream output = connection.getOutputStream()) {
        output.write(requestBody);
      }

      int statusCode = connection.getResponseCode();
      byte[] responseBody = readResponse(connection, statusCode);
      checkStatus(statusCode);
      return responseBody;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to contact OpenBao KMS", e);
    } finally {
      connection.disconnect();
    }
  }

  private URI operationUri(String operation, String wrappingKeyId) {
    String configuredEndpoint = requireInitialized(endpoint);
    String encodedMount =
        Arrays.stream(transitMount.split("/"))
            .map(OpenBaoKeyManagementClient::encodePathSegment)
            .collect(Collectors.joining("/"));
    String encodedKeyId = encodePathSegment(wrappingKeyId);
    return URI.create(
        String.format("%s/v1/%s/%s/%s", configuredEndpoint, encodedMount, operation, encodedKeyId));
  }

  private String readToken() {
    Path path = Paths.get(requireInitialized(tokenFile));
    String token;
    try {
      if (Files.size(path) > MAX_TOKEN_BYTES) {
        throw new IllegalStateException("OpenBao KMS token file exceeded the allowed size");
      }
      byte[] tokenBytes = Files.readAllBytes(path);
      if (tokenBytes.length > MAX_TOKEN_BYTES) {
        throw new IllegalStateException("OpenBao KMS token file exceeded the allowed size");
      }
      token = new String(tokenBytes, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the OpenBao KMS token file", e);
    }

    if (token.isEmpty()) {
      throw new IllegalStateException("OpenBao KMS token file is empty");
    }
    return token;
  }

  private byte[] writeRequest(Map<String, String> request) {
    try {
      return mapper().writeValueAsBytes(request);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to encode an OpenBao KMS request", e);
    }
  }

  private String requiredResponseField(byte[] responseBody, String fieldName) {
    try {
      JsonNode root = mapper().readTree(responseBody);
      JsonNode field = root.path("data").path(fieldName);
      if (!field.isTextual() || field.textValue().isEmpty()) {
        throw malformedResponse(null);
      }
      return field.textValue();
    } catch (IOException e) {
      throw malformedResponse(e);
    }
  }

  private ObjectMapper mapper() {
    ObjectMapper current = objectMapper;
    if (current == null) {
      synchronized (this) {
        current = objectMapper;
        if (current == null) {
          current = new ObjectMapper();
          objectMapper = current;
        }
      }
    }
    return current;
  }

  private static void checkStatus(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return;
    }
    if (statusCode == 401 || statusCode == 403) {
      throw new SecurityException("OpenBao KMS denied the key operation");
    }
    if (statusCode == 400 || statusCode == 404) {
      throw new IllegalArgumentException("OpenBao KMS rejected the key operation");
    }
    if (statusCode == 429 || statusCode >= 500) {
      throw new IllegalStateException("OpenBao KMS is unavailable");
    }
    throw new IllegalStateException("OpenBao KMS returned an unexpected status");
  }

  private static String requireProperty(Map<String, String> properties, String property) {
    String value = properties.get(property);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing required KMS property: " + property);
    }
    return value;
  }

  private static void validateEndpoint(String configuredEndpoint) {
    URI uri;
    try {
      uri = URI.create(configuredEndpoint.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid OpenBao KMS endpoint", e);
    }

    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("OpenBao KMS endpoint must be an HTTP(S) server base URI");
    }
  }

  private static void validateTokenFile(String configuredTokenFile) {
    Path path;
    try {
      path = Paths.get(configuredTokenFile.trim());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid OpenBao KMS token file path", e);
    }
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("OpenBao KMS token file path must be absolute");
    }
  }

  private static void validateTransitMount(String configuredTransitMount) {
    if (configuredTransitMount.isEmpty()
        || configuredTransitMount.startsWith("/")
        || configuredTransitMount.endsWith("/")
        || Arrays.stream(configuredTransitMount.split("/", -1))
            .anyMatch(OpenBaoKeyManagementClient::isInvalidPathSegment)) {
      throw new IllegalArgumentException("Invalid OpenBao Transit mount path");
    }
  }

  private static void validateKeyId(String wrappingKeyId) {
    if (wrappingKeyId == null
        || wrappingKeyId.trim().isEmpty()
        || wrappingKeyId.contains("/")
        || wrappingKeyId.contains("\\")
        || isInvalidPathSegment(wrappingKeyId)) {
      throw new IllegalArgumentException("Invalid OpenBao KMS key ID");
    }
  }

  private static boolean isInvalidPathSegment(String value) {
    return value.isEmpty() || ".".equals(value) || "..".equals(value);
  }

  private static byte[] remainingBytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

  private static String decodeUtf8(ByteBuffer buffer) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(buffer.duplicate())
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Wrapped OpenBao KMS key is not valid UTF-8", e);
    }
  }

  private static String encodePathSegment(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to encode an OpenBao KMS path", e);
    }
  }

  private static HttpURLConnection openConnection(URI uri) {
    URLConnection connection;
    try {
      connection = uri.toURL().openConnection();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to connect to OpenBao KMS", e);
    }
    if (!(connection instanceof HttpURLConnection)) {
      throw new IllegalStateException("OpenBao KMS endpoint is not HTTP(S)");
    }
    return (HttpURLConnection) connection;
  }

  private static byte[] readResponse(HttpURLConnection connection, int statusCode)
      throws IOException {
    InputStream response =
        statusCode >= 200 && statusCode < 400
            ? connection.getInputStream()
            : connection.getErrorStream();
    if (response == null) {
      return new byte[0];
    }

    try (InputStream input = response;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int remaining = MAX_RESPONSE_BYTES + 1;
      while (remaining > 0) {
        int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
        if (read < 0) {
          break;
        }
        output.write(buffer, 0, read);
        remaining -= read;
      }
      if (output.size() > MAX_RESPONSE_BYTES) {
        throw new IllegalStateException("OpenBao KMS response exceeded the allowed size");
      }
      return output.toByteArray();
    }
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String requireInitialized(@Nullable String value) {
    if (value == null) {
      throw new IllegalStateException("OpenBao KMS client has not been initialized");
    }
    return value;
  }

  private static IllegalStateException malformedResponse(@Nullable Exception cause) {
    return new IllegalStateException("OpenBao KMS returned a malformed response", cause);
  }
}
