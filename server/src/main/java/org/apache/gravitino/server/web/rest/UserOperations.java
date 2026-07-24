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
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.UserAddRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.responses.RemoveResponse;
import org.apache.gravitino.dto.responses.UserListResponse;
import org.apache.gravitino.dto.responses.UserResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NameBindings.AccessControlInterfaces
@Path("/metalakes/{metalake}/users")
public class UserOperations {

  private static final Logger LOG = LoggerFactory.getLogger(UserOperations.class);

  private static final String LOAD_USER_PRIVILEGE =
      "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF";

  private final AccessControlDispatcher accessControlManager;
  private final OwnerDispatcher ownerManager;
  private final RecoverableDeletionManager recoverableDeletionManager;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates user REST operations.
   *
   * @param recoverableDeletionManager recoverable-deletion coordinator
   */
  @Inject
  public UserOperations(RecoverableDeletionManager recoverableDeletionManager) {
    // Because accessManager may be null when Gravitino doesn't enable authorization,
    // and Jersey injection doesn't support null value. So UserOperations chooses to retrieve
    // accessControlManager from GravitinoEnv instead of injection here.
    this.accessControlManager = GravitinoEnv.getInstance().accessControlDispatcher();
    this.ownerManager = GravitinoEnv.getInstance().ownerDispatcher();
    this.recoverableDeletionManager = recoverableDeletionManager;
  }

  @GET
  @Path("{user}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "get-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-user", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN || (" + LOAD_USER_PRIVILEGE + ")")
  public Response getUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("id") String id) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              checkDeletedUserAccess(metalake);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              if (entityId == null) {
                throw new IllegalArgumentException("id is required when loading a deleted user");
              }
              DeletedEntityDTO deletedUser =
                  recoverableDeletionManager.getDeletedUser(
                      NamespaceUtil.ofUser(metalake), user, entityId);
              return Response.fromResponse(Utils.ok(new DeletedEntityResponse(deletedUser)))
                  .tag(new EntityTag(deletedUser.getEtag()))
                  .build();
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new UserResponse(
                    DTOConverters.toDTO(accessControlManager.getUser(metalake, user))));
          });
    } catch (Exception e) {
      return "deleted".equals(include)
          ? handleRecoveryException(OperationType.GET, user, metalake, e)
          : ExceptionHandlers.handleUserException(OperationType.GET, user, metalake, e);
    }
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-user", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listUsers(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @QueryParam("details") @DefaultValue("false") boolean verbose,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("name") String name,
      @QueryParam("id") String id) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              if (verbose) {
                throw new IllegalArgumentException(
                    "details=true is not supported with include=deleted");
              }
              checkDeletedUserAccess(metalake);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              List<DeletedEntityDTO> deletedUsers =
                  recoverableDeletionManager.listDeletedUsers(
                      NamespaceUtil.ofUser(metalake), name, entityId);
              return Utils.ok(
                  new DeletedEntityListResponse(deletedUsers.toArray(new DeletedEntityDTO[0])));
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            MetalakeManager.checkMetalakeInUse(metalake);
            if (verbose) {
              User[] users = accessControlManager.listUsers(metalake);
              if (name != null) {
                users =
                    Arrays.stream(users)
                        .filter(user -> name.equals(user.name()))
                        .toArray(User[]::new);
              }
              users =
                  MetadataAuthzHelper.filterByExpression(
                      metalake,
                      LOAD_USER_PRIVILEGE,
                      Entity.EntityType.USER,
                      users,
                      (userEntity) -> NameIdentifierUtil.ofUser(metalake, userEntity.name()));

              return Utils.ok(new UserListResponse(DTOConverters.toDTOs(users)));
            } else {
              String[] users = accessControlManager.listUserNames(metalake);
              if (name != null) {
                users = Arrays.stream(users).filter(name::equals).toArray(String[]::new);
              }
              users =
                  MetadataAuthzHelper.filterByExpression(
                      metalake,
                      LOAD_USER_PRIVILEGE,
                      Entity.EntityType.USER,
                      users,
                      (username) -> NameIdentifierUtil.ofUser(metalake, username));
              return Utils.ok(new NameListResponse(users));
            }
          });
    } catch (Exception e) {
      return "deleted".equals(include)
          ? handleRecoveryException(OperationType.LIST, "", metalake, e)
          : ExceptionHandlers.handleUserException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Restores one exact soft-deleted user metadata generation.
   *
   * @param metalake metalake name
   * @param user original user name
   * @param include deleted-resource selector
   * @param id immutable user identifier
   * @param ifMatch strong entity tag returned by the exact deleted-user read
   * @param request merge patch that changes {@code deleted} to {@code false}
   * @return the restored user response
   */
  @PATCH
  @Path("{user}")
  @Consumes("application/merge-patch+json")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "restore-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "restore-user", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      accessMetadataType = MetadataObject.Type.METALAKE)
  public Response restoreUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") String user,
      @QueryParam("include") String include,
      @QueryParam("id") String id,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      EntityRestoreRequest request) {
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
                  "include=deleted is required when restoring a deleted user");
            }
            Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
            if (entityId == null) {
              throw new IllegalArgumentException("id is required when restoring a deleted user");
            }
            String etag = RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "user");
            checkDeletedUserAccess(metalake);
            UserEntity restored =
                recoverableDeletionManager.restoreDeletedUser(
                    NamespaceUtil.ofUser(metalake), user, entityId, etag);
            return Utils.ok(new UserResponse(DTOConverters.toDTO(restored)));
          });
    } catch (Exception e) {
      return handleRecoveryException(OperationType.RESTORE, user, metalake, e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "add-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "add-user", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
  public Response addUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      UserAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            MetalakeManager.checkMetalakeInUse(metalake);
            User addedUser =
                StringUtils.isNotBlank(request.getExternalId())
                    ? accessControlManager.addUser(
                        metalake,
                        request.getName(),
                        request.getExternalId(),
                        Optional.ofNullable(request.getEnabled()).orElse(true))
                    : accessControlManager.addUser(metalake, request.getName());
            return Utils.ok(new UserResponse(DTOConverters.toDTO(addedUser)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(
          OperationType.ADD, request.getName(), metalake, e);
    }
  }

  @DELETE
  @Path("{user}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "remove-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "remove-user", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
  public Response removeUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);

            ownerManager
                .getOwner(
                    metalake, MetadataObjects.of(null, metalake, MetadataObject.Type.METALAKE))
                .ifPresent(
                    owner -> {
                      if (owner.type() == Owner.Type.USER && owner.name().equals(user)) {
                        throw new IllegalArgumentException(
                            String.format(
                                "Cannot remove user %s from metalake %s because the user is the owner of the metalake.",
                                user, metalake));
                      }
                    });

            boolean removed = accessControlManager.removeUser(metalake, user);
            if (!removed) {
              LOG.warn("Failed to remove user {} under metalake {}", user, metalake);
            }
            return Utils.ok(new RemoveResponse(removed));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.REMOVE, user, metalake, e);
    }
  }

  private static void checkDeletedUserAccess(String metalake) {
    if (!MetadataAuthzHelper.checkAccess(
        NameIdentifierUtil.ofMetalake(metalake), Entity.EntityType.METALAKE, "SERVICE_ADMIN")) {
      throw new ForbiddenException(
          "Only a service administrator can read or restore deleted users under %s", metalake);
    }
  }

  private static Response handleRecoveryException(
      OperationType operation, String name, String metalake, Exception exception) {
    if (exception instanceof ForbiddenException) {
      return Utils.forbidden(exception.getMessage(), exception);
    }
    return ExceptionHandlers.handleUserException(operation, name, metalake, exception);
  }
}
