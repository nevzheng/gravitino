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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.MediaType;
import org.apache.gravitino.rest.v1.model.CatalogCreateRequest;
import org.apache.gravitino.rest.v1.model.CatalogUpdateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeCreateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeUpdateRequest;
import org.apache.gravitino.rest.v1.model.SchemaCreateRequest;
import org.apache.gravitino.rest.v1.model.SchemaUpdateRequest;
import org.apache.gravitino.rest.v1.model.TableCreateRequest;
import org.apache.gravitino.rest.v1.model.TableUpdateRequest;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.rest.v1.error.V1PublicErrorTranslator;

/**
 * Performs bounded strict JSON validation for the V1 CRUD request bodies before Jersey binds them.
 *
 * <p>The legacy server mapper deliberately has compatibility settings, including case-insensitive
 * enums. V1 has a stricter public contract, so this filter validates only the new V1 create and
 * full-replacement request types using a private mapper. It then replaces the consumed entity
 * stream with the original bytes so normal Jersey binding continues to use the established mapper
 * and resource-method pipeline.
 */
@PreMatching
@javax.annotation.Priority(Priorities.ENTITY_CODER)
public final class V1JsonBodyFilter implements ContainerRequestFilter {

  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String API_PATH_SEGMENT = "api";
  private static final String V1_PATH_SEGMENT = "v1";
  private static final String METALAKES_PATH_SEGMENT = "metalakes";
  private static final String CATALOGS_PATH_SEGMENT = "catalogs";
  private static final String SCHEMAS_PATH_SEGMENT = "schemas";
  private static final String TABLES_PATH_SEGMENT = "tables";
  private static final String BODY_FIELD = "body";
  private static final String INVALID_BODY_DESCRIPTION =
      "Must be a single JSON value that conforms to this endpoint's request schema.";

  private static final int COPY_BUFFER_SIZE = 8 * 1024;

  private static final ObjectMapper STRICT_BODY_MAPPER = strictBodyMapper();

  /**
   * Validates a V1 create or update body when the request targets a strict V1 CRUD route.
   *
   * @param requestContext mutable request state, including the entity stream.
   * @throws IOException if the container request stream cannot be read.
   */
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    Class<?> requestType = requestType(requestContext);
    if (requestType == null || !isJsonEntity(requestContext)) {
      return;
    }

    byte[] body = readEntity(requestContext.getEntityStream());
    requestContext.setEntityStream(new ByteArrayInputStream(body));
    if (body.length == 0) {
      return;
    }

    try {
      JsonNode bodyNode = STRICT_BODY_MAPPER.readTree(body);
      V1JsonNullValidator.validate(bodyNode, requestType);
      STRICT_BODY_MAPPER.readValue(body, requestType);
    } catch (IOException | RuntimeException exception) {
      requestContext.abortWith(
          V1PublicErrorTranslator.toInvalidArgumentResponse(BODY_FIELD, INVALID_BODY_DESCRIPTION));
    }
  }

  private static ObjectMapper strictBodyMapper() {
    ObjectMapper mapper = ObjectMapperProvider.objectMapper().copy();
    mapper.setConfig(
        mapper
            .getDeserializationConfig()
            .without(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    rejectTextualScalarCoercion(mapper);
    return mapper;
  }

  private static void rejectTextualScalarCoercion(ObjectMapper mapper) {
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }

  private static boolean isJsonEntity(ContainerRequestContext requestContext) {
    MediaType mediaType = requestContext.getMediaType();
    return requestContext.hasEntity()
        && mediaType != null
        && MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType);
  }

  @Nullable
  private static Class<?> requestType(ContainerRequestContext requestContext) {
    List<String> pathSegments = v1PathSegments(requestContext.getUriInfo().getRequestUri());
    if (pathSegments == null) {
      return null;
    }

    String method = requestContext.getMethod();
    if (POST.equalsIgnoreCase(method)) {
      return createRequestType(pathSegments);
    }
    if (PUT.equalsIgnoreCase(method)) {
      return updateRequestType(pathSegments);
    }
    return null;
  }

  @Nullable
  private static List<String> v1PathSegments(URI requestUri) {
    if (requestUri == null) {
      return null;
    }

    String path = requestUri.getPath();
    if (path == null || path.isEmpty()) {
      return null;
    }

    String[] rawSegments = path.split("/");
    List<String> segments = new ArrayList<>();
    for (String rawSegment : rawSegments) {
      if (!rawSegment.isEmpty()) {
        segments.add(rawSegment);
      }
    }

    if (segments.size() >= 2
        && API_PATH_SEGMENT.equals(segments.get(0))
        && V1_PATH_SEGMENT.equals(segments.get(1))) {
      return segments.subList(2, segments.size());
    }
    if (!segments.isEmpty() && V1_PATH_SEGMENT.equals(segments.get(0))) {
      return segments.subList(1, segments.size());
    }
    return null;
  }

  @Nullable
  private static Class<?> createRequestType(List<String> pathSegments) {
    if (matches(pathSegments, METALAKES_PATH_SEGMENT)) {
      return MetalakeCreateRequest.class;
    }
    if (matches(pathSegments, METALAKES_PATH_SEGMENT, null, CATALOGS_PATH_SEGMENT)) {
      return CatalogCreateRequest.class;
    }
    if (matches(
        pathSegments,
        METALAKES_PATH_SEGMENT,
        null,
        CATALOGS_PATH_SEGMENT,
        null,
        SCHEMAS_PATH_SEGMENT)) {
      return SchemaCreateRequest.class;
    }
    if (matches(
        pathSegments,
        METALAKES_PATH_SEGMENT,
        null,
        CATALOGS_PATH_SEGMENT,
        null,
        SCHEMAS_PATH_SEGMENT,
        null,
        TABLES_PATH_SEGMENT)) {
      return TableCreateRequest.class;
    }
    return null;
  }

  @Nullable
  private static Class<?> updateRequestType(List<String> pathSegments) {
    if (matches(pathSegments, METALAKES_PATH_SEGMENT, null)) {
      return MetalakeUpdateRequest.class;
    }
    if (matches(pathSegments, METALAKES_PATH_SEGMENT, null, CATALOGS_PATH_SEGMENT, null)) {
      return CatalogUpdateRequest.class;
    }
    if (matches(
        pathSegments,
        METALAKES_PATH_SEGMENT,
        null,
        CATALOGS_PATH_SEGMENT,
        null,
        SCHEMAS_PATH_SEGMENT,
        null)) {
      return SchemaUpdateRequest.class;
    }
    if (matches(
        pathSegments,
        METALAKES_PATH_SEGMENT,
        null,
        CATALOGS_PATH_SEGMENT,
        null,
        SCHEMAS_PATH_SEGMENT,
        null,
        TABLES_PATH_SEGMENT,
        null)) {
      return TableUpdateRequest.class;
    }
    return null;
  }

  private static boolean matches(List<String> pathSegments, String... expectedSegments) {
    if (pathSegments.size() != expectedSegments.length) {
      return false;
    }
    for (int index = 0; index < expectedSegments.length; index++) {
      String expectedSegment = expectedSegments[index];
      if (expectedSegment != null && !expectedSegment.equals(pathSegments.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static byte[] readEntity(InputStream entityStream) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[COPY_BUFFER_SIZE];
    int read;
    while ((read = entityStream.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }
}
