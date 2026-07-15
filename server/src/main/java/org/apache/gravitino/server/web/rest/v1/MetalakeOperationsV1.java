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
import org.apache.gravitino.Entity;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rest.v1.model.MetalakeCreateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeListResponse;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.rest.v1.model.MetalakeUpdateRequest;
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

/** Route-versioned public V1 endpoints for metalake collection and resource operations. */
@Path("v1/metalakes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MetalakeOperationsV1 {

  private final MetalakeDispatcher dispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the V1 metalake resource.
   *
   * @param dispatcher internal metalake dispatcher.
   */
  @Inject
  public MetalakeOperationsV1(MetalakeDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Lists the visible metalakes using V1 public representations.
   *
   * @param uriInfo request URI and query parameters.
   * @return the visible V1 metalake collection.
   */
  @GET
  @Timed(name = "list-v1-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-v1-metalake", absolute = true)
  public Response listMetalakes(@Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            Metalake[] metalakes = dispatcher.listMetalakes();
            metalakes =
                MetadataAuthzHelper.filterMetalakes(
                    metalakes,
                    AuthorizationExpressionConstants.LOAD_METALAKE_AUTHORIZATION_EXPRESSION);
            List<MetalakeResource> resources =
                Arrays.stream(metalakes)
                    .map(ResourceMapper::toResource)
                    .collect(Collectors.toList());
            return V1ResourceSupport.noStore(Response.ok(new MetalakeListResponse(resources)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.empty());
    }
  }

  /**
   * Creates a metalake from a strict V1 request.
   *
   * @param request strict V1 create request.
   * @param uriInfo request URI used to construct the created resource location.
   * @return the created V1 metalake representation.
   */
  @POST
  @Timed(name = "create-v1-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-v1-metalake", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      errorMessage =
          "Only service admins can create metalakes, current user can't create the metalake,"
              + "  you should configure it in the server configuration first")
  public Response createMetalake(MetalakeCreateRequest request, @Context UriInfo uriInfo) {
    V1ErrorContext errorContext =
        request == null ? V1ErrorContext.empty() : V1ErrorContext.metalakeWrite(request.getName());
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("name", request.getName());
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(request.getName());
            Metalake metalake =
                dispatcher.createMetalake(
                    identifier, request.getComment(), request.getProperties());
            MetalakeResource resource = ResourceMapper.toResource(metalake);
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
   * Returns one V1 metalake representation and its strong entity tag.
   *
   * @param metalake metalake name.
   * @param uriInfo request URI and query parameters.
   * @return the current V1 metalake representation.
   */
  @GET
  @Path("{metalake}")
  @Timed(name = "get-v1-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-v1-metalake", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_METALAKE_AUTHORIZATION_EXPRESSION)
  public Response getMetalake(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            Metalake internal = dispatcher.loadMetalake(NameIdentifierUtil.ofMetalake(metalake));
            MetalakeResource resource = ResourceMapper.toResource(internal);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.metalakeRead(metalake));
    }
  }

  /**
   * Replaces the complete V1 mutable metalake state when the supplied validator is current.
   *
   * @param metalake metalake name.
   * @param request complete desired V1 mutable state.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return the updated V1 metalake representation.
   */
  @PUT
  @Path("{metalake}")
  @Timed(name = "update-v1-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "update-v1-metalake", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response updateMetalake(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      MetalakeUpdateRequest request,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      requireRequest(request);
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo);
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalake);
            Metalake updated =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadMetalake(identifier),
                    ResourceMapper::toResource,
                    current -> {
                      MetalakeResource currentResource = ResourceMapper.toResource(current);
                      MetalakeChange[] changes =
                          ResourceMutationMapper.toChanges(currentResource, request);
                      return changes.length == 0
                          ? current
                          : dispatcher.alterMetalake(identifier, changes);
                    });
            MetalakeResource resource = ResourceMapper.toResource(updated);
            return V1ResourceSupport.noStore(
                    Response.ok(resource).tag(V1ResourceSupport.entityTag(resource)))
                .build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.metalakeWrite(metalake));
    }
  }

  /**
   * Deletes a metalake after its current V1 representation satisfies {@code If-Match}.
   *
   * @param metalake metalake name.
   * @param headers request headers containing the required strong {@code If-Match} value.
   * @param uriInfo request URI and query parameters.
   * @return an empty successful response.
   */
  @DELETE
  @Path("{metalake}")
  @Timed(name = "delete-v1-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-v1-metalake", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response deleteMetalake(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @Context HttpHeaders headers,
      @Context UriInfo uriInfo) {
    try {
      V1ResourceSupport.validatePathSegment("metalake", metalake);
      V1ResourceSupport.rejectUnexpectedQueryParameters(uriInfo, "force");
      boolean force = V1ResourceSupport.optionalBooleanQueryParameter(uriInfo, "force");
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalake);
            boolean dropped =
                V1ConditionalMutation.execute(
                    headers,
                    () -> dispatcher.loadMetalake(identifier),
                    ResourceMapper::toResource,
                    () -> dispatcher.dropMetalake(identifier, force));
            if (!dropped) {
              throw new IllegalStateException("The validated metalake could not be deleted.");
            }
            return V1ResourceSupport.noStore(Response.noContent()).build();
          });
    } catch (Exception exception) {
      throw new V1ApiException(exception, V1ErrorContext.metalakeWrite(metalake));
    }
  }

  private static void requireRequest(Object request) {
    if (request == null) {
      throw new V1ClientInputException("body", "Request body is required.");
    }
  }
}
