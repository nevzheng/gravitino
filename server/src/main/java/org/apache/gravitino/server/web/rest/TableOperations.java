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

import static org.apache.gravitino.dto.util.DTOConverters.fromDTO;
import static org.apache.gravitino.dto.util.DTOConverters.fromDTOs;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import java.util.Arrays;
import java.util.Comparator;
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
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.TableCreateRequest;
import org.apache.gravitino.dto.requests.TableUpdateRequest;
import org.apache.gravitino.dto.requests.TableUpdatesRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.EntityListResponse;
import org.apache.gravitino.dto.responses.TableResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.annotations.ExpressionCondition;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables")
public class TableOperations {

  private static final Logger LOG = LoggerFactory.getLogger(TableOperations.class);

  private final TableDispatcher dispatcher;
  private final RecoverableDeletionManager recoverableDeletionManager;

  @Context private HttpServletRequest httpRequest;

  @Inject
  public TableOperations(
      TableDispatcher dispatcher, RecoverableDeletionManager recoverableDeletionManager) {
    this.dispatcher = dispatcher;
    this.recoverableDeletionManager = recoverableDeletionManager;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-table", absolute = true)
  @AuthorizationExpression(
      expression =
          "SERVICE_ADMIN || ("
              + AuthorizationExpressionConstants.LOAD_SCHEMA_AUTHORIZATION_EXPRESSION
              + ")",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response listTables(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("name") String name,
      @QueryParam("id") String id) {
    LOG.info("Received list tables request for schema: {}.{}.{}", metalake, catalog, schema);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace tableNS = NamespaceUtil.ofTable(metalake, catalog, schema);
            if ("deleted".equals(include)) {
              checkDeletedTableAccess(tableNS);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              List<DeletedEntityDTO> deletedTables =
                  recoverableDeletionManager.listDeletedTables(tableNS, name, entityId);
              DeletedEntityListResponse body =
                  new DeletedEntityListResponse(deletedTables.toArray(new DeletedEntityDTO[0]));
              Response response = Utils.ok(body);
              LOG.info(
                  "List {} deleted tables under schema: {}.{}.{}",
                  deletedTables.size(),
                  metalake,
                  catalog,
                  schema);
              return response;
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException(
                  "include must be one of non-deleted or deleted in the table POC");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            NameIdentifier[] idents = dispatcher.listTables(tableNS);
            if (name != null) {
              idents =
                  Arrays.stream(idents)
                      .filter(ident -> name.equals(ident.name()))
                      .toArray(NameIdentifier[]::new);
            }
            idents =
                MetadataAuthzHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.TABLE,
                    idents);
            Response response = Utils.ok(new EntityListResponse(idents));
            LOG.info(
                "List {} tables under schema: {}.{}.{}", idents.length, metalake, catalog, schema);
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.LIST, "", schema, e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-table", absolute = true)
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
      TableCreateRequest request) {
    LOG.info(
        "Received create table request: {}.{}.{}.{}", metalake, catalog, schema, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, request.getName());

            Table table =
                dispatcher.createTable(
                    ident,
                    fromDTOs(request.getColumns()),
                    request.getComment(),
                    request.getProperties(),
                    fromDTOs(request.getPartitioning()),
                    fromDTO(request.getDistribution()),
                    fromDTOs(request.getSortOrders()),
                    fromDTOs(request.getIndexes()));
            Response response = Utils.ok(new TableResponse(DTOConverters.toDTO(table)));
            LOG.info("Table created: {}.{}.{}.{}", metalake, catalog, schema, request.getName());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(
          OperationType.CREATE, request.getName(), schema, e);
    }
  }

  @GET
  @Path("{table}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "load-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "load-table", absolute = true)
  @AuthorizationExpression(
      expression =
          "SERVICE_ADMIN || ("
              + AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION
              + ")",
      secondaryExpression = AuthorizationExpressionConstants.MODIFY_TABLE_AUTHORIZATION_EXPRESSION,
      secondaryExpressionCondition = ExpressionCondition.REQUIRED_MODIFY_PRIVILEGES,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response loadTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @QueryParam("include") @DefaultValue("non-deleted") String include,
      @QueryParam("id") String id,
      @QueryParam("privileges")
          @AuthorizationRequest(type = AuthorizationRequest.RequestType.LOAD_TABLE)
          String requiredPrivileges) {
    LOG.info(
        "Received load table request for table: {}.{}.{}.{}", metalake, catalog, schema, table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace tableNamespace = NamespaceUtil.ofTable(metalake, catalog, schema);
            if ("deleted".equals(include)) {
              checkDeletedTableAccess(tableNamespace);
              Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
              if (entityId == null) {
                throw new IllegalArgumentException("id is required when loading a deleted table");
              }
              DeletedEntityDTO deletedTable =
                  recoverableDeletionManager.getDeletedTable(tableNamespace, table, entityId);
              Response response =
                  Response.fromResponse(Utils.ok(new DeletedEntityResponse(deletedTable)))
                      .tag(new EntityTag(deletedTable.getEtag()))
                      .build();
              LOG.info(
                  "Deleted table loaded: {}.{}.{}.{} ({})",
                  metalake,
                  catalog,
                  schema,
                  table,
                  entityId);
              return response;
            }
            if (!"non-deleted".equals(include)) {
              throw new IllegalArgumentException("include must be non-deleted or deleted");
            }
            if (id != null) {
              throw new IllegalArgumentException(
                  "The id filter currently requires include=deleted");
            }
            NameIdentifier ident = NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            Table t = dispatcher.loadTable(ident);
            Response response = Utils.ok(new TableResponse(DTOConverters.toDTO(t)));
            LOG.info("Table loaded: {}.{}.{}.{}", metalake, catalog, schema, table);
            return response;
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.LOAD, table, schema, e);
    }
  }

  @PUT
  @Path("{table}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "alter-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "alter-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.MODIFY_TABLE_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response alterTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      TableUpdatesRequest request) {
    LOG.info("Received alter table request: {}.{}.{}.{}", metalake, catalog, schema, table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident = NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            TableChange[] changes =
                request.getUpdates().stream()
                    .map(TableUpdateRequest::tableChange)
                    .toArray(TableChange[]::new);
            Table t = dispatcher.alterTable(ident, changes);
            Response response = Utils.ok(new TableResponse(DTOConverters.toDTO(t)));
            LOG.info("Table altered: {}.{}.{}.{}", metalake, catalog, schema, t.name());
            return response;
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.ALTER, table, schema, e);
    }
  }

  @PATCH
  @Path("{table}")
  @Consumes("application/merge-patch+json")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "restore-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "restore-table", absolute = true)
  @AuthorizationExpression(
      expression = "SERVICE_ADMIN",
      accessMetadataType = MetadataObject.Type.SCHEMA)
  public Response restoreTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") String table,
      @QueryParam("include") String include,
      @QueryParam("id") String id,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      EntityRestoreRequest request) {
    LOG.info("Received restore table request: {}.{}.{}.{}", metalake, catalog, schema, table);
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
                  "include=deleted is required when restoring a deleted table");
            }
            Long entityId = RecoveryRequestUtils.parsePositiveEntityId(id);
            if (entityId == null) {
              throw new IllegalArgumentException("id is required when restoring a deleted table");
            }
            String etag = RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "table");
            Namespace tableNamespace = NamespaceUtil.ofTable(metalake, catalog, schema);
            checkDeletedTableAccess(tableNamespace);
            TableEntity restored =
                recoverableDeletionManager.restoreDeletedTable(
                    tableNamespace, table, entityId, etag);
            Response response = Utils.ok(new TableResponse(toTableDTO(restored)));
            LOG.info(
                "Table restored: {}.{}.{}.{} ({})", metalake, catalog, schema, table, entityId);
            return response;
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.RESTORE, table, schema, e);
    }
  }

  @DELETE
  @Path("{table}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "drop-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "drop-table", absolute = true)
  @AuthorizationExpression(
      expression =
          """
              ANY(OWNER, METALAKE, CATALOG) ||
              SCHEMA_OWNER_WITH_USE_CATALOG ||
              ANY_USE_CATALOG && ANY_USE_SCHEMA  && TABLE::OWNER
              """,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response dropTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @QueryParam("purge") @DefaultValue("false") boolean purge) {
    LOG.info(
        "Received {} table request: {}.{}.{}.{}",
        purge ? "purge" : "drop",
        metalake,
        catalog,
        schema,
        table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier ident = NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            boolean dropped = purge ? dispatcher.purgeTable(ident) : dispatcher.dropTable(ident);
            if (dropped) {
              LOG.info(
                  "Table {}: {}.{}.{}.{}",
                  purge ? "purge" : "drop",
                  metalake,
                  catalog,
                  schema,
                  table);
            } else {
              LOG.warn("Cannot find to be dropped table {} under schema {}", table, schema);
            }

            return Utils.ok(new DropResponse(dropped));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.DROP, table, schema, e);
    }
  }

  private static TableDTO toTableDTO(TableEntity table) {
    ColumnDTO[] columns =
        table.columns().stream()
            .sorted(Comparator.comparingInt(column -> column.position()))
            .map(
                column ->
                    ColumnDTO.builder()
                        .withName(column.name())
                        .withDataType(column.dataType())
                        .withComment(column.comment())
                        .withNullable(column.nullable())
                        .withAutoIncrement(column.autoIncrement())
                        .withDefaultValue(column.defaultValue())
                        .build())
            .toArray(ColumnDTO[]::new);
    return TableDTO.builder()
        .withName(table.name())
        .withComment(table.comment())
        .withColumns(columns)
        .withProperties(table.properties())
        .withAudit(DTOConverters.toDTO(table.auditInfo()))
        .withDistribution(DTOConverters.toDTO(table.distribution()))
        .withSortOrders(DTOConverters.toDTOs(table.sortOrders()))
        .withPartitioning(DTOConverters.toDTOs(table.partitioning()))
        .withIndex(DTOConverters.toDTOs(table.indexes()))
        .build();
  }

  private static void checkDeletedTableAccess(Namespace tableNamespace) {
    NameIdentifier schemaIdentifier = NameIdentifier.of(tableNamespace.levels());
    if (!MetadataAuthzHelper.checkAccess(
        schemaIdentifier, Entity.EntityType.SCHEMA, "SERVICE_ADMIN")) {
      throw new ForbiddenException(
          "Only a service administrator can read or restore deleted tables under %s",
          tableNamespace);
    }
  }
}
