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
package org.apache.gravitino.server.web.rest.v1.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.rest.v1.error.V1ErrorResponse;
import org.apache.gravitino.rest.v1.error.V1FieldViolationErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests V1 strict-body validation independent of Jersey resource-method binding. */
public class TestV1JsonBodyFilter {

  private final V1JsonBodyFilter filter = new V1JsonBodyFilter();

  @Test
  public void testPreservesValidBodyForNormalJerseyBinding() throws Exception {
    String body = "{\"name\":\"demo\",\"properties\":{\"owner\":\"qa\"}}";
    ContainerRequestContext request = request("POST", "/api/v1/metalakes", body);

    filter.filter(request);

    verify(request, never()).abortWith(any(Response.class));
    ArgumentCaptor<InputStream> entityStream = ArgumentCaptor.forClass(InputStream.class);
    verify(request).setEntityStream(entityStream.capture());
    assertEquals(body, readUtf8(entityStream.getValue()));
  }

  @Test
  public void testRejectsInvalidBodyWithThePublicV1Envelope() throws Exception {
    ContainerRequestContext request =
        request(
            "POST",
            "/api/v1/metalakes/demo/catalogs",
            "{\"name\":\"lakehouse\",\"type\":\"relational\","
                + "\"provider\":\"hive\",\"properties\":{}}");

    filter.filter(request);

    ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
    verify(request).abortWith(response.capture());
    assertEquals(400, response.getValue().getStatus());
    V1ErrorResponse body = (V1ErrorResponse) response.getValue().getEntity();
    assertEquals("INVALID_ARGUMENT", body.getError().getType());
    V1FieldViolationErrorDetail detail =
        (V1FieldViolationErrorDetail) body.getError().getDetails().get(0);
    assertEquals("body", detail.getField());
  }

  @Test
  public void testLeavesLegacyAndReadRequestsUntouched() throws Exception {
    ContainerRequestContext legacyRequest = request("POST", "/api/metalakes", "{}");
    ContainerRequestContext readRequest = request("GET", "/api/v1/metalakes", "{}");

    filter.filter(legacyRequest);
    filter.filter(readRequest);

    verify(legacyRequest, never()).getEntityStream();
    verify(legacyRequest, never()).setEntityStream(any(InputStream.class));
    verify(readRequest, never()).getEntityStream();
    verify(readRequest, never()).setEntityStream(any(InputStream.class));
  }

  private static ContainerRequestContext request(String method, String path, String body) {
    ContainerRequestContext request = mock(ContainerRequestContext.class);
    UriInfo uriInfo = mock(UriInfo.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
    when(request.hasEntity()).thenReturn(true);
    when(request.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
    when(request.getEntityStream())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return request;
  }

  private static String readUtf8(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }
}
