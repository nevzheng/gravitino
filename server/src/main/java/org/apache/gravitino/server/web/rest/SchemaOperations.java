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
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.SchemaCreateRequest;
import org.apache.gravitino.dto.requests.SchemaUpdateRequest;
import org.apache.gravitino.dto.requests.SchemaUpdatesRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.EntityListResponse;
import org.apache.gravitino.dto.responses.SchemaResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/metalakes/{metalake}/catalogs/{catalog}/schemas")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SchemaOperations {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaOperations.class);

  private final SchemaDispatcher dispatcher;
  private final RecoverableDeletionManager recoverableDeletionManager;

  @Context private HttpServletRequest httpRequest;

  @Inject
  public SchemaOperations(
      SchemaDispatcher dispatcher, RecoverableDeletionManager recoverableDeletionManager) {
    this.dispatcher = dispatcher;
    this.recoverableDeletionManager = recoverableDeletionManager;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-schema", absolute = true)
  @AuthorizationExpression(
      expression =
          "SERVICE_ADMIN || ("
              + AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION
              + ")",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response listSchemas(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @DefaultValue("") @QueryParam("parentSchema") String parentSchema,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("name") String name,
      @QueryParam("id") String id) {
    LOG.info(
        "Received list schema request for catalog: {}.{}, parentSchema: {}",
        metalake,
        catalog,
        parentSchema);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace recoveryNamespace = NamespaceUtil.ofSchema(metalake, catalog);
            if ("deleted".equals(include)) {
              checkDeletedSchemaAccess(recoveryNamespace);
              if (StringUtils.isNotBlank(parentSchema)) {
                validateParentSchema(parentSchema);
              }
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              List<DeletedEntityDTO> deletedSchemas =
                  recoverableDeletionManager.listDeletedSchemas(
                      recoveryNamespace,
                      StringUtils.isBlank(parentSchema) ? null : parentSchema,
                      name,
                      entityId);
              return Utils.ok(
                  new DeletedEntityListResponse(deletedSchemas.toArray(new DeletedEntityDTO[0])));
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            Namespace schemaNS;
            if (StringUtils.isBlank(parentSchema)) {
              schemaNS = NamespaceUtil.ofSchema(metalake, catalog);
            } else {
              validateParentSchema(parentSchema);
              schemaNS = Namespace.of(metalake, catalog, parentSchema);
            }
            NameIdentifier[] idents = dispatcher.listSchemas(schemaNS);
            idents = idents == null ? new NameIdentifier[0] : idents;
            if (name != null) {
              idents =
                  Arrays.stream(idents)
                      .filter(ident -> name.equals(ident.name()))
                      .toArray(NameIdentifier[]::new);
            }
            idents =
                MetadataAuthzHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.SCHEMA,
                    idents);
            Response response = Utils.ok(new EntityListResponse(idents));
            LOG.info(
                "List {} schemas in catalog {}.{} (parentSchema='{}')",
                idents.length,
                metalake,
                catalog,
                parentSchema);
            return response;
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(OperationType.LIST, "", catalog, e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG, SCHEMA) || ANY_USE_CATALOG && ANY_CREATE_SCHEMA",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response createSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @AuthorizationRequest(type = AuthorizationRequest.RequestType.CREATE_SCHEMA)
          SchemaCreateRequest request) {
    LOG.info("Received create schema request: {}.{}.{}", metalake, catalog, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident =
                NameIdentifierUtil.ofSchema(metalake, catalog, request.getName());
            Schema schema =
                dispatcher.createSchema(ident, request.getComment(), request.getProperties());
            Response response = Utils.ok(new SchemaResponse(DTOConverters.toDTO(schema)));
            LOG.info("Schema created: {}.{}.{}", metalake, catalog, schema.name());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(
          OperationType.CREATE, request.getName(), catalog, e);
    }
  }

  @GET
  @Path("/{schema}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "load-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "load-schema", absolute = true)
  @AuthorizationExpression(
      expression =
          "SERVICE_ADMIN || ("
              + AuthorizationExpressionConstants.LOAD_SCHEMA_AUTHORIZATION_EXPRESSION
              + ")",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response loadSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("id") String id) {
    LOG.info("Received load schema request for schema: {}.{}.{}", metalake, catalog, schema);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace namespace = NamespaceUtil.ofSchema(metalake, catalog);
            if ("deleted".equals(include)) {
              checkDeletedSchemaAccess(namespace);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              if (entityId == null) {
                throw new IllegalArgumentException("id is required when loading a deleted schema");
              }
              DeletedEntityDTO deletedSchema =
                  recoverableDeletionManager.getDeletedSchema(namespace, schema, entityId);
              return Response.fromResponse(Utils.ok(new DeletedEntityResponse(deletedSchema)))
                  .tag(new EntityTag(deletedSchema.getEtag()))
                  .build();
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            NameIdentifier ident = NameIdentifierUtil.ofSchema(metalake, catalog, schema);
            Schema s = dispatcher.loadSchema(ident);
            Response response = Utils.ok(new SchemaResponse(DTOConverters.toDTO(s)));
            LOG.info("Schema loaded: {}.{}.{}", metalake, catalog, s.name());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(OperationType.LOAD, schema, catalog, e);
    }
  }

  /**
   * Restores one exact soft-deleted schema metadata generation.
   *
   * <p>When the original schema deletion cascaded, the recovery transaction restores the exact
   * metadata tree recorded by that deletion generation. No catalog connector or downstream system
   * is contacted.
   *
   * @param metalake metalake name
   * @param catalog catalog name
   * @param schema original logical schema name, including its configured hierarchy separator
   * @param include deleted-resource selector
   * @param id immutable schema identifier
   * @param ifMatch strong entity tag returned by the exact deleted-schema read
   * @param request merge patch that changes {@code deleted} to {@code false}
   * @return the restored schema response
   */
  @PATCH
  @Path("/{schema}")
  @Consumes("application/merge-patch+json")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "restore-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "restore-schema", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response restoreSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") String schema,
      @QueryParam("include") String include,
      @QueryParam("id") String id,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      EntityRestoreRequest request) {
    LOG.info("Received restore schema request: {}.{}.{}", metalake, catalog, schema);
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
                  "include=deleted is required when restoring a deleted schema");
            }
            Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
            if (entityId == null) {
              throw new IllegalArgumentException("id is required when restoring a deleted schema");
            }
            String etag = RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "schema");
            Namespace namespace = NamespaceUtil.ofSchema(metalake, catalog);
            checkDeletedSchemaAccess(namespace);
            SchemaEntity restored =
                recoverableDeletionManager.restoreDeletedSchema(namespace, schema, entityId, etag);
            return Utils.ok(new SchemaResponse(toDTO(restored)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(OperationType.RESTORE, schema, catalog, e);
    }
  }

  @PUT
  @Path("/{schema}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "alter-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "alter-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response alterSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      SchemaUpdatesRequest request) {
    LOG.info("Received alter schema request: {}.{}.{}", metalake, catalog, schema);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident = NameIdentifierUtil.ofSchema(metalake, catalog, schema);
            SchemaChange[] changes =
                request.getUpdates().stream()
                    .map(SchemaUpdateRequest::schemaChange)
                    .toArray(SchemaChange[]::new);
            Schema s = dispatcher.alterSchema(ident, changes);
            Response response = Utils.ok(new SchemaResponse(DTOConverters.toDTO(s)));
            LOG.info("Schema altered: {}.{}.{}", metalake, catalog, s.name());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(OperationType.ALTER, schema, catalog, e);
    }
  }

  @DELETE
  @Path("/{schema}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "drop-schema." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "drop-schema", absolute = true)
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response dropSchema(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @DefaultValue("false") @QueryParam("cascade") boolean cascade) {
    LOG.info("Received drop schema request: {}.{}.{}", metalake, catalog, schema);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier ident = NameIdentifierUtil.ofSchema(metalake, catalog, schema);
            boolean dropped = dispatcher.dropSchema(ident, cascade);

            if (dropped) {
              LOG.info("Schema dropped: {}.{}.{}", metalake, catalog, schema);
            } else {
              LOG.warn("Failed to drop schema {} under namespace {}", schema, ident.namespace());
            }

            Response response = Utils.ok(new DropResponse(dropped));
            return response;
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleSchemaException(OperationType.DROP, schema, catalog, e);
    }
  }

  /**
   * Validates the {@code parentSchema} query parameter. The value is a logical (possibly
   * hierarchical) schema name, so it must not contain empty segments (e.g. {@code "A::B"} or {@code
   * "A:"}) before it is passed to {@link Namespace#of}.
   *
   * @param parentSchema the non-blank {@code parentSchema} query parameter
   * @throws IllegalArgumentException if the value contains an empty segment
   */
  private static void validateParentSchema(String parentSchema) {
    String separator = HierarchicalSchemaUtil.schemaSeparator();
    for (String segment : HierarchicalSchemaUtil.splitSchemaName(parentSchema, separator)) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException(
            String.format(
                "The parentSchema '%s' contains an empty segment after splitting by '%s'.",
                parentSchema, separator));
      }
    }
  }

  private static SchemaDTO toDTO(SchemaEntity schema) {
    return SchemaDTO.builder()
        .withName(schema.name())
        .withComment(schema.comment())
        .withProperties(schema.properties())
        .withAudit(DTOConverters.toDTO(schema.auditInfo()))
        .build();
  }

  private static void checkDeletedSchemaAccess(Namespace schemaNamespace) {
    NameIdentifier catalogIdentifier = NameIdentifier.of(schemaNamespace.levels());
    if (!MetadataAuthzHelper.checkAccess(
        catalogIdentifier, Entity.EntityType.CATALOG, "SERVICE_ADMIN")) {
      throw new ForbiddenException(
          "Only a service administrator can read or restore deleted schemas under %s",
          schemaNamespace);
    }
  }
}
