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
import java.util.Objects;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rest.v1.model.SchemaCreateRequest;
import org.apache.gravitino.rest.v1.model.SchemaListResponse;
import org.apache.gravitino.rest.v1.model.SchemaResource;
import org.apache.gravitino.rest.v1.model.SchemaUpdateRequest;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.v1.error.V1ApiException;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.apache.gravitino.server.web.rest.v1.error.V1ErrorContext;
import org.apache.gravitino.server.web.rest.v1.mapper.ResourceMapper;
import org.apache.gravitino.server.web.rest.v1.mapper.ResourceMutationMapper;
import org.apache.gravitino.server.web.rest.v1.support.V1ConditionalMutation;
import org.apache.gravitino.server.web.rest.v1.support.V1ResourceSupport;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;

/** Route-versioned public V1 endpoints for schema collection and resource operations. */
@Path("v1/metalakes/{metalake}/catalogs/{catalog}/schemas")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SchemaOperationsV1 {

  private final SchemaDispatcher dispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the V1 schema resource.
   *
   * @param dispatcher internal schema dispatcher.
   */
  @Inject
  public SchemaOperationsV1(SchemaDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Lists the visible schemas in one catalog using complete V1 public representations.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param uriInfo request URI and query parameters.
   * @return the visible V1 schema collection.
   */
  @GET
  @Timed(name = "list-v1-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-v1-schema", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response listSchemas(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace namespace = NamespaceUtil.ofSchema(metalake, catalog);
            NameIdentifier[] identifiers = dispatcher.listSchemas(namespace);
            identifiers =
                MetadataAuthzHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.SCHEMA,
                    identifiers);
            List<SchemaResource> resources =
                Arrays.stream(identifiers)
                    .map(dispatcher::loadSchema)
                    .map(schema -> ResourceMapper.toResource(metalake, catalog, schema))
                    .collect(Collectors.toList());
            return V1ResourceSupport.noStore(Response.ok(new SchemaListResponse(resources)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.catalogRead(metalake, catalog));
    }
  }

  /**
   * Creates a schema from a strict V1 request.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param request strict V1 create request.
   * @param uriInfo request URI used to construct the created resource location.
   * @return the created V1 schema representation.
   */
  @POST
  @Timed(name = "create-v1-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-v1-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG, SCHEMA) || ANY_USE_CATALOG && ANY_CREATE_SCHEMA",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response createSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @AuthorizationRequest(type = AuthorizationRequest.RequestType.CREATE_SCHEMA)
          SchemaCreateRequest request,
      @Context UriInfo uriInfo) {
    V1ErrorContext errorContext =
        request == null
            ? V1ErrorContext.catalogWrite(metalake, catalog)
            : V1ErrorContext.schemaWrite(metalake, catalog, request.getName());
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("name", request.getName());
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofSchema(metalake, catalog, request.getName());
            Schema schema =
                dispatcher.createSchema(identifier, request.getComment(), request.getProperties());
            SchemaResource resource = ResourceMapper.toResource(metalake, catalog, schema);
            return V1ResourceSupport.noStore(
                    Response.created(
                            uriInfo.getAbsolutePathBuilder().path(resource.getName()).build())
                        .entity(resource)
                        .tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, errorContext);
    }
  }

  /**
   * Returns one V1 schema representation and its strong entity tag.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param schema schema name.
   * @param uriInfo request URI and query parameters.
   * @return the current V1 schema representation.
   */
  @GET
  @Path("{schema}")
  @Timed(name = "get-v1-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-v1-schema", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_SCHEMA_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response getSchema(
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
            Schema internal =
                dispatcher.loadSchema(NameIdentifierUtil.ofSchema(metalake, catalog, schema));
            SchemaResource resource = ResourceMapper.toResource(metalake, catalog, internal);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.schemaRead(metalake, catalog, schema));
    }
  }

  /**
   * Replaces supported V1 schema mutable state when the supplied validator is current.
   *
   * <p>The existing schema dispatcher cannot update comments. A request that changes the comment is
   * therefore rejected as an unsupported operation rather than reporting a false successful
   * desired-state replacement.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param schema schema name.
   * @param request complete desired V1 mutable state.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return the updated V1 schema representation.
   */
  @PUT
  @Path("{schema}")
  @Timed(name = "update-v1-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "update-v1-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response updateSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      SchemaUpdateRequest request,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofSchema(metalake, catalog, schema);
            Schema updated =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadSchema(identifier),
                    current -> ResourceMapper.toResource(metalake, catalog, current),
                    current -> {
                      SchemaResource currentResource =
                          ResourceMapper.toResource(metalake, catalog, current);
                      if (!Objects.equals(currentResource.getComment(), request.getComment())) {
                        throw new UnsupportedOperationException(
                            "Schema comment mutation is not supported by the current dispatcher.");
                      }
                      SchemaChange[] changes =
                          ResourceMutationMapper.toChanges(currentResource, request);
                      return changes.length == 0
                          ? current
                          : dispatcher.alterSchema(identifier, changes);
                    });
            SchemaResource resource = ResourceMapper.toResource(metalake, catalog, updated);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.schemaWrite(metalake, catalog, schema));
    }
  }

  /**
   * Deletes a schema after its current V1 representation satisfies {@code If-Match}.
   *
   * @param metalake parent metalake name.
   * @param catalog parent catalog name.
   * @param schema schema name.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return an empty successful response.
   */
  @DELETE
  @Path("{schema}")
  @Timed(name = "delete-v1-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-v1-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response deleteSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.validatePathSegment("schema", schema);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo, "cascade");
      boolean cascade = V1ResourceSupport.optionalBooleanQueryParameter(uriInfo, "cascade");
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofSchema(metalake, catalog, schema);
            boolean dropped =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadSchema(identifier),
                    current -> ResourceMapper.toResource(metalake, catalog, current),
                    () -> dispatcher.dropSchema(identifier, cascade));
            if (!dropped) {
              throw new IllegalStateException("The validated schema could not be deleted.");
            }
            return V1ResourceSupport.noStore(Response.noContent()).build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.schemaWrite(metalake, catalog, schema));
    }
  }

  private static void requireRequest(Object request) {
    if (request == null) {
      throw new V1ClientInputException("body", "Request body is required.");
    }
  }
}
