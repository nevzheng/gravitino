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

import static org.apache.gravitino.Configs.CACHE_ENABLED;
import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS;
import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.TableResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.storage.relational.JDBCBackend;
import org.apache.gravitino.storage.relational.RelationalEntityStoreIdResolver;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.CatalogMetaService;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.apache.gravitino.storage.relational.service.SchemaMetaService;
import org.apache.gravitino.storage.relational.service.TableMetaService;
import org.apache.gravitino.utils.NamespaceUtil;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Exercises metadata-only table recovery across HTTP and the production relational-store path. */
public class TestTableRecoveryE2E extends BaseOperationsTest {

  private static final String VERSION_MEDIA_TYPE = "application/vnd.gravitino.v1+json";
  private static final String MERGE_PATCH_MEDIA_TYPE = "application/merge-patch+json";
  private static final long RETENTION_MS = 604_800_000L;
  private static final String METALAKE = "recovery_e2e_metalake";
  private static final String CATALOG = "recovery_e2e_catalog";
  private static final String SCHEMA = "recovery_e2e_schema";
  private static final String TABLE = "orders";
  private static final long METALAKE_ID = 900_001L;
  private static final long CATALOG_ID = 900_002L;
  private static final long SCHEMA_ID = 900_003L;
  private static final long TABLE_ID = 900_004L;
  private static final long COLUMN_ID = 900_005L;

  private static final TableDispatcher TABLE_DISPATCHER = mock(TableDispatcher.class);

  private static JDBCBackend backend;
  private static RecoverableDeletionManager recoverableDeletionManager;
  private static Path storageDirectory;
  private static Object previousConfig;
  private static Object previousLockManager;
  private static Object previousEntityIdResolver;

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  @BeforeAll
  public static void initializeRelationalStore() throws Exception {
    GravitinoEnv environment = GravitinoEnv.getInstance();
    previousConfig = FieldUtils.readField(environment, "config", true);
    previousLockManager = FieldUtils.readField(environment, "lockManager", true);
    previousEntityIdResolver =
        FieldUtils.readStaticField(EntityIdService.class, "entityIdResolver", true);

    storageDirectory = Files.createTempDirectory("gravitino-table-recovery-e2e-");
    ServerConfig config = new ServerConfig(false);
    config.set(
        ENTITY_RELATIONAL_JDBC_BACKEND_URL, "jdbc:h2:file:" + storageDirectory.resolve("metadata"));
    config.set(ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER, "org.h2.Driver");
    config.set(ENTITY_RELATIONAL_JDBC_BACKEND_USER, "root");
    config.set(ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD, "test");
    config.set(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS, 10);
    config.set(ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS, 1_000L);
    config.set(STORE_DELETE_AFTER_TIME, RETENTION_MS);
    config.set(CACHE_ENABLED, false);
    config.set(ENABLE_AUTHORIZATION, false);
    config.set(TREE_LOCK_MAX_NODE_IN_MEMORY, 100_000L);
    config.set(TREE_LOCK_MIN_NODE_IN_MEMORY, 1_000L);
    config.set(TREE_LOCK_CLEAN_INTERVAL, 36_000L);

    FieldUtils.writeField(environment, "config", config, true);
    FieldUtils.writeField(environment, "lockManager", new LockManager(config), true);

    backend = new JDBCBackend();
    backend.initialize(config);
    EntityIdService.initialize(new RelationalEntityStoreIdResolver());
    recoverableDeletionManager = new RecoverableDeletionManager(RETENTION_MS);

    when(TABLE_DISPATCHER.dropTable(any(NameIdentifier.class)))
        .thenAnswer(
            invocation ->
                backend.delete(
                    invocation.getArgument(0),
                    org.apache.gravitino.Entity.EntityType.TABLE,
                    false));
  }

  @AfterAll
  public static void closeRelationalStore() throws Exception {
    try {
      if (backend != null) {
        backend.close();
      }
    } finally {
      GravitinoEnv environment = GravitinoEnv.getInstance();
      FieldUtils.writeField(environment, "config", previousConfig, true);
      FieldUtils.writeField(environment, "lockManager", previousLockManager, true);
      FieldUtils.writeStaticField(
          EntityIdService.class, "entityIdResolver", previousEntityIdResolver, true);
      if (storageDirectory != null) {
        FileUtils.deleteDirectory(storageDirectory.toFile());
      }
    }
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(3001, 4000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(TableOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(TABLE_DISPATCHER).to(TableDispatcher.class).ranked(2);
            bind(recoverableDeletionManager).to(RecoverableDeletionManager.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @BeforeEach
  public void insertTableMetadata() throws Exception {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("recovery-e2e").withCreateTime(Instant.now()).build();
    MetalakeMetaService.getInstance()
        .insertMetalake(
            BaseMetalake.builder()
                .withId(METALAKE_ID)
                .withName(METALAKE)
                .withComment("table recovery E2E")
                .withProperties(Collections.emptyMap())
                .withAuditInfo(auditInfo)
                .withVersion(SchemaVersion.V_0_1)
                .build(),
            false);
    CatalogMetaService.getInstance()
        .insertCatalog(
            CatalogEntity.builder()
                .withId(CATALOG_ID)
                .withName(CATALOG)
                .withNamespace(Namespace.of(METALAKE))
                .withType(Catalog.Type.RELATIONAL)
                .withProvider("test")
                .withComment("table recovery E2E")
                .withProperties(Collections.emptyMap())
                .withAuditInfo(auditInfo)
                .build(),
            false);
    SchemaMetaService.getInstance()
        .insertSchema(
            SchemaEntity.builder()
                .withId(SCHEMA_ID)
                .withName(SCHEMA)
                .withNamespace(Namespace.of(METALAKE, CATALOG))
                .withComment("table recovery E2E")
                .withProperties(Collections.emptyMap())
                .withAuditInfo(auditInfo)
                .build(),
            false);

    ColumnEntity column =
        ColumnEntity.builder()
            .withId(COLUMN_ID)
            .withName("order_id")
            .withPosition(0)
            .withDataType(Types.LongType.get())
            .withComment("stable deletion-generation member")
            .withNullable(false)
            .withAutoIncrement(false)
            .withAuditInfo(auditInfo)
            .build();
    TableMetaService.getInstance()
        .insertTable(
            TableEntity.builder()
                .withId(TABLE_ID)
                .withName(TABLE)
                .withNamespace(NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA))
                .withColumns(List.of(column))
                .withAuditInfo(auditInfo)
                .build(),
            false);

    when(TABLE_DISPATCHER.loadTable(any(NameIdentifier.class)))
        .thenAnswer(
            invocation ->
                toTableDTO(
                    TableMetaService.getInstance()
                        .getTableByIdentifier(invocation.getArgument(0))));
  }

  @Test
  public void testDeleteDiscoverAndConditionallyRestoreTableGeneration() {
    NameIdentifier tableIdentifier = NameIdentifier.of(METALAKE, CATALOG, SCHEMA, TABLE);
    String tablePath = tablePath() + TABLE;

    Response deleteResponse =
        target(tablePath)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept(VERSION_MEDIA_TYPE)
            .delete();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), deleteResponse.getStatus());
    Assertions.assertTrue(deleteResponse.readEntity(DropResponse.class).dropped());
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> TableMetaService.getInstance().getTableByIdentifier(tableIdentifier));
    when(TABLE_DISPATCHER.loadTable(any(NameIdentifier.class)))
        .thenThrow(new RuntimeException("downstream table is absent"));

    Response discoveryResponse =
        target(tablePath())
            .queryParam("include", "deleted")
            .queryParam("name", TABLE)
            .queryParam("id", String.valueOf(TABLE_ID))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept(VERSION_MEDIA_TYPE)
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), discoveryResponse.getStatus());
    DeletedEntityListResponse discovery =
        discoveryResponse.readEntity(DeletedEntityListResponse.class);
    discovery.validate();
    Assertions.assertEquals(1, discovery.getDeletedEntities().length);
    Assertions.assertNull(discoveryResponse.getHeaderString("ETag"));

    Response exactReadResponse =
        target(tablePath)
            .queryParam("include", "deleted")
            .queryParam("id", String.valueOf(TABLE_ID))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept(VERSION_MEDIA_TYPE)
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), exactReadResponse.getStatus());
    String etag = exactReadResponse.getHeaderString("ETag");
    Assertions.assertNotNull(etag);
    DeletedEntityResponse exactRead = exactReadResponse.readEntity(DeletedEntityResponse.class);
    exactRead.validate();
    DeletedEntityDTO tombstone = exactRead.getDeletedEntity();
    Assertions.assertEquals(discovery.getDeletedEntities()[0], tombstone);
    Assertions.assertEquals(String.valueOf(TABLE_ID), tombstone.getId());
    Assertions.assertEquals('"' + tombstone.getEtag() + '"', etag);
    Assertions.assertTrue(tombstone.getRestorable());

    Response stalePatch =
        patchRestore(
            tablePath,
            String.valueOf(TABLE_ID),
            "\"deletion-stale-representation-0000000000000000000000000000000000000000000000000000000000000000\"");
    Assertions.assertEquals(
        Response.Status.PRECONDITION_FAILED.getStatusCode(), stalePatch.getStatus());
    ErrorResponse staleError = stalePatch.readEntity(ErrorResponse.class);
    staleError.validate();
    Assertions.assertEquals(ErrorConstants.TOMBSTONE_CHANGED_CODE, staleError.getCode());
    Assertions.assertEquals(TombstoneChangedException.class.getSimpleName(), staleError.getType());
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> TableMetaService.getInstance().getTableByIdentifier(tableIdentifier));

    Response restoreResponse = patchRestore(tablePath, String.valueOf(TABLE_ID), etag);
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), restoreResponse.getStatus());
    TableResponse restoreBody = restoreResponse.readEntity(TableResponse.class);
    restoreBody.validate();
    Assertions.assertEquals(TABLE, restoreBody.getTable().name());
    Mockito.verify(TABLE_DISPATCHER, Mockito.never()).loadTable(any(NameIdentifier.class));

    TableEntity restored = TableMetaService.getInstance().getTableByIdentifier(tableIdentifier);
    Assertions.assertEquals(TABLE_ID, restored.id());
    Assertions.assertEquals(1, restored.columns().size());
    Assertions.assertEquals(COLUMN_ID, restored.columns().get(0).id());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(tombstone.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(DeletionState.RESTORED, receipt.getState());
    Assertions.assertNotNull(receipt.getRestoredAt());

    when(TABLE_DISPATCHER.loadTable(any(NameIdentifier.class)))
        .thenAnswer(
            invocation ->
                toTableDTO(
                    TableMetaService.getInstance()
                        .getTableByIdentifier(invocation.getArgument(0))));
    Response liveReadResponse =
        target(tablePath).request(MediaType.APPLICATION_JSON_TYPE).accept(VERSION_MEDIA_TYPE).get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), liveReadResponse.getStatus());
    TableResponse liveRead = liveReadResponse.readEntity(TableResponse.class);
    liveRead.validate();
    Assertions.assertEquals(TABLE, liveRead.getTable().name());
    Assertions.assertEquals(1, liveRead.getTable().columns().length);
    Assertions.assertEquals("order_id", liveRead.getTable().columns()[0].name());

    Response replayResponse = patchRestore(tablePath, String.valueOf(TABLE_ID), etag);
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), replayResponse.getStatus());
    TableResponse replayBody = replayResponse.readEntity(TableResponse.class);
    replayBody.validate();
    Assertions.assertEquals(TABLE, replayBody.getTable().name());
    Assertions.assertEquals(1, replayBody.getTable().columns().length);
    Assertions.assertEquals("order_id", replayBody.getTable().columns()[0].name());
    Assertions.assertEquals(
        TABLE_ID, TableMetaService.getInstance().getTableByIdentifier(tableIdentifier).id());

    Response rediscoveryResponse =
        target(tablePath())
            .queryParam("include", "deleted")
            .queryParam("name", TABLE)
            .queryParam("id", String.valueOf(TABLE_ID))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept(VERSION_MEDIA_TYPE)
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), rediscoveryResponse.getStatus());
    Assertions.assertEquals(
        0,
        rediscoveryResponse
            .readEntity(DeletedEntityListResponse.class)
            .getDeletedEntities()
            .length);
  }

  private Response patchRestore(String tablePath, String id, String etag) {
    return target(tablePath)
        .queryParam("include", "deleted")
        .queryParam("id", id)
        .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept(VERSION_MEDIA_TYPE)
        .header("If-Match", etag)
        .method("PATCH", Entity.entity(new EntityRestoreRequest(false), MERGE_PATCH_MEDIA_TYPE));
  }

  @SuppressWarnings("unchecked")
  private static TableDTO toTableDTO(TableEntity table) {
    ColumnDTO[] columns =
        table.columns().stream()
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

  private static String tablePath() {
    return String.format("/metalakes/%s/catalogs/%s/schemas/%s/tables/", METALAKE, CATALOG, SCHEMA);
  }
}
