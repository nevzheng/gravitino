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
package org.apache.gravitino.iceberg.service.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.cache.EntityChangeRecord;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

/** Cross-backend transaction tests for the Iceberg-specific deletion coordinator. */
public class TestIcebergTableDeletionLifecycle extends TestJDBCBackend {

  private static final String METALAKE = "iceberg_deletion_metalake";
  private static final String CATALOG = "iceberg_deletion_catalog";
  private static final String SCHEMA = "sales";
  private static final String TABLE = "orders";

  private IcebergCatalogWrapperManager wrapperManager;
  private CatalogWrapperForREST wrapper;
  private IcebergRequestContext requestContext;
  private TableIdentifier icebergIdentifier;
  private NameIdentifier gravitinoIdentifier;
  private LoadTableResponse loadResponse;

  @BeforeEach
  public void setUpLifecycle() throws IOException {
    when(GravitinoEnv.getInstance().config().get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);
    createParentEntities(METALAKE, CATALOG, SCHEMA, AUDIT_INFO);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    gravitinoIdentifier = NameIdentifier.of(namespace, TABLE);
    TableEntity table =
        createTableEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, TABLE, AUDIT_INFO);
    backend.insert(table, false);

    wrapperManager = mock(IcebergCatalogWrapperManager.class);
    wrapper = mock(CatalogWrapperForREST.class);
    requestContext = mock(IcebergRequestContext.class);
    icebergIdentifier = TableIdentifier.of(org.apache.iceberg.catalog.Namespace.of(SCHEMA), TABLE);
    loadResponse = mock(LoadTableResponse.class);
    TableMetadata metadata = mock(TableMetadata.class);
    when(metadata.uuid()).thenReturn("70d8f29b-4d9c-4ef2-ae43-57b445fc83a1");
    when(metadata.metadataFileLocation())
        .thenReturn("s3://warehouse/sales/orders/v1.metadata.json");
    when(wrapperManager.getCatalogWrapper(CATALOG)).thenReturn(wrapper);
    when(wrapper.loadTableMetadata(icebergIdentifier)).thenReturn(metadata);
    when(wrapper.fileIOImpl()).thenReturn("org.apache.iceberg.io.ResolvingFileIO");
    when(wrapper.loadTable(icebergIdentifier)).thenReturn(loadResponse);
    when(requestContext.catalogName()).thenReturn(CATALOG);
    when(requestContext.userName()).thenReturn("alice");
    when(requestContext.httpHeaders())
        .thenReturn(Map.of("X-Request-ID", "request-1", "X-Correlation-ID", "correlation-1"));

    IcebergConfigProvider provider = mock(IcebergConfigProvider.class);
    when(provider.getMetalakeName()).thenReturn(METALAKE);
    when(provider.getDefaultCatalogName()).thenReturn(CATALOG);
    IcebergRESTServerContext.create(provider, false, true, true, wrapperManager);
  }

  @TestTemplate
  public void testDeleteAndUndropAreRelationalTransactionsOnly() throws IOException, SQLException {
    IcebergTableDeletionLifecycle lifecycle = lifecycle(true, 86_400_000L);
    long beforeChange = maxChangeId();

    lifecycle.delete(requestContext, icebergIdentifier, true);

    assertFalse(backend.exists(gravitinoIdentifier, Entity.EntityType.TABLE));
    EntityDeletionPO deletion = onlyDeletion();
    assertEquals(IcebergTableDeletionLifecycle.DELETED, deletion.getState());
    assertEquals(deletion.getDeletedAt() + 86_400_000L, deletion.getRetentionExpiresAt());
    assertTrue(lifecycle.isNameReserved(CATALOG, icebergIdentifier));
    IcebergDeletionContextPO icebergContext =
        new IcebergDeletionContextStore().get(deletion.getDeletionId());
    assertNotNull(icebergContext);
    assertEquals(
        "s3://warehouse/sales/orders/v1.metadata.json", icebergContext.getMetadataLocation());
    assertEquals("catalog-id:" + deletion.getCatalogId(), icebergContext.getProtectedFileIoRef());
    verify(wrapper, never()).dropTable(icebergIdentifier);
    verify(wrapper, never()).purgeTable(icebergIdentifier);
    assertChange(beforeChange, OperateType.DROP);

    String etag = IcebergDeletionETags.strongTag(deletion);
    long beforeRestoreChange = maxChangeId();
    assertSame(
        loadResponse,
        lifecycle.undrop(
            requestContext,
            icebergIdentifier,
            deletion.getDeletionId(),
            etag,
            deletion.getDeletedAt() + 1));

    assertTrue(backend.exists(gravitinoIdentifier, Entity.EntityType.TABLE));
    assertFalse(lifecycle.isNameReserved(CATALOG, icebergIdentifier));
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    assertEquals(IcebergTableDeletionLifecycle.RESTORED, receipt.getState());
    assertEquals(etag, receipt.getAcceptedRestoreEtag());
    assertChange(beforeRestoreChange, OperateType.ALTER);

    assertSame(
        loadResponse,
        lifecycle.undrop(
            requestContext,
            icebergIdentifier,
            deletion.getDeletionId(),
            etag,
            deletion.getDeletedAt() + 2));
    assertEquals(1L, countAuditEvents(deletion.getDeletionId(), "UNDROP_RESTORED"));
  }

  @TestTemplate
  public void testPrdConfigurationMatrixIsCapturedAtDeleteTime() throws SQLException {
    IcebergTableDeletionLifecycle disabled = lifecycle(false, 86_400_000L);
    assertFalse(disabled.manages(false));
    assertTrue(disabled.manages(true));

    disabled.delete(requestContext, icebergIdentifier, true);
    EntityDeletionPO immediate = onlyDeletion();
    assertNull(immediate.getRetentionExpiresAt());
    assertTrue(immediate.getPurgeRequested());

    IcebergDeletionException notRecoverable =
        assertThrows(
            IcebergDeletionException.class,
            () -> disabled.discover(CATALOG, icebergIdentifier, System.currentTimeMillis()));
    assertEquals(Outcome.NOT_FOUND, notRecoverable.outcome());
  }

  @TestTemplate
  public void testRetentionZeroIsImmediatelyGone() throws SQLException {
    IcebergTableDeletionLifecycle lifecycle = lifecycle(true, 0L);
    lifecycle.delete(requestContext, icebergIdentifier, false);
    EntityDeletionPO deletion = onlyDeletion();
    assertEquals(deletion.getDeletedAt(), deletion.getRetentionExpiresAt());

    IcebergDeletionException gone =
        assertThrows(
            IcebergDeletionException.class,
            () -> lifecycle.discover(CATALOG, icebergIdentifier, deletion.getDeletedAt()));
    assertEquals(Outcome.GONE, gone.outcome());
  }

  @TestTemplate
  public void testPurgeClaimWinsUndropCasBoundary() throws IOException, SQLException {
    IcebergTableDeletionLifecycle lifecycle = lifecycle(true, 86_400_000L);
    lifecycle.delete(requestContext, icebergIdentifier, false);
    EntityDeletionPO deletion = onlyDeletion();
    execute(
        "UPDATE entity_deletion SET state = 'PURGING', revision = revision + 1, "
            + "purge_job_id = 'job-1' WHERE deletion_id = '"
            + deletion.getDeletionId()
            + "'");

    IcebergDeletionException gone =
        assertThrows(
            IcebergDeletionException.class,
            () ->
                lifecycle.undrop(
                    requestContext,
                    icebergIdentifier,
                    deletion.getDeletionId(),
                    IcebergDeletionETags.strongTag(deletion),
                    deletion.getDeletedAt() + 1));
    assertEquals(Outcome.GONE, gone.outcome());
    assertFalse(backend.exists(gravitinoIdentifier, Entity.EntityType.TABLE));
  }

  private IcebergTableDeletionLifecycle lifecycle(boolean enabled, long retentionMs) {
    Map<String, String> properties = new HashMap<>();
    properties.put("soft-delete.enabled", String.valueOf(enabled));
    properties.put("soft-delete.retention-ms", String.valueOf(retentionMs));
    return new IcebergTableDeletionLifecycle(wrapperManager, new IcebergConfig(properties));
  }

  private EntityDeletionPO onlyDeletion() throws SQLException {
    String deletionId = selectString("SELECT deletion_id FROM entity_deletion");
    EntityDeletionPO deletion = EntityDeletionService.getInstance().get(deletionId);
    assertNotNull(deletion);
    return deletion;
  }

  private long maxChangeId() {
    return SessionUtils.doWithCommitAndFetchResult(
        EntityChangeLogMapper.class, EntityChangeLogMapper::selectMaxChangeId);
  }

  private void assertChange(long after, OperateType expected) {
    List<EntityChangeRecord> changes =
        SessionUtils.doWithCommitAndFetchResult(
            EntityChangeLogMapper.class, mapper -> mapper.selectEntityChanges(after, 10));
    assertEquals(1, changes.size());
    assertEquals(expected, changes.get(0).getOperateType());
    assertEquals(gravitinoIdentifier.toString(), changes.get(0).getFullName());
  }

  private long countAuditEvents(String deletionId, String eventType) throws SQLException {
    return selectLong(
        "SELECT COUNT(*) FROM entity_deletion_audit WHERE deletion_id = '"
            + deletionId
            + "' AND event_type = '"
            + eventType
            + "'");
  }

  private void execute(String sql) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private String selectString(String sql) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      assertTrue(resultSet.next());
      return resultSet.getString(1);
    }
  }

  private long selectLong(String sql) throws SQLException {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      assertTrue(resultSet.next());
      return resultSet.getLong(1);
    }
  }
}
