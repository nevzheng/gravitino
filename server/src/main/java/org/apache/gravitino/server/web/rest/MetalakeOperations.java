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

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.MetalakeDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.MetalakeCreateRequest;
import org.apache.gravitino.dto.requests.MetalakeSetRequest;
import org.apache.gravitino.dto.requests.MetalakeUpdateRequest;
import org.apache.gravitino.dto.requests.MetalakeUpdatesRequest;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.MetalakeListResponse;
import org.apache.gravitino.dto.responses.MetalakeResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/metalakes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MetalakeOperations {

  private static final Logger LOG = LoggerFactory.getLogger(MetalakeOperations.class);

  private final MetalakeDispatcher metalakeDispatcher;
  private final RecoverableDeletionManager recoverableDeletionManager;

  @Context private HttpServletRequest httpRequest;

  @Inject
  public MetalakeOperations(
      MetalakeDispatcher dispatcher, RecoverableDeletionManager recoverableDeletionManager) {
    this.metalakeDispatcher = dispatcher;
    this.recoverableDeletionManager = recoverableDeletionManager;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-metalake", absolute = true)
  public Response listMetalakes(
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("name") String name,
      @QueryParam("id") String id) {
    LOG.info("Received list metalakes request.");
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              checkDeletedMetalakeAccess(name);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              List<DeletedEntityDTO> deletedMetalakes =
                  recoverableDeletionManager.listDeletedMetalakes(name, entityId);
              return Utils.ok(
                  new DeletedEntityListResponse(deletedMetalakes.toArray(new DeletedEntityDTO[0])));
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            Metalake[] metalakes = metalakeDispatcher.listMetalakes();
            if (name != null) {
              metalakes =
                  Arrays.stream(metalakes)
                      .filter(metalake -> name.equals(metalake.name()))
                      .toArray(Metalake[]::new);
            }
            metalakes =
                MetadataAuthzHelper.filterMetalakes(
                    metalakes,
                    AuthorizationExpressionConstants.LOAD_METALAKE_AUTHORIZATION_EXPRESSION);
            MetalakeDTO[] metalakeDTOs =
                Arrays.stream(metalakes).map(DTOConverters::toDTO).toArray(MetalakeDTO[]::new);
            Response response = Utils.ok(new MetalakeListResponse(metalakeDTOs));
            LOG.info("List {} metalakes in Gravitino", metalakeDTOs.length);
            return response;
          });

    } catch (Exception e) {
      return handleRecoveryException(OperationType.LIST, Namespace.empty().toString(), e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-metalake", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      errorMessage =
          "Only service admins can create metalakes, current user can't create the metalake,"
              + "  you should configure it in the server configuration first")
  public Response createMetalake(MetalakeCreateRequest request) {
    if (request == null) {
      LOG.warn("Received create metalake request with null request body");
      return ExceptionHandlers.handleMetalakeException(
          OperationType.CREATE, "", new IllegalArgumentException("Request body cannot be null"));
    }

    LOG.info("Received create metalake request for {}", request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident = NameIdentifierUtil.ofMetalake(request.getName());
            Metalake metalake =
                metalakeDispatcher.createMetalake(
                    ident, request.getComment(), request.getProperties());
            Response response = Utils.ok(new MetalakeResponse(DTOConverters.toDTO(metalake)));
            LOG.info("Metalake created: {}", metalake.name());
            return response;
          });

    } catch (Exception e) {
      String metalakeName = request != null ? request.getName() : "";
      return ExceptionHandlers.handleMetalakeException(OperationType.CREATE, metalakeName, e);
    }
  }

  @GET
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "load-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "load-metalake", absolute = true)
  @AuthorizationExpression(
      expression =
          "SERVICE_ADMIN || "
              + AuthorizationExpressionConstants.LOAD_METALAKE_AUTHORIZATION_EXPRESSION)
  public Response loadMetalake(
      @PathParam("name") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalakeName,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("id") String id) {
    LOG.info("Received load metalake request for metalake: {}", metalakeName);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              checkDeletedMetalakeAccess(metalakeName);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              if (entityId == null) {
                throw new IllegalArgumentException(
                    "id is required when loading a deleted metalake");
              }
              DeletedEntityDTO deletedMetalake =
                  recoverableDeletionManager.getDeletedMetalake(metalakeName, entityId);
              return Response.fromResponse(Utils.ok(new DeletedEntityResponse(deletedMetalake)))
                  .tag(new EntityTag(deletedMetalake.getEtag()))
                  .build();
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalakeName);
            Metalake metalake = metalakeDispatcher.loadMetalake(identifier);
            Response response = Utils.ok(new MetalakeResponse(DTOConverters.toDTO(metalake)));
            LOG.info("Metalake loaded: {}", metalake.name());
            return response;
          });

    } catch (Exception e) {
      return handleRecoveryException(OperationType.LOAD, metalakeName, e);
    }
  }

  @PATCH
  @Path("{name}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "set-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "set-metalake", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response setMetalake(
      @PathParam("name") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalakeName,
      MetalakeSetRequest request) {
    LOG.info("Received set request for metalake: {}", metalakeName);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalakeName);
            if (request.isInUse()) {
              metalakeDispatcher.enableMetalake(identifier);
            } else {
              metalakeDispatcher.disableMetalake(identifier);
            }
            Response response = Utils.ok(new BaseResponse());
            LOG.info(
                "Successfully {} metalake: {}",
                request.isInUse() ? "enable" : "disable",
                metalakeName);
            return response;
          });

    } catch (Exception e) {
      LOG.info("Failed to {} metalake: {}", request.isInUse() ? "enable" : "disable", metalakeName);
      return ExceptionHandlers.handleMetalakeException(
          request.isInUse() ? OperationType.ENABLE : OperationType.DISABLE, metalakeName, e);
    }
  }

  /**
   * Restores one exact soft-deleted metalake metadata generation.
   *
   * <p>When the original metalake deletion cascaded, the recovery transaction restores the exact
   * Gravitino metadata tree recorded by that deletion generation. No catalog connector or
   * downstream system is contacted. The restored response is loaded through the normal metalake
   * dispatcher so hidden properties are not exposed.
   *
   * @param metalakeName original metalake name
   * @param include deleted-resource selector
   * @param id immutable metalake identifier
   * @param ifMatch strong entity tag returned by the exact deleted-metalake read
   * @param request merge patch that changes {@code deleted} to {@code false}
   * @return the restored metalake response
   */
  @PATCH
  @Path("{name}")
  @Consumes("application/merge-patch+json")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "restore-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "restore-metalake", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response restoreMetalake(
      @PathParam("name") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalakeName,
      @QueryParam("include") String include,
      @QueryParam("id") String id,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      EntityRestoreRequest request) {
    LOG.info("Received restore metalake request: {}", metalakeName);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if (request == null) {
              throw new IllegalArgumentException("A merge-patch request body is required");
            }
            request.validate();
            if (!"deleted".equals(include)) {
              throw new IllegalArgumentException(
                  "include=deleted is required when restoring a deleted metalake");
            }
            Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
            if (entityId == null) {
              throw new IllegalArgumentException(
                  "id is required when restoring a deleted metalake");
            }
            String etag = RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "metalake");
            checkDeletedMetalakeAccess(metalakeName);
            recoverableDeletionManager.restoreDeletedMetalake(metalakeName, entityId, etag);

            Metalake restored =
                metalakeDispatcher.loadMetalake(NameIdentifierUtil.ofMetalake(metalakeName));
            return Utils.ok(new MetalakeResponse(DTOConverters.toDTO(restored)));
          });
    } catch (Exception e) {
      return handleRecoveryException(OperationType.RESTORE, metalakeName, e);
    }
  }

  @PUT
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "alter-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "alter-metalake", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response alterMetalake(
      @PathParam("name") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalakeName,
      MetalakeUpdatesRequest updatesRequest) {
    LOG.info("Received alter metalake request for metalake: {}", metalakeName);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            updatesRequest.validate();
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalakeName);
            MetalakeChange[] changes =
                updatesRequest.getUpdates().stream()
                    .map(MetalakeUpdateRequest::metalakeChange)
                    .toArray(MetalakeChange[]::new);
            Metalake updatedMetalake = metalakeDispatcher.alterMetalake(identifier, changes);
            Response response =
                Utils.ok(new MetalakeResponse(DTOConverters.toDTO(updatedMetalake)));
            LOG.info("Metalake altered: {}", updatedMetalake.name());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.ALTER, metalakeName, e);
    }
  }

  @DELETE
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "drop-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "drop-metalake", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response dropMetalake(
      @PathParam("name") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalakeName,
      @DefaultValue("false") @QueryParam("force") boolean force) {
    LOG.info("Received drop metalake request for metalake: {}", metalakeName);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofMetalake(metalakeName);
            boolean dropped = metalakeDispatcher.dropMetalake(identifier, force);
            if (dropped) {
              LOG.info("Metalake dropped: {}", metalakeName);
            } else {
              LOG.warn("Failed to drop metalake by name {}", metalakeName);
            }

            return Utils.ok(new DropResponse(dropped));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.DROP, metalakeName, e);
    }
  }

  private static void checkDeletedMetalakeAccess(String metalakeName) {
    String authorizationName = metalakeName == null ? "deleted-metalakes" : metalakeName;
    if (!MetadataAuthzHelper.checkAccess(
        NameIdentifierUtil.ofMetalake(authorizationName),
        Entity.EntityType.METALAKE,
        "SERVICE_ADMIN")) {
      throw new ForbiddenException(
          "Only a service administrator can read or restore deleted metalakes");
    }
  }

  private static Response handleRecoveryException(
      OperationType operation, String metalakeName, Exception exception) {
    if (exception instanceof ForbiddenException) {
      return Utils.forbidden(exception.getMessage(), exception);
    }
    if (exception instanceof TombstoneNotFoundException) {
      return Utils.notFound(exception.getMessage(), exception);
    }
    return ExceptionHandlers.handleMetalakeException(operation, metalakeName, exception);
  }
}
