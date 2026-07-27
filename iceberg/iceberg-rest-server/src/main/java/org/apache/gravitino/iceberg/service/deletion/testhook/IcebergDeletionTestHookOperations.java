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
package org.apache.gravitino.iceberg.service.deletion.testhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.iceberg.service.IcebergRESTUtils;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionNamespaceCodec;
import org.apache.gravitino.iceberg.service.deletion.testhook.IcebergDeletionTestHookController.Fault;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;

/** Authenticated, production-disabled fault controls for Iceberg deletion integration tests. */
@Path("/test/v1/deletion")
@Produces(MediaType.APPLICATION_JSON)
public final class IcebergDeletionTestHookOperations {

  /** Authentication header understood only by this explicitly enabled test resource. */
  public static final String TOKEN_HEADER = "X-Gravitino-Test-Hook-Token";

  private static final String PURGE_OPERATION = "ICEBERG_REST_PURGE";
  private static final String REDRIVE_ACTOR = "iceberg-deletion-test-hook";

  private final IcebergDeletionTestHookController controller;
  private final ToLongFunction<String> catalogIdResolver;
  private final BiFunction<String, String, Integer> cleanupRunner;
  private final byte[] expectedToken;

  /**
   * Creates the resource. The enclosing service registers it only when the hook is enabled.
   *
   * @param controller in-memory fault rules
   * @param catalogIdResolver resolver for immutable catalog identity
   * @param cleanupRunner bounded durable redrive plus normal worker turn
   * @param token nonblank shared integration-test token
   */
  public IcebergDeletionTestHookOperations(
      IcebergDeletionTestHookController controller,
      ToLongFunction<String> catalogIdResolver,
      BiFunction<String, String, Integer> cleanupRunner,
      String token) {
    this.controller = Objects.requireNonNull(controller, "controller must not be null");
    this.catalogIdResolver =
        Objects.requireNonNull(catalogIdResolver, "catalogIdResolver must not be null");
    this.cleanupRunner = Objects.requireNonNull(cleanupRunner, "cleanupRunner must not be null");
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Deletion test-hook token must not be blank");
    }
    this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
  }

  /** Installs one deterministic table-scoped physical-deletion fault. */
  @POST
  @Path("faults")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response installFault(
      @HeaderParam(TOKEN_HEADER) String token, Map<String, Object> request) {
    if (!authenticated(token)) {
      return unauthorized();
    }
    try {
      String catalog = requiredString(request, "catalog");
      String namespace = namespaceSnapshot(requiredString(request, "namespace"));
      String table = requiredString(request, "table");
      if (!PURGE_OPERATION.equals(requiredString(request, "operation"))) {
        return badRequest();
      }
      Fault fault = Fault.valueOf(requiredString(request, "fault"));
      int afterSuccessfulTargets = requiredNonnegativeInteger(request, "afterSuccessfulTargets");
      controller.install(
          catalogIdResolver.applyAsLong(catalog), namespace, table, fault, afterSuccessfulTargets);
      return Response.noContent().build();
    } catch (IllegalArgumentException | NullPointerException e) {
      return badRequest();
    }
  }

  /** Idempotently clears one table-scoped physical-deletion fault. */
  @DELETE
  @Path("faults")
  public Response clearFault(
      @HeaderParam(TOKEN_HEADER) String token,
      @QueryParam("catalog") String catalog,
      @QueryParam("namespace") String namespace,
      @QueryParam("table") String table) {
    if (!authenticated(token)) {
      return unauthorized();
    }
    try {
      controller.clear(
          catalogIdResolver.applyAsLong(requiredString(catalog)),
          namespaceSnapshot(requiredString(namespace)),
          requiredString(table));
      return Response.noContent().build();
    } catch (IllegalArgumentException | NullPointerException e) {
      return badRequest();
    }
  }

  /** Performs one bounded manual redrive transaction followed by the normal bounded worker turn. */
  @POST
  @Path("cleanup/run-once")
  public Response runCleanupOnce(@HeaderParam(TOKEN_HEADER) String token) {
    if (!authenticated(token)) {
      return unauthorized();
    }
    try {
      int redriven = cleanupRunner.apply(REDRIVE_ACTOR, UUID.randomUUID().toString());
      return Response.ok(Collections.singletonMap("redrivenActions", redriven)).build();
    } catch (RuntimeException e) {
      return Response.serverError()
          .entity(Collections.singletonMap("error", "Test-hook cleanup trigger failed"))
          .build();
    }
  }

  private boolean authenticated(String candidate) {
    byte[] supplied = candidate == null ? new byte[0] : candidate.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedToken, supplied);
  }

  private static String namespaceSnapshot(String namespace) {
    Namespace decoded =
        RESTUtil.decodeNamespace(namespace, IcebergRESTUtils.NAMESPACE_SEPARATOR_URLENCODED_UTF_8);
    return IcebergDeletionNamespaceCodec.encode(decoded.levels());
  }

  private static String requiredString(Map<String, Object> request, String field) {
    if (request == null) {
      throw new IllegalArgumentException("Missing request");
    }
    Object value = request.get(field);
    if (!(value instanceof String)) {
      throw new IllegalArgumentException("Invalid field");
    }
    return requiredString((String) value);
  }

  private static String requiredString(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Value must not be blank");
    }
    return value;
  }

  private static int requiredNonnegativeInteger(Map<String, Object> request, String field) {
    if (request == null || !(request.get(field) instanceof Number)) {
      throw new IllegalArgumentException("Invalid field");
    }
    Number value = (Number) request.get(field);
    int result = value.intValue();
    if (result < 0 || value.longValue() != result) {
      throw new IllegalArgumentException("Invalid integer field");
    }
    return result;
  }

  private static Response unauthorized() {
    return Response.status(Response.Status.UNAUTHORIZED)
        .entity(Collections.singletonMap("error", "Unauthorized test-hook request"))
        .build();
  }

  private static Response badRequest() {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Collections.singletonMap("error", "Invalid test-hook request"))
        .build();
  }
}
