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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
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
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rest.v1.model.Distribution;
import org.apache.gravitino.rest.v1.model.TableCreateRequest;
import org.apache.gravitino.rest.v1.model.TableListResponse;
import org.apache.gravitino.rest.v1.model.TableReference;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.rest.v1.model.TableStorage;
import org.apache.gravitino.rest.v1.model.TableUpdateRequest;
import org.apache.gravitino.rest.v1.model.Transform;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.v1.error.V1ApiException;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorContext;
import org.apache.gravitino.server.web.rest.v1.mapper.TableMapper;
import org.apache.gravitino.server.web.rest.v1.mapper.TableMutationMapper;
import org.apache.gravitino.server.web.rest.v1.mapper.TableOptionsMapper;
import org.apache.gravitino.server.web.rest.v1.mapper.TableRequestMapper;
import org.apache.gravitino.server.web.rest.v1.support.V1ConditionalMutation;
import org.apache.gravitino.server.web.rest.v1.support.V1ResourceSupport;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;

/**
 * Route-versioned public V1 table representation endpoints.
 *
 * <p>This resource is intentionally independent from the legacy table route: it consumes and
 * produces the API-domain wire model defined by the V1 OpenAPI contract, and uses an explicit
 * mapper at the internal-domain boundary.
 */
@Path("v1/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TableOperationsV1 {

  private static final Pattern IF_NONE_MATCH_PATTERN =
      Pattern.compile("^(\\*|(?:W/)?\"[^\"\\\\]+\"(?:\\s*,\\s*(?:W/)?\"[^\"\\\\]+\")*)$");

  private static final String LAKEHOUSE_GENERIC_PROVIDER = "lakehouse-generic";

  private final TableDispatcher dispatcher;
  private final CatalogDispatcher catalogDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the V1 table resource.
   *
   * @param dispatcher the internal table dispatcher.
   * @param catalogDispatcher the internal catalog dispatcher used to resolve the provider profile.
   */
  @Inject
  public TableOperationsV1(TableDispatcher dispatcher, CatalogDispatcher catalogDispatcher) {
    this.dispatcher = dispatcher;
    this.catalogDispatcher = catalogDispatcher;
  }

  /**
   * Lists table references visible under one schema.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param uriInfo request URI and query parameters.
   * @return stable-name-ordered V1 table references.
   */
  @GET
  @Timed(name = "list-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-v1-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_SCHEMA_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response listTables(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier[] identifiers =
                dispatcher.listTables(NamespaceUtil.ofTable(metalake, catalog, schema));
            List<TableReference> references =
                Arrays.stream(identifiers)
                    .map(NameIdentifier::name)
                    .sorted()
                    .map(
                        name ->
                            new TableReference(
                                canonicalResourceName(metalake, catalog, schema, name), name))
                    .collect(Collectors.toList());
            return V1ResourceSupport.noStore(Response.ok(new TableListResponse(references)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.schemaRead(metalake, catalog, schema));
    }
  }

  /**
   * Creates a table using the V1 wire model and explicit internal-domain translation.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param request strict V1 table-create request.
   * @param uriInfo request URI used to construct the created resource location.
   * @return the created V1 table representation.
   */
  @POST
  @Timed(name = "create-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-v1-table", absolute = true)
  @AuthorizationExpression(
      expression =
          "ANY(OWNER, METALAKE, CATALOG) || "
              + "SCHEMA_OWNER_WITH_USE_CATALOG || "
              + "ANY_USE_CATALOG && ANY_USE_SCHEMA && ANY_CREATE_TABLE",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response createTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      TableCreateRequest request,
      @Context UriInfo uriInfo) {
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.validatePathSegment("name", request.getName());
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, request.getName());
            String provider = catalogProvider(metalake, catalog);
            validateKnownCreateConstraints(provider, request);
            Table internal =
                dispatcher.createTable(
                    identifier,
                    TableRequestMapper.toColumns(request.getColumns()),
                    request.getComment(),
                    TableOptionsMapper.toInternalProperties(
                        provider,
                        request.getStorage(),
                        request.getIcebergOptions(),
                        request.getHiveOptions(),
                        request.getClickhouseOptions(),
                        request.getMysqlOptions()),
                    TableRequestMapper.toTransforms(request.getPartitioning()),
                    TableRequestMapper.toDistribution(request.getDistribution()),
                    TableRequestMapper.toSortOrders(request.getSortOrders()),
                    TableRequestMapper.toIndexes(request.getIndexes()));
            TableResource resource =
                TableMapper.toResource(
                    canonicalResourceName(metalake, catalog, schema, internal.name()),
                    internal,
                    provider);
            return V1ResourceSupport.noStore(
                    Response.created(
                            uriInfo.getAbsolutePathBuilder().path(resource.getName()).build())
                        .entity(resource)
                        .tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, createErrorContext(metalake, catalog, schema, request));
    }
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

  /**
   * Replaces the supported mutable table state after verifying the current strong ETag.
   *
   * <p>The request always contains the full desired V1 resource state. The current dispatcher only
   * exposes atomic mutations for comments and properties; changes to the remaining resource shape
   * are rejected before the dispatcher is called until a complete replacement primitive exists.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param table table name.
   * @param update complete desired V1 table state.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return the updated V1 table representation.
   */
  @PUT
  @Path("{table}")
  @Timed(name = "update-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "update-v1-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.MODIFY_TABLE_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response updateTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      TableUpdateRequest update,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      requireRequest(update);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.validatePathSegment("table", table);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            String resourceName = canonicalResourceName(metalake, catalog, schema, table);
            String provider = catalogProvider(metalake, catalog);
            Table updated =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadTable(identifier),
                    current -> TableMapper.toResource(resourceName, current, provider),
                    current -> {
                      TableResource currentResource =
                          TableMapper.toResource(resourceName, current, provider);
                      TableChange[] changes =
                          TableMutationMapper.toChanges(currentResource, update);
                      return changes.length == 0
                          ? current
                          : dispatcher.alterTable(identifier, changes);
                    });
            TableResource resource = TableMapper.toResource(resourceName, updated, provider);
            return cacheable(Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(
          exception, V1ErrorContext.tableWrite(metalake, catalog, schema, table));
    }
  }

  /**
   * Deletes a table after verifying the current strong ETag.
   *
   * @param metalake metalake name.
   * @param catalog catalog name.
   * @param schema schema name.
   * @param table table name.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return an empty successful response.
   */
  @DELETE
  @Path("{table}")
  @Timed(name = "delete-v1-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-v1-table", absolute = true)
  @AuthorizationExpression(
      expression =
          "ANY(OWNER, METALAKE, CATALOG) || "
              + "SCHEMA_OWNER_WITH_USE_CATALOG || "
              + "ANY_USE_CATALOG && ANY_USE_SCHEMA && TABLE::OWNER",
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response deleteTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.validatePathSegment("table", table);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo, "purge");
      boolean purge = V1ResourceSupport.optionalBooleanQueryParameter(uriInfo, "purge");
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            String resourceName = canonicalResourceName(metalake, catalog, schema, table);
            String provider = catalogProvider(metalake, catalog);
            boolean deleted =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadTable(identifier),
                    current -> TableMapper.toResource(resourceName, current, provider),
                    () ->
                        purge
                            ? dispatcher.purgeTable(identifier)
                            : dispatcher.dropTable(identifier));
            if (!deleted) {
              throw new IllegalStateException("The validated table could not be deleted.");
            }
            return V1ResourceSupport.noStore(Response.noContent()).build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(
          exception, V1ErrorContext.tableWrite(metalake, catalog, schema, table));
    }
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
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.validatePathSegment("table", table);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      validateIfNoneMatch(headers);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            String provider = catalogProvider(metalake, catalog);
            Table internalTable = dispatcher.loadTable(identifier);
            TableResource resource = TableMapper.toResource(resourceName, internalTable, provider);
            EntityTag entityTag = V1ResourceSupport.entityTag(resource);
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

  private String catalogProvider(String metalake, String catalog) {
    Catalog catalogInfo =
        catalogDispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalog));
    return catalogInfo.provider();
  }

  private static void validateKnownCreateConstraints(String provider, TableCreateRequest request) {
    TableStorage storage = request.getStorage();
    if (!LAKEHOUSE_GENERIC_PROVIDER.equalsIgnoreCase(provider)
        || storage == null
        || storage.getTableFormat() != TableStorage.TableFormat.DELTA) {
      return;
    }
    for (int index = 0; index < request.getPartitioning().size(); index++) {
      if (!(request.getPartitioning().get(index) instanceof Transform.Identity)) {
        throw new V1ClientInputException(
            "partitioning[" + index + "]",
            "Delta tables support identity partition transforms only.");
      }
    }
    Distribution distribution = request.getDistribution();
    if (distribution != null && distribution.getStrategy() != Distribution.Strategy.NONE) {
      throw new V1ClientInputException(
          "distribution", "Delta tables do not support an explicit data distribution.");
    }
    if (!request.getSortOrders().isEmpty()) {
      throw new V1ClientInputException(
          "sortOrders", "Delta tables do not support sort orders in this V1 profile.");
    }
    if (!request.getIndexes().isEmpty()) {
      throw new V1ClientInputException(
          "indexes", "Delta tables do not support indexes in this V1 profile.");
    }
  }

  private static String canonicalResourceName(
      String metalake, String catalog, String schema, String table) {
    return String.format(
        "metalakes/%s/catalogs/%s/schemas/%s/tables/%s", metalake, catalog, schema, table);
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

  private static void requireRequest(Object request) {
    if (request == null) {
      throw new V1ClientInputException("body", "Request body is required.");
    }
  }

  private static V1ErrorContext createErrorContext(
      String metalake, String catalog, String schema, TableCreateRequest request) {
    return request == null
        ? V1ErrorContext.schemaWrite(metalake, catalog, schema)
        : V1ErrorContext.tableWrite(metalake, catalog, schema, request.getName());
  }
}
