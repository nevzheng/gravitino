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
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.GroupAddRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.GroupListResponse;
import org.apache.gravitino.dto.responses.GroupResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.responses.RemoveResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.meta.GroupEntity;
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
@Path("/metalakes/{metalake}/groups")
public class GroupOperations {

  private static final Logger LOG = LoggerFactory.getLogger(GroupOperations.class);

  private final AccessControlDispatcher accessControlManager;
  private final OwnerDispatcher ownerDispatcher;
  private final RecoverableDeletionManager recoverableDeletionManager;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates group REST operations.
   *
   * @param recoverableDeletionManager recoverable-deletion coordinator
   */
  @Inject
  public GroupOperations(RecoverableDeletionManager recoverableDeletionManager) {
    // Because accessManager may be null when Gravitino doesn't enable authorization,
    // and Jersey injection doesn't support null value. So GroupOperations chooses to retrieve
    // accessControlManager from GravitinoEnv instead of injection here.
    this.accessControlManager = GravitinoEnv.getInstance().accessControlDispatcher();
    this.ownerDispatcher = GravitinoEnv.getInstance().ownerDispatcher();
    this.recoverableDeletionManager = recoverableDeletionManager;
  }

  @GET
  @Path("{group}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "get-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-group", absolute = true)
  public Response getGroup(
      @PathParam("metalake") String metalake,
      @PathParam("group") String group,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("id") String id) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              checkDeletedGroupAccess(metalake);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              if (entityId == null) {
                throw new IllegalArgumentException("id is required when loading a deleted group");
              }
              DeletedEntityDTO deletedGroup =
                  recoverableDeletionManager.getDeletedGroup(
                      NamespaceUtil.ofGroup(metalake), group, entityId);
              return Response.fromResponse(Utils.ok(new DeletedEntityResponse(deletedGroup)))
                  .tag(new EntityTag(deletedGroup.getEtag()))
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
                new GroupResponse(
                    DTOConverters.toDTO(accessControlManager.getGroup(metalake, group))));
          });
    } catch (Exception e) {
      return "deleted".equals(include)
          ? handleRecoveryException(OperationType.GET, group, metalake, e)
          : ExceptionHandlers.handleGroupException(OperationType.GET, group, metalake, e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "add-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "add-group", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response addGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      GroupAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            MetalakeManager.checkMetalakeInUse(metalake);
            Group addedGroup =
                StringUtils.isNotBlank(request.getExternalId())
                    ? accessControlManager.addGroup(
                        metalake, request.getName(), request.getExternalId())
                    : accessControlManager.addGroup(metalake, request.getName());
            return Utils.ok(new GroupResponse(DTOConverters.toDTO(addedGroup)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(
          OperationType.ADD, request.getName(), metalake, e);
    }
  }

  @DELETE
  @Path("{group}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "remove-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "remove-group", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response removeGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            ownerDispatcher
                .getOwner(
                    metalake, MetadataObjects.of(null, metalake, MetadataObject.Type.METALAKE))
                .ifPresent(
                    owner -> {
                      if (owner.type() == Owner.Type.GROUP && owner.name().equals(group)) {
                        throw new IllegalArgumentException(
                            String.format(
                                "Cannot remove group %s from metalake %s because the group is the owner of the metalake.",
                                group, metalake));
                      }
                    });

            boolean removed = accessControlManager.removeGroup(metalake, group);
            if (!removed) {
              LOG.warn("Failed to remove group {} under metalake {}", group, metalake);
            }
            return Utils.ok(new RemoveResponse(removed));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.REMOVE, group, metalake, e);
    }
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-group", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listGroups(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @QueryParam("details") @DefaultValue("false") boolean verbose,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("name") String name,
      @QueryParam("id") String id) {
    LOG.info("Received list groups request.");
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if ("deleted".equals(include)) {
              if (verbose) {
                throw new IllegalArgumentException(
                    "details=true is not supported with include=deleted");
              }
              checkDeletedGroupAccess(metalake);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              List<DeletedEntityDTO> deletedGroups =
                  recoverableDeletionManager.listDeletedGroups(
                      NamespaceUtil.ofGroup(metalake), name, entityId);
              return Utils.ok(
                  new DeletedEntityListResponse(deletedGroups.toArray(new DeletedEntityDTO[0])));
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
              Group[] groups = accessControlManager.listGroups(metalake);
              if (name != null) {
                groups =
                    Arrays.stream(groups)
                        .filter(group -> name.equals(group.name()))
                        .toArray(Group[]::new);
              }
              return Utils.ok(new GroupListResponse(DTOConverters.toDTOs(groups)));
            } else {
              String[] groupNames = accessControlManager.listGroupNames(metalake);
              if (name != null) {
                groupNames = Arrays.stream(groupNames).filter(name::equals).toArray(String[]::new);
              }
              return Utils.ok(new NameListResponse(groupNames));
            }
          });

    } catch (Exception e) {
      return "deleted".equals(include)
          ? handleRecoveryException(OperationType.LIST, Namespace.empty().toString(), metalake, e)
          : ExceptionHandlers.handleGroupException(
              OperationType.LIST, Namespace.empty().toString(), metalake, e);
    }
  }

  /**
   * Restores one exact soft-deleted group metadata generation.
   *
   * @param metalake metalake name
   * @param group original group name
   * @param include deleted-resource selector
   * @param id immutable group identifier
   * @param ifMatch strong entity tag returned by the exact deleted-group read
   * @param request merge patch that changes {@code deleted} to {@code false}
   * @return the restored group response
   */
  @PATCH
  @Path("{group}")
  @Consumes("application/merge-patch+json")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "restore-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "restore-group", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      accessMetadataType = MetadataObject.Type.METALAKE)
  public Response restoreGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") String group,
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
                  "include=deleted is required when restoring a deleted group");
            }
            Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
            if (entityId == null) {
              throw new IllegalArgumentException("id is required when restoring a deleted group");
            }
            String etag = RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "group");
            checkDeletedGroupAccess(metalake);
            GroupEntity restored =
                recoverableDeletionManager.restoreDeletedGroup(
                    NamespaceUtil.ofGroup(metalake), group, entityId, etag);
            return Utils.ok(new GroupResponse(DTOConverters.toDTO(restored)));
          });
    } catch (Exception e) {
      return handleRecoveryException(OperationType.RESTORE, group, metalake, e);
    }
  }

  private static void checkDeletedGroupAccess(String metalake) {
    if (!MetadataAuthzHelper.checkAccess(
        NameIdentifierUtil.ofMetalake(metalake), Entity.EntityType.METALAKE, "SERVICE_ADMIN")) {
      throw new ForbiddenException(
          "Only a service administrator can read or restore deleted groups under %s", metalake);
    }
  }

  private static Response handleRecoveryException(
      OperationType operation, String name, String metalake, Exception exception) {
    if (exception instanceof ForbiddenException) {
      return Utils.forbidden(exception.getMessage(), exception);
    }
    return ExceptionHandlers.handleGroupException(operation, name, metalake, exception);
  }
}
