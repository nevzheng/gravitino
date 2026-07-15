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
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.CatalogProvider;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rest.v1.model.CatalogCreateRequest;
import org.apache.gravitino.rest.v1.model.CatalogListResponse;
import org.apache.gravitino.rest.v1.model.CatalogResource;
import org.apache.gravitino.rest.v1.model.CatalogUpdateRequest;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
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

/** Route-versioned public V1 endpoints for catalog collection and resource operations. */
@Path("v1/metalakes/{metalake}/catalogs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CatalogOperationsV1 {

  private final CatalogDispatcher dispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the V1 catalog resource.
   *
   * @param dispatcher internal catalog dispatcher.
   */
  @Inject
  public CatalogOperationsV1(CatalogDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Lists the visible catalogs in one metalake using V1 public representations.
   *
   * @param metalake parent metalake name.
   * @param uriInfo request URI and query parameters.
   * @return the visible V1 catalog collection.
   */
  @GET
  @Timed(name = "list-v1-catalog." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-v1-catalog", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listCatalogs(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace namespace = NamespaceUtil.ofCatalog(metalake);
            Catalog[] catalogs = dispatcher.listCatalogsInfo(namespace);
            catalogs =
                MetadataAuthzHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.CATALOG,
                    catalogs,
                    catalog -> NameIdentifierUtil.ofCatalog(metalake, catalog.name()));
            List<CatalogResource> resources =
                Arrays.stream(catalogs)
                    .map(catalog -> ResourceMapper.toResource(metalake, catalog))
                    .collect(Collectors.toList());
            return V1ResourceSupport.noStore(Response.ok(new CatalogListResponse(resources)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.metalakeRead(metalake));
    }
  }

  /**
   * Creates a catalog from a strict V1 request.
   *
   * @param metalake parent metalake name.
   * @param request strict V1 create request.
   * @param uriInfo request URI used to construct the created resource location.
   * @return the created V1 catalog representation.
   */
  @POST
  @Timed(name = "create-v1-catalog." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-v1-catalog", absolute = true)
  @AuthorizationExpression(
      expression = "METALAKE::CREATE_CATALOG || METALAKE::OWNER",
      accessMetadataType = MetadataObject.Type.METALAKE)
  public Response createCatalog(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      CatalogCreateRequest request,
      @Context UriInfo uriInfo) {
    V1ErrorContext errorContext =
        request == null
            ? V1ErrorContext.metalakeWrite(metalake)
            : V1ErrorContext.catalogWrite(metalake, request.getName());
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("name", request.getName());
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, request.getName());
            Catalog catalog =
                dispatcher.createCatalog(
                    identifier,
                    Catalog.Type.valueOf(request.getType().name()),
                    effectiveProvider(request),
                    request.getComment(),
                    request.getProperties());
            CatalogResource resource = ResourceMapper.toResource(metalake, catalog);
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
   * Returns one V1 catalog representation and its strong entity tag.
   *
   * @param metalake parent metalake name.
   * @param catalog catalog name.
   * @param uriInfo request URI and query parameters.
   * @return the current V1 catalog representation.
   */
  @GET
  @Path("{catalog}")
  @Timed(name = "get-v1-catalog." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-v1-catalog", absolute = true)
  @AuthorizationExpression(
      expression = "ANY_USE_CATALOG || ANY(OWNER, METALAKE, CATALOG)",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response getCatalog(
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
            Catalog internal =
                dispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalog));
            CatalogResource resource = ResourceMapper.toResource(metalake, internal);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.catalogRead(metalake, catalog));
    }
  }

  /**
   * Replaces the complete V1 mutable catalog state when the supplied validator is current.
   *
   * @param metalake parent metalake name.
   * @param catalog catalog name.
   * @param request complete desired V1 mutable state.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return the updated V1 catalog representation.
   */
  @PUT
  @Path("{catalog}")
  @Timed(name = "update-v1-catalog." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "update-v1-catalog", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG)",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response updateCatalog(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      CatalogUpdateRequest request,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalog);
            Catalog updated =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadCatalog(identifier),
                    current -> ResourceMapper.toResource(metalake, current),
                    current -> {
                      CatalogResource currentResource =
                          ResourceMapper.toResource(metalake, current);
                      CatalogChange[] changes =
                          ResourceMutationMapper.toChanges(currentResource, request);
                      return changes.length == 0
                          ? current
                          : dispatcher.alterCatalog(identifier, changes);
                    });
            CatalogResource resource = ResourceMapper.toResource(metalake, updated);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.catalogWrite(metalake, catalog));
    }
  }

  /**
   * Deletes a catalog after its current V1 representation satisfies {@code If-Match}.
   *
   * @param metalake parent metalake name.
   * @param catalog catalog name.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return an empty successful response.
   */
  @DELETE
  @Path("{catalog}")
  @Timed(name = "delete-v1-catalog." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-v1-catalog", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG)",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response deleteCatalog(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.validatePathSegment("catalog", catalog);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo, "force");
      boolean force = V1ResourceSupport.optionalBooleanQueryParameter(uriInfo, "force");
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalog);
            boolean dropped =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadCatalog(identifier),
                    current -> ResourceMapper.toResource(metalake, current),
                    () -> dispatcher.dropCatalog(identifier, force));
            if (!dropped) {
              throw new IllegalStateException("The validated catalog could not be deleted.");
            }
            return V1ResourceSupport.noStore(Response.noContent()).build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.catalogWrite(metalake, catalog));
    }
  }

  private static void requireRequest(Object request) {
    if (request == null) {
      throw new V1ClientInputException("body", "Request body is required.");
    }
  }

  private static String effectiveProvider(CatalogCreateRequest request) {
    if (request.getProvider() != null) {
      return request.getProvider();
    }
    return CatalogProvider.shortNameForManagedCatalog(
        Catalog.Type.valueOf(request.getType().name()));
  }
}
