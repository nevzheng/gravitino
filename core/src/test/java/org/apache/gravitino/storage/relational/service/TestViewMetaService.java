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
package org.apache.gravitino.storage.relational.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.mapper.ViewMetaMapper;
import org.apache.gravitino.storage.relational.mapper.ViewVersionInfoMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.mockito.Mockito;

public class TestViewMetaService extends TestJDBCBackend {

  private final String metalakeName = GravitinoITUtils.genRandomName("tst_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("tst_view_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("tst_view_schema");

  @BeforeEach
  public void prepare() throws IOException, IllegalAccessException {
    Config config = GravitinoEnv.getInstance().config();
    Mockito.when(config.get(Configs.TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100_000L);
    Mockito.when(config.get(Configs.TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1_000L);
    Mockito.when(config.get(Configs.TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36_000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    createAndInsertMakeLake(metalakeName);
    createAndInsertCatalog(metalakeName, catalogName);
    createAndInsertSchema(metalakeName, catalogName, schemaName);
  }

  @TestTemplate
  public void testInsertAlreadyExistsException() throws IOException {
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, "test_view", AUDIT_INFO);
    ViewEntity viewCopy =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, "test_view", AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view, false);
    assertThrows(
        EntityAlreadyExistsException.class,
        () -> ViewMetaService.getInstance().insertView(viewCopy, false));
  }

  @TestTemplate
  public void testInsertAndGetView() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view, false);

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    ViewEntity loaded = ViewMetaService.getInstance().getViewByIdentifier(viewIdent);

    assertNotNull(loaded);
    assertEquals(view.id(), loaded.id());
    assertEquals(view.name(), loaded.name());
    assertEquals(view.comment(), loaded.comment());
    assertEquals(view.defaultCatalog(), loaded.defaultCatalog());
    assertEquals(view.defaultSchema(), loaded.defaultSchema());
    assertEquals(view.columns().length, loaded.columns().length);
    assertEquals(view.representations().length, loaded.representations().length);
    assertEquals(view.auditInfo().creator(), loaded.auditInfo().creator());
  }

  @TestTemplate
  public void testListViews() throws IOException {
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);

    String viewName1 = GravitinoITUtils.genRandomName("test_view1");
    ViewEntity view1 =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName1, AUDIT_INFO);

    String viewName2 = GravitinoITUtils.genRandomName("test_view2");
    ViewEntity view2 =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName2, AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view1, false);
    ViewMetaService.getInstance().insertView(view2, false);

    List<ViewEntity> views = ViewMetaService.getInstance().listViewsByNamespace(ns);

    assertEquals(2, views.size());
    assertTrue(views.stream().anyMatch(v -> v.name().equals(viewName1)));
    assertTrue(views.stream().anyMatch(v -> v.name().equals(viewName2)));
  }

  @TestTemplate
  public void testUpdateView() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view, false);

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    ViewEntity updated =
        ViewEntity.builder()
            .withId(view.id())
            .withName(view.name())
            .withNamespace(ns)
            .withComment("updated comment")
            .withColumns(view.columns())
            .withRepresentations(view.representations())
            .withDefaultCatalog("updated_catalog")
            .withDefaultSchema("updated_schema")
            .withProperties(view.properties())
            .withAuditInfo(AUDIT_INFO)
            .build();

    ViewEntity result = ViewMetaService.getInstance().updateView(viewIdent, e -> updated);
    assertEquals("updated comment", result.comment());
    assertEquals("updated_catalog", result.defaultCatalog());
    assertEquals("updated_schema", result.defaultSchema());

    Map<Integer, Long> versions = listViewVersions(view.id());
    assertEquals(2, versions.size());
    assertTrue(versions.containsKey(1));
    assertTrue(versions.containsKey(2));
  }

  @TestTemplate
  public void testUpdateViewRollbackWhenMetaUpdateAffectsZeroRows() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);
    ViewMetaService.getInstance().insertView(view, false);

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    ViewEntity updated =
        ViewEntity.builder()
            .withId(view.id())
            .withName(view.name())
            .withNamespace(ns)
            .withComment("updated comment")
            .withColumns(view.columns())
            .withRepresentations(view.representations())
            .withDefaultCatalog("updated_catalog")
            .withDefaultSchema("updated_schema")
            .withProperties(view.properties())
            .withAuditInfo(AUDIT_INFO)
            .build();

    assertThrows(
        TombstoneChangedException.class,
        () ->
            ViewMetaService.getInstance()
                .updateView(
                    viewIdent,
                    e -> {
                      ViewMetaService.getInstance().deleteView(viewIdent);
                      return updated;
                    }));

    Map<Integer, Long> versions = listViewVersions(view.id());
    assertEquals(1, versions.size());
    assertTrue(versions.containsKey(1));
    assertEquals(0L, versions.get(1));
    assertViewMetadataEquals(view, ViewMetaService.getInstance().getViewByIdentifier(viewIdent));
    assertEquals(
        0,
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.VIEW,
                EntityIdService.getEntityId(
                    NameIdentifier.of(ns.levels()), Entity.EntityType.SCHEMA),
                null,
                view.id(),
                null)
            .size());
  }

  @TestTemplate
  public void testDeleteView() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view, false);

    // Set up tag relation
    TagEntity tag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("tag1")
            .withNamespace(NamespaceUtil.ofTag(metalakeName))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            view.nameIdentifier(),
            view.type(),
            new NameIdentifier[] {NameIdentifierUtil.ofTag(metalakeName, tag.name())},
            new NameIdentifier[0]);
    assertEquals(1, countActiveTagRelForMetadataObject(view.id(), "VIEW"));

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    assertTrue(ViewMetaService.getInstance().deleteView(viewIdent));

    assertThrows(
        NoSuchEntityException.class,
        () -> ViewMetaService.getInstance().getViewByIdentifier(viewIdent));
    assertEquals(0, countActiveTagRelForMetadataObject(view.id(), "VIEW"));
  }

  @TestTemplate
  public void testRestoreExactViewAggregatePreservesIndependentTombstones() throws IOException {
    Namespace namespace = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("recoverable_view"),
            AUDIT_INFO);
    ViewMetaService.getInstance().insertView(view, false);

    UserEntity owner =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofUserNamespace(metalakeName),
            GravitinoITUtils.genRandomName("view_owner"),
            AUDIT_INFO);
    backend.insert(owner, false);
    OwnerMetaService.getInstance()
        .setOwner(
            view.nameIdentifier(), Entity.EntityType.VIEW, owner.nameIdentifier(), owner.type());

    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            SecurableObjects.ofCatalog(
                catalogName, Lists.newArrayList(Privileges.UseCatalog.allow())),
            schemaName,
            Lists.newArrayList(Privileges.UseSchema.allow()));
    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofRoleNamespace(metalakeName),
            GravitinoITUtils.genRandomName("view_role"),
            AUDIT_INFO,
            Lists.newArrayList(
                SecurableObjects.ofView(
                    schemaObject, view.name(), Lists.newArrayList(Privileges.SelectView.allow()))),
            ImmutableMap.of());
    RoleMetaService.getInstance().insertRole(role, false);

    TagEntity retainedTag = newTag("retained_view_tag");
    TagEntity independentlyRemovedTag = newTag("removed_view_tag");
    TagMetaService.getInstance().insertTag(retainedTag, false);
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            view.nameIdentifier(),
            Entity.EntityType.VIEW,
            new NameIdentifier[] {
              retainedTag.nameIdentifier(), independentlyRemovedTag.nameIdentifier()
            },
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            view.nameIdentifier(),
            Entity.EntityType.VIEW,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});

    ViewEntity versionTwo = copyWithComment(view, "version two");
    ViewMetaService.getInstance().updateView(view.nameIdentifier(), ignored -> versionTwo);
    ViewEntity current = copyWithComment(view, "version three");
    ViewMetaService.getInstance().updateView(view.nameIdentifier(), ignored -> current);

    long deletedAt = Instant.now().toEpochMilli();
    long independentDeletedAt = deletedAt - 1L;
    assertEquals(
        1,
        updateRows(
            "UPDATE view_version_info SET deleted_at = ?, deletion_id = NULL"
                + " WHERE view_id = ? AND version = 1 AND deleted_at = 0",
            independentDeletedAt,
            view.id()));
    assertTrue(
        ViewMetaService.getInstance()
            .deleteView(view.nameIdentifier(), deletedAt, Configs.DEFAULT_STORE_DELETE_AFTER_TIME));

    assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM view_version_info WHERE view_id = ?"
                + " AND version = 1 AND deleted_at = ? AND deletion_id IS NULL",
            view.id(),
            independentDeletedAt));
    assertEquals(
        2,
        countRows(
            "SELECT COUNT(*) FROM view_version_info WHERE view_id = ?"
                + " AND version IN (2, 3) AND deleted_at = ? AND deletion_id IS NOT NULL",
            view.id(),
            deletedAt));

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.VIEW, schemaId, view.name(), view.id(), DeletionState.DELETED)
            .get(0);
    ViewEntity restored =
        ViewMetaService.getInstance()
            .restoreView(
                view.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "view-restore-etag",
                deletion.getExpiresAt());

    assertViewMetadataEquals(current, restored);
    assertEquals(1, countActiveRelation("owner_meta", "metadata_object_type", view.id()));
    assertEquals(1, countActiveRelation("role_meta_securable_object", "type", view.id()));
    assertEquals(1, countActiveTagRelation(view.id(), retainedTag.id()));
    assertEquals(0, countActiveTagRelation(view.id(), independentlyRemovedTag.id()));
    assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM view_version_info WHERE view_id = ?"
                + " AND version = 1 AND deleted_at = ? AND deletion_id IS NULL",
            view.id(),
            independentDeletedAt));
    assertEquals(
        2,
        countRows(
            "SELECT COUNT(*) FROM view_version_info WHERE view_id = ?"
                + " AND version IN (2, 3) AND deleted_at = 0 AND deletion_id IS NULL",
            view.id()));
    assertEquals(
        view.id(),
        ViewMetaService.getInstance()
            .restoreView(
                view.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "view-restore-etag",
                deletion.getExpiresAt())
            .id());
  }

  @TestTemplate
  public void testViewNameCollisionLatestGenerationAndOverwriteGuard() throws IOException {
    Namespace namespace = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    String name = GravitinoITUtils.genRandomName("repeated_view");
    ViewEntity original =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
    ViewMetaService.getInstance().insertView(original, false);
    assertTrue(ViewMetaService.getInstance().deleteView(original.nameIdentifier()));

    RecoveryConflictException overwriteFailure =
        assertThrows(
            RecoveryConflictException.class,
            () -> ViewMetaService.getInstance().insertView(original, true));
    assertEquals(RecoveryConflictReason.ENTITY_ID_REUSED, overwriteFailure.getReason());

    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
    ViewEntity replacement =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
    ViewMetaService.getInstance().insertView(replacement, false);
    DeletedEntityDTO occupied = manager.getDeletedView(namespace, name, original.id());
    assertEquals("NAME_OCCUPIED", occupied.getReason());
    RecoveryConflictException occupiedFailure =
        assertThrows(
            RecoveryConflictException.class,
            () -> manager.restoreDeletedView(namespace, name, original.id(), occupied.getEtag()));
    assertEquals(RecoveryConflictReason.NAME_OCCUPIED, occupiedFailure.getReason());

    assertTrue(ViewMetaService.getInstance().deleteView(replacement.nameIdentifier()));
    DeletedEntityDTO oldGeneration = manager.getDeletedView(namespace, name, original.id());
    assertFalse(oldGeneration.getLatestForName());
    assertEquals("NOT_LATEST_TOMBSTONE", oldGeneration.getReason());
    RecoveryConflictException latestFailure =
        assertThrows(
            RecoveryConflictException.class,
            () ->
                manager.restoreDeletedView(
                    namespace, name, original.id(), oldGeneration.getEtag()));
    assertEquals(RecoveryConflictReason.NOT_LATEST_TOMBSTONE, latestFailure.getReason());
  }

  @TestTemplate
  public void testGetNonExistentView() {
    NameIdentifier viewIdent =
        NameIdentifier.of(metalakeName, catalogName, schemaName, "non_existent_view");
    assertThrows(
        NoSuchEntityException.class,
        () -> ViewMetaService.getInstance().getViewByIdentifier(viewIdent));
  }

  @TestTemplate
  public void testInsertViewWithOverwrite() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);

    ViewMetaService.getInstance().insertView(view, false);

    ViewEntity newView =
        ViewEntity.builder()
            .withId(view.id())
            .withName(view.name())
            .withNamespace(ns)
            .withComment("overwritten comment")
            .withColumns(view.columns())
            .withRepresentations(view.representations())
            .withDefaultCatalog(view.defaultCatalog())
            .withDefaultSchema(view.defaultSchema())
            .withProperties(view.properties())
            .withAuditInfo(AUDIT_INFO)
            .build();

    ViewMetaService.getInstance().insertView(newView, true);

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    ViewEntity loaded = ViewMetaService.getInstance().getViewByIdentifier(viewIdent);
    assertEquals("overwritten comment", loaded.comment());
  }

  @TestTemplate
  public void testViewLifeCycle() throws IOException {
    String viewName = GravitinoITUtils.genRandomName("test_view");
    Namespace ns = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), ns, viewName, AUDIT_INFO);
    ViewMetaService.getInstance().insertView(view, false);

    NameIdentifier viewIdent = NameIdentifier.of(metalakeName, catalogName, schemaName, viewName);
    ViewEntity v2 =
        ViewEntity.builder()
            .withId(view.id())
            .withName(view.name())
            .withNamespace(ns)
            .withComment("v2")
            .withColumns(view.columns())
            .withRepresentations(view.representations())
            .withDefaultCatalog(view.defaultCatalog())
            .withDefaultSchema(view.defaultSchema())
            .withProperties(view.properties())
            .withAuditInfo(AUDIT_INFO)
            .build();
    ViewMetaService.getInstance().updateView(viewIdent, e -> v2);

    long deletedAt = Instant.now().minusSeconds(60).toEpochMilli();
    assertTrue(ViewMetaService.getInstance().deleteView(viewIdent, deletedAt, 0L));
    assertEquals(
        0,
        ViewMetaService.getInstance()
            .deleteViewMetasByLegacyTimeline(Instant.now().toEpochMilli(), 100));
    assertEquals(
        1, backend.hardDeleteLegacyData(Entity.EntityType.VIEW, Instant.now().toEpochMilli()));
    assertEquals(0, listViewVersions(view.id()).size());
  }

  @TestTemplate
  public void testRecordedAndLegacyViewDeletionsUseSeparatePurgePaths() throws IOException {
    Namespace namespace = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity recorded =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("recorded_purge_view"),
            AUDIT_INFO);
    ViewMetaService.getInstance().insertView(recorded, false);
    TagEntity independentlyRemovedTag = newTag("purged_view_removed_tag");
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            recorded.nameIdentifier(),
            Entity.EntityType.VIEW,
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()},
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            recorded.nameIdentifier(),
            Entity.EntityType.VIEW,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});
    long deletedAt = Instant.now().minusSeconds(60).toEpochMilli();
    assertTrue(ViewMetaService.getInstance().deleteView(recorded.nameIdentifier(), deletedAt, 0L));

    assertEquals(
        0,
        ViewMetaService.getInstance()
            .deleteViewMetasByLegacyTimeline(Instant.now().toEpochMilli(), 100));
    assertTrue(legacyRecordExistsInDB(recorded.id(), Entity.EntityType.VIEW));
    assertEquals(
        1, backend.hardDeleteLegacyData(Entity.EntityType.VIEW, Instant.now().toEpochMilli()));
    assertFalse(legacyRecordExistsInDB(recorded.id(), Entity.EntityType.VIEW));
    assertEquals(
        0, countRows("SELECT COUNT(*) FROM view_version_info WHERE view_id = ?", recorded.id()));
    assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
                + " AND tag_id = ? AND deleted_at > 0 AND deletion_id IS NULL",
            recorded.id(),
            independentlyRemovedTag.id()));
    EntityDeletionPO receipt =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.VIEW,
                EntityIdService.getEntityId(
                    NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA),
                null,
                recorded.id(),
                null)
            .get(0);
    assertEquals(DeletionState.PURGED, receipt.getState());

    ViewEntity legacy =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("legacy_purge_view"),
            AUDIT_INFO);
    ViewMetaService.getInstance().insertView(legacy, false);
    SessionUtils.doWithCommit(
        ViewVersionInfoMapper.class, mapper -> mapper.softDeleteViewVersionsByViewId(legacy.id()));
    SessionUtils.doWithCommit(
        ViewMetaMapper.class, mapper -> mapper.softDeleteViewMetasByViewId(legacy.id()));
    assertTrue(
        backend.hardDeleteLegacyData(
                Entity.EntityType.VIEW, Instant.now().plusSeconds(1).toEpochMilli())
            > 0);
    assertFalse(legacyRecordExistsInDB(legacy.id(), Entity.EntityType.VIEW));
  }

  @TestTemplate
  public void testConcurrentUpdatesDeriveFromLockedViewState() throws Exception {
    Namespace namespace = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("serialized_view"),
            AUDIT_INFO);
    ViewMetaService.getInstance().insertView(view, false);

    CountDownLatch firstUpdaterEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstUpdater = new CountDownLatch(1);
    CountDownLatch secondTaskStarted = new CountDownLatch(1);
    CountDownLatch secondUpdaterEntered = new CountDownLatch(1);
    AtomicReference<String> secondObservedComment = new AtomicReference<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Function<ViewEntity, ViewEntity> firstUpdater =
          oldView -> {
            firstUpdaterEntered.countDown();
            await(releaseFirstUpdater);
            return copyWithComment(oldView, "first");
          };
      Future<ViewEntity> first =
          executor.submit(
              () -> ViewMetaService.getInstance().updateView(view.nameIdentifier(), firstUpdater));
      assertTrue(firstUpdaterEntered.await(10, TimeUnit.SECONDS));

      Function<ViewEntity, ViewEntity> secondUpdater =
          oldView -> {
            secondUpdaterEntered.countDown();
            secondObservedComment.set(oldView.comment());
            return copyWithComment(oldView, oldView.comment() + "-second");
          };
      Future<ViewEntity> second =
          executor.submit(
              () -> {
                secondTaskStarted.countDown();
                return ViewMetaService.getInstance()
                    .updateView(view.nameIdentifier(), secondUpdater);
              });
      assertTrue(secondTaskStarted.await(10, TimeUnit.SECONDS));
      assertFalse(
          secondUpdaterEntered.await(500, TimeUnit.MILLISECONDS),
          "The second updater must not derive state before acquiring the view lock");

      releaseFirstUpdater.countDown();
      assertEquals("first", first.get(10, TimeUnit.SECONDS).comment());
      assertEquals("first-second", second.get(10, TimeUnit.SECONDS).comment());
      assertEquals("first", secondObservedComment.get());
      assertEquals(
          "first-second",
          ViewMetaService.getInstance().getViewByIdentifier(view.nameIdentifier()).comment());
    } finally {
      releaseFirstUpdater.countDown();
      executor.shutdownNow();
    }
  }

  @TestTemplate
  public void testInsertRejectsDeletedParentWithoutCreatingOrphan() throws IOException {
    Namespace namespace = NamespaceUtil.ofView(metalakeName, catalogName, schemaName);
    ViewEntity view =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("orphan_view"),
            AUDIT_INFO);
    assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(metalakeName, catalogName, schemaName), true));
    assertThrows(
        NoSuchEntityException.class, () -> ViewMetaService.getInstance().insertView(view, false));
    assertEquals(0, countRows("SELECT COUNT(*) FROM view_meta WHERE view_id = ?", view.id()));
  }

  private ViewEntity createViewEntity(
      Long id, Namespace namespace, String name, AuditInfo auditInfo) {
    Column[] columns =
        new Column[] {
          Column.of("c1", Types.IntegerType.get(), "first column"),
          Column.of("c2", Types.StringType.get(), "second column")
        };
    Representation[] reps =
        new Representation[] {
          SQLRepresentation.builder().withDialect("spark").withSql("SELECT c1, c2 FROM t").build(),
          SQLRepresentation.builder().withDialect("trino").withSql("SELECT c1, c2 FROM t").build()
        };
    return ViewEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(namespace)
        .withComment("test view comment")
        .withColumns(columns)
        .withRepresentations(reps)
        .withDefaultCatalog(null)
        .withDefaultSchema(null)
        .withProperties(ImmutableMap.of("k1", "v1"))
        .withAuditInfo(auditInfo)
        .build();
  }

  private TagEntity newTag(String prefix) {
    return TagEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(GravitinoITUtils.genRandomName(prefix))
        .withNamespace(NamespaceUtil.ofTag(metalakeName))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private void assertViewMetadataEquals(ViewEntity expected, ViewEntity actual) {
    assertEquals(expected.id(), actual.id());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.namespace(), actual.namespace());
    assertEquals(expected.comment(), actual.comment());
    assertEquals(expected.defaultCatalog(), actual.defaultCatalog());
    assertEquals(expected.defaultSchema(), actual.defaultSchema());
    assertEquals(expected.properties(), actual.properties());
    assertEquals(expected.auditInfo(), actual.auditInfo());
    assertEquals(expected.columns().length, actual.columns().length);
    for (int index = 0; index < expected.columns().length; index++) {
      assertEquals(expected.columns()[index].name(), actual.columns()[index].name());
      assertEquals(expected.columns()[index].dataType(), actual.columns()[index].dataType());
      assertEquals(expected.columns()[index].comment(), actual.columns()[index].comment());
    }
    assertEquals(expected.representations().length, actual.representations().length);
    for (int index = 0; index < expected.representations().length; index++) {
      assertEquals(expected.representations()[index], actual.representations()[index]);
    }
  }

  private int countActiveRelation(String table, String typeColumn, long viewId) {
    String sql =
        String.format(
            "SELECT count(*) FROM %s WHERE metadata_object_id = ? AND %s = 'VIEW'"
                + " AND deleted_at = 0",
            table, typeColumn);
    return countRows(sql, viewId);
  }

  private int countActiveTagRelation(long viewId, long tagId) {
    return countRows(
        "SELECT count(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
            + " AND metadata_object_type = 'VIEW' AND tag_id = ? AND deleted_at = 0",
        viewId,
        tagId);
  }

  private int countRows(String sql, Object... parameters) {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return resultSet.getInt(1);
        }
        throw new IllegalStateException("Count query returned no row");
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to count view rows", e);
    }
  }

  private int updateRows(String sql, Object... parameters) {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to update view rows", e);
    }
  }

  private ViewEntity copyWithComment(ViewEntity view, String comment) {
    return ViewEntity.builder()
        .withId(view.id())
        .withName(view.name())
        .withNamespace(view.namespace())
        .withComment(comment)
        .withColumns(view.columns())
        .withRepresentations(view.representations())
        .withDefaultCatalog(view.defaultCatalog())
        .withDefaultSchema(view.defaultSchema())
        .withProperties(view.properties())
        .withAuditInfo(view.auditInfo())
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent view update");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while coordinating view update", e);
    }
  }

  private Map<Integer, Long> listViewVersions(Long viewId) {
    Map<Integer, Long> versionDeletedTime = new HashMap<>();
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                String.format(
                    "SELECT version, deleted_at FROM view_version_info WHERE view_id = %d",
                    viewId))) {
      while (rs.next()) {
        versionDeletedTime.put(rs.getInt("version"), rs.getLong("deleted_at"));
      }
    } catch (SQLException e) {
      throw new RuntimeException("SQL execution failed", e);
    }
    return versionDeletedTime;
  }

  private int countActiveTagRelForMetadataObject(Long metadataObjectId, String metadataObjectType) {
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                String.format(
                    "SELECT count(*) FROM tag_relation_meta"
                        + " WHERE metadata_object_id = %d AND metadata_object_type = '%s'"
                        + " AND deleted_at = 0",
                    metadataObjectId, metadataObjectType))) {
      if (rs.next()) {
        return rs.getInt(1);
      }
      throw new RuntimeException("No result for countActiveTagRelForMetadataObject");
    } catch (SQLException e) {
      throw new RuntimeException("SQL execution failed", e);
    }
  }
}
