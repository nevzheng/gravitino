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
package org.apache.gravitino.server.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.TableOperationsV1;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorResponseFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1MediaTypeFilter;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicExceptionMapper;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Test;

/** End-to-end Jersey tests for the route-versioned V1 table-read slice. */
public class TestTableOperationsV1 extends BaseOperationsTest {

  private final TableDispatcher dispatcher = mock(TableDispatcher.class);

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(3001, 4000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(TableOperationsV1.class);
    resourceConfig.register(V1PublicExceptionMapper.class);
    resourceConfig.register(V1ErrorResponseFilter.class);
    resourceConfig.register(V1MediaTypeFilter.class);
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(JacksonFeature.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(dispatcher).to(TableDispatcher.class).ranked(1);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testGetHeadAndConditionalGetUseThePublicV1Representation() {
    Table table = mock(Table.class);
    when(table.name()).thenReturn("orders");
    when(dispatcher.loadTable(any())).thenReturn(table);

    Response getResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).get();

    assertEquals(200, getResponse.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, getResponse.getMediaType());
    String entityTag = getResponse.getHeaderString(HttpHeaders.ETAG);
    assertNotNull(entityTag);
    assertTrue(entityTag.startsWith("W/\""));
    assertEquals("private, no-cache", getResponse.getHeaderString(HttpHeaders.CACHE_CONTROL));
    assertEquals(
        "Authorization, Accept, Accept-Encoding", getResponse.getHeaderString(HttpHeaders.VARY));
    assertTrue(getResponse.readEntity(String.class).contains("\"resourceName\""));

    Response conditionalResponse =
        tableTarget()
            .request(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.IF_NONE_MATCH, entityTag)
            .get();
    assertEquals(304, conditionalResponse.getStatus());
    assertFalse(conditionalResponse.hasEntity());
    assertEquals(entityTag, conditionalResponse.getHeaderString(HttpHeaders.ETAG));

    Response headResponse = tableTarget().request(MediaType.APPLICATION_JSON_TYPE).head();
    assertEquals(200, headResponse.getStatus());
    assertFalse(headResponse.hasEntity());
    assertEquals(entityTag, headResponse.getHeaderString(HttpHeaders.ETAG));
  }

  @Test
  public void testRouteRejectsUnexpectedQueryParametersWithPublicError() {
    Response response = tableTarget().queryParam("privileges", "MODIFY_TABLE").request().get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"query\""));
    assertFalse(body.contains("\"details\":[]"));
  }

  @Test
  public void testRouteRejectsNonconformingPathSegmentsWithFieldViolation() {
    Response response =
        target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables")
            .path("a".repeat(256))
            .request()
            .get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"table\""));
  }

  @Test
  public void testRouteRejectsInvalidIfNoneMatchWithFieldViolation() {
    Response response =
        tableTarget().request().header(HttpHeaders.IF_NONE_MATCH, "not-an-entity-tag").get();

    assertEquals(400, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"INVALID_ARGUMENT\""));
    assertTrue(body.contains("\"kind\":\"FIELD_VIOLATION\""));
    assertTrue(body.contains("\"field\":\"If-None-Match\""));
  }

  @Test
  public void testRouteTranslatesMissingTableWithoutLeakingInternalMessage() {
    doThrow(new NoSuchTableException("internal catalog topology"))
        .when(dispatcher)
        .loadTable(any());

    Response response = tableTarget().request().get();

    assertEquals(404, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"TABLE_NOT_FOUND\""));
    assertTrue(body.contains("\"resourceType\":\"TABLE\""));
    assertFalse(body.contains("topology"));

    Response headResponse = tableTarget().request().head();
    assertEquals(404, headResponse.getStatus());
    assertFalse(headResponse.hasEntity());
  }

  @Test
  public void testV1RouteRejectsLegacyVendorMediaNegotiation() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/vnd.gravitino.v1+json")
            .get();

    assertEquals(406, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"NOT_ACCEPTABLE\""));
  }

  @Test
  public void testV1RouteRejectsMixedLegacyAndJsonMediaNegotiation() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/json, application/vnd.gravitino.v1+json")
            .get();

    assertEquals(406, response.getStatus());
  }

  @Test
  public void testV1HeadMediaNegotiationFailureDoesNotWriteAnErrorBody() {
    Response response =
        tableTarget()
            .request()
            .header(HttpHeaders.ACCEPT, "application/vnd.gravitino.v1+json")
            .head();

    assertEquals(406, response.getStatus());
    assertFalse(response.hasEntity());
  }

  @Test
  public void testFrameworkNegotiationFailureUsesThePublicErrorEnvelope() {
    Response response = tableTarget().request(MediaType.APPLICATION_XML_TYPE).get();

    assertEquals(406, response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"NOT_ACCEPTABLE\""));
    assertTrue(body.contains("\"details\":[]"));
  }

  @Test
  public void testMethodNotAllowedUsesThePublicErrorAndAllowHeader() {
    Response response = tableTarget().request().post(Entity.json("{}"));

    assertEquals(405, response.getStatus());
    Set<String> allowedMethods =
        Arrays.stream(response.getHeaderString(HttpHeaders.ALLOW).split(","))
            .map(String::trim)
            .collect(Collectors.toSet());
    assertEquals(Set.of("GET", "HEAD", "OPTIONS"), allowedMethods);
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"type\":\"METHOD_NOT_ALLOWED\""));
    assertTrue(body.contains("\"details\":[]"));
  }

  private WebTarget tableTarget() {
    return target("v1/metalakes/demo/catalogs/catalog/schemas/schema/tables/orders");
  }

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      return mock(HttpServletRequest.class);
    }
  }
}
