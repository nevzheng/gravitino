/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.Encoded;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.IcebergExceptionMapper;
import org.apache.gravitino.iceberg.service.IcebergRESTUtils;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionAuthorization;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.gravitino.iceberg.service.deletion.IcebergTableDeletionLifecycle;
import org.apache.gravitino.iceberg.service.deletion.IcebergUndropRequest;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;

/** Management discovery and conditional UNDROP for retained Iceberg REST tables. */
@Path("/management/v1/{prefix:([^/]*/)?}namespaces/{namespace}/tables")
@Produces(MediaType.APPLICATION_JSON)
public class IcebergDeletionManagementOperations {

  private static final String MANAGEMENT_AUTHORIZATION =
      "ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG || "
          + "ANY_USE_CATALOG && ANY_USE_SCHEMA";

  private final IcebergTableDeletionLifecycle lifecycle;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the management resource.
   *
   * @param lifecycle Iceberg table deletion lifecycle
   */
  @Inject
  public IcebergDeletionManagementOperations(IcebergTableDeletionLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  /** Reactivates one exact deletion generation under a strong If-Match precondition. */
  @POST
  @Path("{table}/undrop")
  @Consumes(MediaType.APPLICATION_JSON)
  @Timed(name = "undrop-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "undrop-table", absolute = true)
  @AuthorizationExpression(
      expression = MANAGEMENT_AUTHORIZATION,
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response undrop(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG) @PathParam("prefix") String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @Encoded() @PathParam("namespace")
          String namespace,
      @Encoded() @PathParam("table") String table,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      IcebergUndropRequest request) {
    String catalogName = IcebergRESTUtils.getCatalogName(prefix);
    TableIdentifier identifier = identifier(namespace, table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            String deletionId = parseDeletionId(request);
            String acceptedEtag = parseStrongIfMatch(ifMatch);
            EntityDeletionPO deletion = lifecycle.getAction(catalogName, identifier, deletionId);
            requireDropAccess(catalogName, identifier, deletion);
            IcebergRequestContext context = new IcebergRequestContext(httpRequest, catalogName);
            LoadTableResponse response =
                lifecycle.undrop(
                    context, identifier, deletionId, acceptedEtag, System.currentTimeMillis());
            return IcebergRESTUtils.buildResponseWithETag(response);
          });
    } catch (IcebergDeletionException e) {
      return lifecycleError(e);
    } catch (Exception e) {
      return IcebergExceptionMapper.toRESTResponse(e);
    }
  }

  static String parseDeletionId(IcebergUndropRequest request) {
    if (request == null || StringUtils.isBlank(request.getDeletionId())) {
      throw new IcebergDeletionException(Outcome.BAD_REQUEST, "deletionId is required");
    }
    return request.getDeletionId();
  }

  static String parseStrongIfMatch(String ifMatch) {
    if (StringUtils.isBlank(ifMatch)) {
      throw new IcebergDeletionException(Outcome.PRECONDITION_REQUIRED, "If-Match is required");
    }
    String value = ifMatch.trim();
    if ("*".equals(value)
        || value.startsWith("W/")
        || value.contains(",")
        || value.length() < 3
        || value.charAt(0) != '"'
        || value.charAt(value.length() - 1) != '"') {
      throw new IcebergDeletionException(
          Outcome.BAD_REQUEST, "If-Match must contain one strong deletion-action ETag");
    }
    return value.substring(1, value.length() - 1);
  }

  private static TableIdentifier identifier(String namespace, String table) {
    Namespace decodedNamespace =
        RESTUtil.decodeNamespace(namespace, IcebergRESTUtils.NAMESPACE_SEPARATOR_URLENCODED_UTF_8);
    return TableIdentifier.of(decodedNamespace, RESTUtil.decodeString(table));
  }

  static Response lifecycleError(IcebergDeletionException error) {
    int status;
    switch (error.outcome()) {
      case BAD_REQUEST:
        status = 400;
        break;
      case NOT_FOUND:
        status = 404;
        break;
      case CONFLICT:
        status = 409;
        break;
      case GONE:
        status = 410;
        break;
      case PRECONDITION_FAILED:
        status = 412;
        break;
      case PRECONDITION_REQUIRED:
        status = 428;
        break;
      default:
        throw new IllegalStateException("Unhandled deletion outcome " + error.outcome());
    }
    ErrorResponse response =
        IcebergRESTUtils.errorResponse(status, error.outcome().name(), error.getMessage());
    return Response.status(status).entity(response).type(MediaType.APPLICATION_JSON).build();
  }

  private static void requireDropAccess(
      String catalogName, TableIdentifier identifier, EntityDeletionPO deletion) {
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, catalogName, identifier, HierarchicalSchemaUtil.schemaSeparator());
    if (!IcebergDeletionAuthorization.canDrop(gravitinoIdentifier, deletion)) {
      throw new IcebergDeletionException(Outcome.NOT_FOUND, "Deletion action does not exist");
    }
  }
}
