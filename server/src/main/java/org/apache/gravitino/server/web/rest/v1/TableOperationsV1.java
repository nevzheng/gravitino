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
package org.apache.gravitino.server.web.rest.v1;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.v1.error.V1ApiException;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorContext;
import org.apache.gravitino.server.web.rest.v1.mapper.TableMapper;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Route-versioned public V1 table representation endpoints.
 *
 * <p>This resource is intentionally independent from the legacy table route: it consumes and
 * produces the API-domain wire model defined by the V1 OpenAPI contract, and uses an explicit
 * mapper at the internal-domain boundary.
 */
@Path("v1/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables")
@Produces(MediaType.APPLICATION_JSON)
public class TableOperationsV1 {

  private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("[^/?#]{1,255}");
  private static final Pattern IF_NONE_MATCH_PATTERN =
      Pattern.compile("^(\\*|(?:W/)?\"[^\"\\\\]+\"(?:\\s*,\\s*(?:W/)?\"[^\"\\\\]+\")*)$");

  private final TableDispatcher dispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the V1 table resource.
   *
   * @param dispatcher the internal table dispatcher.
   */
  @Inject
  public TableOperationsV1(TableDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Returns one table's V1 public representation.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param table table name.
   * @param request request precondition evaluator.
   * @param headers request headers.
   * @param uriInfo request URI and query parameters.
   * @return a table resource, a conditional {@code 304}, or a V1 public error.
   */
  @GET
  @Path("{table}")
  @Timed(name = "get-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-v1-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response getTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @Context Request request,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    return loadTable(metalake, catalog, schema, table, request, headers, uriInfo, false);
  }

  /**
   * Returns the cache validator and other representation headers for one table without a body.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param table table name.
   * @param request request precondition evaluator.
   * @param headers request headers.
   * @param uriInfo request URI and query parameters.
   * @return representation headers, a conditional {@code 304}, or a V1 public error.
   */
  @HEAD
  @Path("{table}")
  @Timed(name = "head-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "head-v1-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response headTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @Context Request request,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    return loadTable(metalake, catalog, schema, table, request, headers, uriInfo, true);
  }

  private Response loadTable(
      String metalake,
      String catalog,
      String schema,
      String table,
      Request request,
      HttpHeaders headers,
      UriInfo uriInfo,
      boolean headOnly) {
    String resourceName = canonicalResourceName(metalake, catalog, schema, table);
    try {
      validatePathSegment("metalake", metalake);
      validatePathSegment("catalog", catalog);
      validatePathSegment("schema", schema);
      validatePathSegment("table", table);
      rejectQueryParameters(uriInfo);
      validateIfNoneMatch(headers);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            Table internalTable = dispatcher.loadTable(identifier);
            TableResource resource = TableMapper.toResource(resourceName, internalTable);
            EntityTag entityTag = entityTag(resource);
            Response.ResponseBuilder precondition = request.evaluatePreconditions(entityTag);
            if (precondition != null) {
              return cacheable(precondition.tag(entityTag)).build();
            }
            if (headOnly) {
              return cacheable(Response.ok().tag(entityTag)).build();
            }
            return cacheable(Response.ok(resource).tag(entityTag)).build();
          });
    } catch (Exception e) {
      throw new V1ApiException(e, V1ErrorContext.tableRead(metalake, catalog, schema, table));
    }
  }

  private static Response.ResponseBuilder cacheable(Response.ResponseBuilder responseBuilder) {
    return responseBuilder
        .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
        .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION + ", Accept, Accept-Encoding");
  }

  private static EntityTag entityTag(TableResource resource)
      throws IOException, NoSuchAlgorithmException {
    byte[] representation =
        ObjectMapperProvider.objectMapper()
            .writeValueAsString(resource)
            .getBytes(StandardCharsets.UTF_8);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(representation);
    StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      hexadecimal.append(String.format("%02x", value & 0xff));
    }
    return new EntityTag(hexadecimal.toString(), true);
  }

  private static String canonicalResourceName(
      String metalake, String catalog, String schema, String table) {
    return String.format(
        "metalakes/%s/catalogs/%s/schemas/%s/tables/%s", metalake, catalog, schema, table);
  }

  private static void rejectQueryParameters(UriInfo uriInfo) {
    if (!uriInfo.getQueryParameters().isEmpty()) {
      throw new V1ClientInputException("query", "This operation does not accept query parameters.");
    }
  }

  private static void validatePathSegment(String field, String value) {
    if (value == null || !PATH_SEGMENT_PATTERN.matcher(value).matches()) {
      throw new V1ClientInputException(
          field, "Must be a 1 to 255 character URI path segment without /, ?, or #.");
    }
  }

  private static void validateIfNoneMatch(HttpHeaders headers) {
    List<String> values = headers.getRequestHeader(HttpHeaders.IF_NONE_MATCH);
    if (values == null || values.isEmpty()) {
      return;
    }
    if (values.size() != 1
        || values.get(0).length() > 4096
        || !IF_NONE_MATCH_PATTERN.matcher(values.get(0)).matches()) {
      throw new V1ClientInputException(
          HttpHeaders.IF_NONE_MATCH,
          "Must be one 1 to 4096 character If-None-Match header containing valid entity tags.");
    }
  }
}
