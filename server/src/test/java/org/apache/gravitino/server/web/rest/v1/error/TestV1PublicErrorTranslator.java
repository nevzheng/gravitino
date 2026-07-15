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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.ws.rs.core.Response;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.apache.gravitino.rest.v1.error.V1ResourceInfoErrorDetail;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for the V1 public error boundary. */
public class TestV1PublicErrorTranslator {

  @AfterEach
  public void cleanup() {
    RequestContext.clear();
  }

  @Test
  public void testMapsMissingTableToStablePublicError() {
    RequestContext.setRequestId("request-404");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new NoSuchTableException("internal table details"),
            V1ErrorContext.tableRead("metalakes/demo/catalogs/c/schemas/s/tables/t"));

    assertEquals(404, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals(404, body.getError().getCode());
    assertEquals("TABLE_NOT_FOUND", body.getError().getType());
    assertEquals("The requested table was not found.", body.getError().getMessage());
    assertFalse(body.getError().isRetryable());
    assertEquals("request-404", body.getError().getRequestId());
    assertNotNull(body.getError().getDetails());
    assertEquals("RESOURCE_INFO", body.getError().getDetails().get(0).getKind());
    V1ResourceInfoErrorDetail detail =
        (V1ResourceInfoErrorDetail) body.getError().getDetails().get(0);
    assertEquals("TABLE", detail.getResourceType());
    assertEquals("metalakes/demo/catalogs/c/schemas/s/tables/t", detail.getResourceName());
    assertEquals("no-store", response.getHeaderString("Cache-Control"));
  }

  @Test
  public void testMapsMissingCatalogToItsHierarchyLevel() {
    RequestContext.setRequestId("request-catalog-404");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new NoSuchCatalogException("internal catalog details"),
            V1ErrorContext.tableRead("demo", "catalog", "schema", "table"));

    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    V1ResourceInfoErrorDetail detail =
        (V1ResourceInfoErrorDetail) body.getError().getDetails().get(0);
    assertEquals("CATALOG_NOT_FOUND", body.getError().getType());
    assertEquals("CATALOG", detail.getResourceType());
    assertEquals("metalakes/demo/catalogs/catalog", detail.getResourceName());
  }

  @Test
  public void testMapsTransientCatalogConnectionFailureAsRetryableForGet() {
    RequestContext.setRequestId("request-502");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new ConnectionFailedException("credentials must not leak"),
            V1ErrorContext.tableRead("metalakes/demo/catalogs/c/schemas/s/tables/t"));

    assertEquals(502, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("UPSTREAM_CONNECTION_FAILED", body.getError().getType());
    assertTrue(body.getError().isRetryable());
    assertNull(body.getError().getRetryAfterSeconds());
    assertFalse(body.getError().getMessage().contains("credentials"));
  }

  @Test
  public void testSanitizesUnknownFailures() {
    RequestContext.setRequestId("request-500");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new IllegalStateException("sensitive catalog password"), V1ErrorContext.empty());

    assertEquals(500, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
    assertFalse(body.getError().isRetryable());
    assertFalse(body.getError().getMessage().contains("password"));
    assertTrue(body.getError().getDetails().isEmpty());
  }

  @Test
  public void testInternalMapperValidationFailureIsNotBlamedOnTheClient() {
    RequestContext.setRequestId("request-mapper-500");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new IllegalArgumentException("unsupported internal expression"),
            V1ErrorContext.tableRead("demo", "catalog", "schema", "table"));

    assertEquals(500, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
    assertFalse(body.getError().getMessage().contains("expression"));
  }

  @Test
  public void testMapsFrameworkNotAcceptableResponse() {
    RequestContext.setRequestId("request-406");

    Response response = V1PublicErrorTranslator.toResponseForStatus(406);
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();

    assertEquals(406, response.getStatus());
    assertEquals(406, body.getError().getCode());
    assertEquals("NOT_ACCEPTABLE", body.getError().getType());
    assertFalse(body.getError().isRetryable());
    assertEquals("request-406", body.getError().getRequestId());
  }

  @Test
  public void testMapsUnauthorizedFailureWithEveryChallenge() {
    RequestContext.setRequestId("request-401");
    UnauthorizedException exception = new UnauthorizedException("credential internals", "Bearer");
    exception.getChallenges().add("Basic realm=\"Gravitino\"");

    Response response = V1PublicErrorTranslator.toResponse(exception, V1ErrorContext.empty());

    assertEquals(401, response.getStatus());
    assertEquals(
        List.of("Bearer", "Basic realm=\"Gravitino\""),
        response.getStringHeaders().get(AuthConstants.HTTP_CHALLENGE_HEADER));
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("UNAUTHENTICATED", body.getError().getType());
    assertFalse(body.getError().getMessage().contains("credential internals"));
  }

  @Test
  public void testV1RouteExceptionMapperPreservesAuthenticationChallenge() {
    Response response =
        new V1PublicExceptionMapper()
            .toResponse(
                new V1ApiException(
                    new UnauthorizedException("credential internals", "Bearer"),
                    V1ErrorContext.empty()));

    assertEquals(401, response.getStatus());
    assertEquals(
        List.of("Bearer"), response.getStringHeaders().get(AuthConstants.HTTP_CHALLENGE_HEADER));
  }

  @Test
  public void testUnauthorizedFailureWithoutChallengeFailsClosed() {
    RequestContext.setRequestId("request-401-missing-challenge");

    Response response =
        V1PublicErrorTranslator.toResponse(
            new UnauthorizedException("credential internals"), V1ErrorContext.empty());

    assertEquals(500, response.getStatus());
    assertNull(response.getStringHeaders().get(AuthConstants.HTTP_CHALLENGE_HEADER));
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
    assertFalse(body.getError().getMessage().contains("credential internals"));
  }

  @Test
  public void testUnauthorizedFailureWithAnInvalidChallengeFailsClosed() {
    Response response =
        V1PublicErrorTranslator.toResponse(
            new UnauthorizedException("credential internals", "Bearer\r\nX-Injected: true"),
            V1ErrorContext.empty());

    assertEquals(500, response.getStatus());
    assertNull(response.getStringHeaders().get(AuthConstants.HTTP_CHALLENGE_HEADER));
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
  }

  @Test
  public void testFrameworkUnauthorizedFailureWithoutChallengeFailsClosed() {
    Response response = V1PublicErrorTranslator.toResponseForStatus(401);

    assertEquals(500, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals("INTERNAL_ERROR", body.getError().getType());
  }

  @Test
  public void testUndocumentedFrameworkStatusIsCollapsedToTheDocumentedInternalError() {
    RequestContext.setRequestId("request-framework-503");

    Response response = V1PublicErrorTranslator.toResponseForStatus(503);

    assertEquals(500, response.getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getEntity();
    assertEquals(500, body.getError().getCode());
    assertEquals("INTERNAL_ERROR", body.getError().getType());
  }
}
