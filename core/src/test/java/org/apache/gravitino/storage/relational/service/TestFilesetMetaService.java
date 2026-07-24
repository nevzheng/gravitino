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

import static org.apache.gravitino.file.Fileset.LOCATION_NAME_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
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
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.StatisticEntity;
import org.apache.gravitino.meta.TableStatisticEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.stats.StatisticValues;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.mockito.Mockito;

public class TestFilesetMetaService extends TestJDBCBackend {
  private final String metalakeName = GravitinoITUtils.genRandomName("tst_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("tst_fs_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("tst_fs_schema");

  @BeforeEach
  public void prepare() throws IOException, IllegalAccessException {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(Configs.CACHE_ENABLED)).thenReturn(false);
    Mockito.when(config.get(Configs.TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100_000L);
    Mockito.when(config.get(Configs.TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1_000L);
    Mockito.when(config.get(Configs.TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36_000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    createAndInsertMakeLake(metalakeName);
    createAndInsertCatalog(metalakeName, catalogName);
    createAndInsertSchema(metalakeName, catalogName, schemaName);
  }

  @TestTemplate
  public void testInsertAlreadyExistsException() throws IOException {
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset",
            AUDIT_INFO);
    FilesetEntity filesetCopy =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset",
            AUDIT_INFO);
    backend.insert(fileset, false);
    assertThrows(EntityAlreadyExistsException.class, () -> backend.insert(filesetCopy, false));
  }

  @TestTemplate
  public void testUpdateAlreadyExistsException() throws IOException {
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset",
            AUDIT_INFO);
    FilesetEntity filesetCopy =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset1",
            AUDIT_INFO);
    backend.insert(fileset, false);
    backend.insert(filesetCopy, false);
    assertThrows(
        EntityAlreadyExistsException.class,
        () ->
            backend.update(
                filesetCopy.nameIdentifier(),
                Entity.EntityType.FILESET,
                e ->
                    createFilesetEntity(
                        filesetCopy.id(), filesetCopy.namespace(), "fileset", AUDIT_INFO)));
  }

  @TestTemplate
  public void testMetaLifeCycleFromCreationToDeletion() throws IOException {
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset",
            AUDIT_INFO);
    backend.insert(fileset, false);

    // update fileset properties and version
    FilesetEntity filesetV2 =
        createFilesetEntity(
            fileset.id(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "fileset",
            AUDIT_INFO);
    filesetV2.properties().put("version", "2");
    backend.update(fileset.nameIdentifier(), Entity.EntityType.FILESET, e -> filesetV2);

    String anotherMetalakeName = GravitinoITUtils.genRandomName("another-metalake");
    String anotherCatalogName = GravitinoITUtils.genRandomName("another-catalog");
    String anotherSchemaName = GravitinoITUtils.genRandomName("another-schema");
    createAndInsertMakeLake(anotherMetalakeName);
    createAndInsertCatalog(anotherMetalakeName, anotherCatalogName);
    createAndInsertSchema(anotherMetalakeName, anotherCatalogName, anotherSchemaName);

    FilesetEntity anotherFileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(anotherMetalakeName, anotherCatalogName, anotherSchemaName),
            "anotherFileset",
            AUDIT_INFO);
    backend.insert(anotherFileset, false);

    FilesetEntity anotherFilesetV2 =
        createFilesetEntity(
            anotherFileset.id(),
            NamespaceUtil.ofFileset(anotherMetalakeName, anotherCatalogName, anotherSchemaName),
            "anotherFileset",
            AUDIT_INFO);
    anotherFilesetV2.properties().put("version", "2");
    backend.update(
        anotherFileset.nameIdentifier(), Entity.EntityType.FILESET, e -> anotherFilesetV2);

    FilesetEntity anotherFilesetV3 =
        createFilesetEntity(
            anotherFileset.id(),
            NamespaceUtil.ofFileset(anotherMetalakeName, anotherCatalogName, anotherSchemaName),
            "anotherFileset",
            AUDIT_INFO);
    anotherFilesetV3.properties().put("version", "3");
    backend.update(
        anotherFileset.nameIdentifier(), Entity.EntityType.FILESET, e -> anotherFilesetV3);

    List<FilesetEntity> filesets =
        backend.list(fileset.namespace(), Entity.EntityType.FILESET, true);
    assertFalse(filesets.contains(fileset));
    assertTrue(filesets.contains(filesetV2));
    assertEquals("2", filesets.get(filesets.indexOf(filesetV2)).properties().get("version"));

    // meta data soft delete
    backend.delete(NameIdentifierUtil.ofMetalake(metalakeName), Entity.EntityType.METALAKE, true);
    assertFalse(backend.exists(fileset.nameIdentifier(), Entity.EntityType.FILESET));
    assertTrue(backend.exists(anotherFileset.nameIdentifier(), Entity.EntityType.FILESET));

    // check legacy record after soft delete
    assertTrue(legacyRecordExistsInDB(fileset.id(), Entity.EntityType.FILESET));
    assertEquals(2, listFilesetVersions(fileset.id()).size());
    assertEquals(3, listFilesetVersions(anotherFileset.id()).size());

    // meta data hard delete
    for (Entity.EntityType entityType : Entity.EntityType.values()) {
      backend.hardDeleteLegacyData(entityType, Instant.now().toEpochMilli() + 1000);
    }
    assertFalse(legacyRecordExistsInDB(fileset.id(), Entity.EntityType.FILESET));
    assertEquals(0, listFilesetVersions(fileset.id()).size());
    Map<Integer, Long> anotherFilesetVersionsAfterHardDelete =
        listFilesetVersions(anotherFileset.id());
    assertTrue(anotherFilesetVersionsAfterHardDelete.containsKey(3));
    assertEquals(0L, anotherFilesetVersionsAfterHardDelete.get(3));

    // soft delete for old version fileset
    for (Entity.EntityType entityType : Entity.EntityType.values()) {
      backend.deleteOldVersionData(entityType, 1);
    }
    Map<Integer, Long> versionDeletedMap = listFilesetVersions(anotherFileset.id());
    assertTrue(versionDeletedMap.containsKey(3));
    assertEquals(0L, versionDeletedMap.get(3));
    assertEquals(1, versionDeletedMap.values().stream().filter(value -> value == 0L).count());

    // hard delete for old version fileset
    backend.hardDeleteLegacyData(Entity.EntityType.FILESET, Instant.now().toEpochMilli() + 1000);
    Map<Integer, Long> finalFilesetVersions = listFilesetVersions(anotherFileset.id());
    assertTrue(finalFilesetVersions.containsKey(3));
    assertEquals(0L, finalFilesetVersions.get(3));
    assertEquals(1, finalFilesetVersions.values().stream().filter(value -> value == 0L).count());
  }

  @TestTemplate
  public void testFilesetMultipleLocations() {
    // test create
    String filesetName = GravitinoITUtils.genRandomName("multiple_location_fileset");
    NameIdentifier filesetIdent =
        NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName);
    String locationName = "location1";
    Map<String, String> locations =
        ImmutableMap.of(LOCATION_NAME_UNKNOWN, "/tmp", locationName, "/tmp2");
    Namespace filesetNs = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    FilesetEntity filesetEntity =
        FilesetEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(filesetName)
            .withNamespace(filesetNs)
            .withFilesetType(Fileset.Type.MANAGED)
            .withStorageLocations(locations)
            .withComment("")
            .withProperties(null)
            .withAuditInfo(AUDIT_INFO)
            .build();
    Assertions.assertDoesNotThrow(
        () -> FilesetMetaService.getInstance().insertFileset(filesetEntity, true));

    // test load
    FilesetEntity loadedFilesetEntity =
        FilesetMetaService.getInstance().getFilesetByIdentifier(filesetIdent);
    Assertions.assertEquals(filesetEntity, loadedFilesetEntity);

    // test update
    Map<String, String> newProps = ImmutableMap.of("k1", "v1", "k2", "v2");
    FilesetEntity updatedFilesetEntity =
        FilesetEntity.builder()
            .withId(loadedFilesetEntity.id())
            .withName(loadedFilesetEntity.name())
            .withNamespace(loadedFilesetEntity.namespace())
            .withFilesetType(loadedFilesetEntity.filesetType())
            .withStorageLocations(loadedFilesetEntity.storageLocations())
            .withComment(loadedFilesetEntity.comment())
            .withProperties(newProps)
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator2").withCreateTime(Instant.now()).build())
            .build();
    Assertions.assertDoesNotThrow(
        () ->
            FilesetMetaService.getInstance()
                .updateFileset(filesetIdent, e -> updatedFilesetEntity));
    FilesetEntity updatedLoadedFilesetEntity =
        FilesetMetaService.getInstance().getFilesetByIdentifier(filesetIdent);
    Assertions.assertEquals(updatedFilesetEntity, updatedLoadedFilesetEntity);

    // test list
    String filesetName2 = GravitinoITUtils.genRandomName("multiple_location_fileset2");
    NameIdentifier filesetIdent2 =
        NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName2);
    FilesetEntity filesetEntity2 =
        FilesetEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(filesetName2)
            .withNamespace(filesetNs)
            .withFilesetType(Fileset.Type.MANAGED)
            .withStorageLocations(locations)
            .withComment("")
            .withProperties(null)
            .withAuditInfo(AUDIT_INFO)
            .build();
    Assertions.assertDoesNotThrow(
        () -> FilesetMetaService.getInstance().insertFileset(filesetEntity2, true));
    int count = FilesetMetaService.getInstance().listFilesetsByNamespace(filesetNs).size();
    Assertions.assertEquals(2, count);

    // test delete
    Assertions.assertDoesNotThrow(
        () -> FilesetMetaService.getInstance().deleteFileset(filesetIdent2));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> FilesetMetaService.getInstance().getFilesetByIdentifier(filesetIdent2));
    List<Pair<Integer, String>> versionInfos = listFilesetInvalidVersions(filesetEntity2.id());
    Assertions.assertEquals(2, versionInfos.size());
    Assertions.assertEquals(1, versionInfos.get(0).getLeft());
    Set<String> locationNames =
        versionInfos.stream().map(Pair::getRight).collect(Collectors.toSet());
    Assertions.assertTrue(locationNames.contains(LOCATION_NAME_UNKNOWN));
    Assertions.assertTrue(locationNames.contains(locationName));
  }

  @TestTemplate
  public void testRestoreExactFilesetAggregatePreservesIndependentTombstones() throws IOException {
    Namespace namespace = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    String filesetName = GravitinoITUtils.genRandomName("recoverable_fileset");
    FilesetEntity fileset =
        FilesetEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(filesetName)
            .withNamespace(namespace)
            .withFilesetType(Fileset.Type.MANAGED)
            .withStorageLocations(ImmutableMap.of(LOCATION_NAME_UNKNOWN, "/warehouse/v1"))
            .withComment("version one")
            .withProperties(ImmutableMap.of("version", "one"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    FilesetMetaService.getInstance().insertFileset(fileset, false);

    UserEntity owner =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofUserNamespace(metalakeName),
            GravitinoITUtils.genRandomName("fileset_owner"),
            AUDIT_INFO);
    backend.insert(owner, false);
    OwnerMetaService.getInstance()
        .setOwner(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            owner.nameIdentifier(),
            owner.type());

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
            GravitinoITUtils.genRandomName("fileset_role"),
            AUDIT_INFO,
            Lists.newArrayList(
                SecurableObjects.ofFileset(
                    schemaObject,
                    fileset.name(),
                    Lists.newArrayList(Privileges.ReadFileset.allow()))),
            ImmutableMap.of());
    RoleMetaService.getInstance().insertRole(role, false);

    TagEntity retainedTag = newTag("retained_fileset_tag");
    TagEntity independentlyRemovedTag = newTag("removed_fileset_tag");
    TagMetaService.getInstance().insertTag(retainedTag, false);
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            new NameIdentifier[] {
              retainedTag.nameIdentifier(), independentlyRemovedTag.nameIdentifier()
            },
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});

    StatisticEntity statistic =
        TableStatisticEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(GravitinoITUtils.genRandomName("fileset_statistic"))
            .withValue(StatisticValues.longValue(1L))
            .withAuditInfo(AUDIT_INFO)
            .build();
    StatisticMetaService.getInstance()
        .batchInsertStatisticPOsOnDuplicateKeyUpdate(
            ImmutableList.of(statistic), fileset.nameIdentifier(), Entity.EntityType.FILESET);

    PolicyContent policyContent =
        PolicyContents.custom(
            ImmutableMap.of("rule", true), ImmutableSet.of(MetadataObject.Type.FILESET), null);
    PolicyEntity policy =
        PolicyEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(GravitinoITUtils.genRandomName("fileset_policy"))
            .withNamespace(NamespaceUtil.ofPolicy(metalakeName))
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withContent(policyContent)
            .withAuditInfo(AUDIT_INFO)
            .build();
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyMetaService.getInstance()
        .associatePoliciesWithMetadataObject(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            new NameIdentifier[] {policy.nameIdentifier()},
            new NameIdentifier[0]);

    FilesetEntity versionTwo =
        FilesetEntity.builder()
            .withId(fileset.id())
            .withName(fileset.name())
            .withNamespace(fileset.namespace())
            .withFilesetType(fileset.filesetType())
            .withStorageLocations(
                ImmutableMap.of(LOCATION_NAME_UNKNOWN, "/warehouse/v2", "archive", "/archive/v2"))
            .withComment("version two")
            .withProperties(ImmutableMap.of("version", "two"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    FilesetMetaService.getInstance().updateFileset(fileset.nameIdentifier(), ignored -> versionTwo);
    FilesetEntity current =
        FilesetEntity.builder()
            .withId(fileset.id())
            .withName(fileset.name())
            .withNamespace(fileset.namespace())
            .withFilesetType(fileset.filesetType())
            .withStorageLocations(
                ImmutableMap.of(LOCATION_NAME_UNKNOWN, "/warehouse/v3", "archive", "/archive/v3"))
            .withComment("version three")
            .withProperties(ImmutableMap.of("version", "three"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    FilesetMetaService.getInstance().updateFileset(fileset.nameIdentifier(), ignored -> current);
    FilesetMetaService.getInstance().deleteFilesetVersionsByRetentionCount(2L, 100);

    long deletedAt = Instant.now().toEpochMilli();
    Assertions.assertTrue(
        FilesetMetaService.getInstance()
            .deleteFileset(fileset.nameIdentifier(), deletedAt, 60_000L));
    Assertions.assertEquals(
        2,
        countRows(
            "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?"
                + " AND version = 3 AND deleted_at = ? AND deletion_id IS NOT NULL",
            fileset.id(),
            deletedAt));
    Assertions.assertEquals(
        2,
        countRows(
            "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?"
                + " AND version = 2 AND deleted_at = ? AND deletion_id IS NOT NULL",
            fileset.id(),
            deletedAt));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?"
                + " AND version = 1 AND deleted_at > 0 AND deletion_id IS NULL",
            fileset.id()));
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.FILESET,
                schemaId,
                fileset.name(),
                fileset.id(),
                DeletionState.DELETED)
            .get(0);
    FilesetEntity restored =
        FilesetMetaService.getInstance()
            .restoreFileset(
                fileset.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "fileset-restore-etag",
                deletion.getExpiresAt());
    Assertions.assertEquals(current, restored);
    Assertions.assertEquals(2, restored.storageLocations().size());
    Assertions.assertEquals(
        1, countActiveRelation("owner_meta", "metadata_object_type", fileset.id()));
    Assertions.assertEquals(
        1, countActiveRelation("role_meta_securable_object", "type", fileset.id()));
    Assertions.assertEquals(1, countActiveTagRelation(fileset.id(), retainedTag.id()));
    Assertions.assertEquals(0, countActiveTagRelation(fileset.id(), independentlyRemovedTag.id()));
    Assertions.assertEquals(
        1, countActiveRelation("statistic_meta", "metadata_object_type", fileset.id()));
    Assertions.assertEquals(
        1, countActiveRelation("policy_relation_meta", "metadata_object_type", fileset.id()));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?"
                + " AND version = 1 AND deleted_at > 0 AND deletion_id IS NULL",
            fileset.id()));
    Assertions.assertEquals(
        2,
        countRows(
            "SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?"
                + " AND version = 2 AND deleted_at = 0 AND deletion_id IS NULL",
            fileset.id()));
    Assertions.assertEquals(
        fileset.id(),
        FilesetMetaService.getInstance()
            .restoreFileset(
                fileset.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "fileset-restore-etag",
                deletion.getExpiresAt())
            .id());
  }

  @TestTemplate
  public void testFilesetNameCollisionLatestGenerationAndOverwriteGuard() throws IOException {
    Namespace namespace = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    String name = GravitinoITUtils.genRandomName("repeated_fileset");
    FilesetEntity original =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO, "/original");
    FilesetMetaService.getInstance().insertFileset(original, false);
    Assertions.assertTrue(
        FilesetMetaService.getInstance().deleteFileset(original.nameIdentifier()));
    Assertions.assertThrows(
        RecoveryConflictException.class,
        () -> FilesetMetaService.getInstance().insertFileset(original, true));

    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
    FilesetEntity replacement =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO, "/replacement");
    FilesetMetaService.getInstance().insertFileset(replacement, false);
    DeletedEntityDTO occupied = manager.getDeletedFileset(namespace, name, original.id());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());
    RecoveryConflictException occupiedFailure =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                manager.restoreDeletedFileset(namespace, name, original.id(), occupied.getEtag()));
    Assertions.assertEquals(RecoveryConflictReason.NAME_OCCUPIED, occupiedFailure.getReason());

    Assertions.assertTrue(
        FilesetMetaService.getInstance().deleteFileset(replacement.nameIdentifier()));
    DeletedEntityDTO oldGeneration = manager.getDeletedFileset(namespace, name, original.id());
    Assertions.assertFalse(oldGeneration.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", oldGeneration.getReason());
    RecoveryConflictException latestFailure =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                manager.restoreDeletedFileset(
                    namespace, name, original.id(), oldGeneration.getEtag()));
    Assertions.assertEquals(RecoveryConflictReason.NOT_LATEST_TOMBSTONE, latestFailure.getReason());
  }

  @TestTemplate
  public void testRecordedFilesetDeletionUsesExactPurge() throws IOException {
    Namespace namespace = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("purged_fileset"),
            AUDIT_INFO,
            "/purged");
    FilesetMetaService.getInstance().insertFileset(fileset, false);
    TagEntity independentlyRemovedTag = newTag("purged_fileset_removed_tag");
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()},
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            fileset.nameIdentifier(),
            Entity.EntityType.FILESET,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});
    long deletedAt = Instant.now().minusSeconds(60).toEpochMilli();
    Assertions.assertTrue(
        FilesetMetaService.getInstance().deleteFileset(fileset.nameIdentifier(), deletedAt, 0L));

    Assertions.assertEquals(
        0,
        FilesetMetaService.getInstance()
            .deleteFilesetAndVersionMetasByLegacyTimeline(Instant.now().toEpochMilli(), 100));
    Assertions.assertTrue(legacyRecordExistsInDB(fileset.id(), Entity.EntityType.FILESET));
    Assertions.assertEquals(
        1, backend.hardDeleteLegacyData(Entity.EntityType.FILESET, Instant.now().toEpochMilli()));
    Assertions.assertFalse(legacyRecordExistsInDB(fileset.id(), Entity.EntityType.FILESET));
    Assertions.assertEquals(
        0,
        countRows("SELECT COUNT(*) FROM fileset_version_info WHERE fileset_id = ?", fileset.id()));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
                + " AND tag_id = ? AND deleted_at > 0 AND deletion_id IS NULL",
            fileset.id(),
            independentlyRemovedTag.id()));
  }

  @TestTemplate
  public void testConcurrentUpdatesDeriveFromLockedFilesetState() throws Exception {
    Namespace namespace = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("serialized_fileset"),
            AUDIT_INFO,
            "/initial");
    FilesetMetaService.getInstance().insertFileset(fileset, false);

    CountDownLatch firstUpdaterEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstUpdater = new CountDownLatch(1);
    CountDownLatch secondTaskStarted = new CountDownLatch(1);
    CountDownLatch secondUpdaterEntered = new CountDownLatch(1);
    AtomicReference<String> secondObservedComment = new AtomicReference<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Function<FilesetEntity, FilesetEntity> firstUpdater =
          oldFileset -> {
            firstUpdaterEntered.countDown();
            await(releaseFirstUpdater);
            return copyWithComment(oldFileset, "first");
          };
      Future<FilesetEntity> first =
          executor.submit(
              () ->
                  FilesetMetaService.getInstance()
                      .updateFileset(fileset.nameIdentifier(), firstUpdater));
      Assertions.assertTrue(firstUpdaterEntered.await(10, TimeUnit.SECONDS));

      Function<FilesetEntity, FilesetEntity> secondUpdater =
          oldFileset -> {
            secondUpdaterEntered.countDown();
            secondObservedComment.set(oldFileset.comment());
            return copyWithComment(oldFileset, oldFileset.comment() + "-second");
          };
      Future<FilesetEntity> second =
          executor.submit(
              () -> {
                secondTaskStarted.countDown();
                return FilesetMetaService.getInstance()
                    .updateFileset(fileset.nameIdentifier(), secondUpdater);
              });
      Assertions.assertTrue(secondTaskStarted.await(10, TimeUnit.SECONDS));
      Assertions.assertFalse(
          secondUpdaterEntered.await(500, TimeUnit.MILLISECONDS),
          "The second updater must not derive state before acquiring the fileset lock");

      releaseFirstUpdater.countDown();
      Assertions.assertEquals("first", first.get(10, TimeUnit.SECONDS).comment());
      Assertions.assertEquals("first-second", second.get(10, TimeUnit.SECONDS).comment());
      Assertions.assertEquals("first", secondObservedComment.get());
      Assertions.assertEquals(
          "first-second",
          FilesetMetaService.getInstance()
              .getFilesetByIdentifier(fileset.nameIdentifier())
              .comment());
    } finally {
      releaseFirstUpdater.countDown();
      executor.shutdownNow();
    }
  }

  @TestTemplate
  public void testInsertRejectsDeletedParentWithoutCreatingOrphan() throws IOException {
    Namespace namespace = NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName);
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("orphan_fileset"),
            AUDIT_INFO,
            "/orphan");
    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(metalakeName, catalogName, schemaName), true));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> FilesetMetaService.getInstance().insertFileset(fileset, false));
    Assertions.assertEquals(
        0, countRows("SELECT COUNT(*) FROM fileset_meta WHERE fileset_id = ?", fileset.id()));
  }

  @TestTemplate
  public void testDeleteFilesetVersionsByRetentionCount() throws IOException {
    String filesetName = GravitinoITUtils.genRandomName("tst_fs_fileset");
    FilesetEntity filesetEntity =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            filesetName,
            AUDIT_INFO,
            "/tmp");
    FilesetMetaService.getInstance().insertFileset(filesetEntity, true);
    assertNotNull(
        FilesetMetaService.getInstance()
            .getFilesetByIdentifier(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName)));
    FilesetMetaService.getInstance()
        .updateFileset(
            NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
            e -> {
              AuditInfo auditInfo1 =
                  AuditInfo.builder().withCreator("creator5").withCreateTime(Instant.now()).build();
              return createFilesetEntity(
                  filesetEntity.id(),
                  Namespace.of(metalakeName, catalogName, schemaName),
                  "filesetChanged",
                  auditInfo1,
                  "/tmp1");
            });
    Map<Integer, Long> versionDeletedMap = listFilesetVersions(filesetEntity.id());
    assertEquals(2, versionDeletedMap.size());
    assertVersionActive(versionDeletedMap, 1);
    assertVersionActive(versionDeletedMap, 2);

    FilesetMetaService.getInstance().deleteFilesetVersionsByRetentionCount(1L, 100);
    versionDeletedMap = listFilesetVersions(filesetEntity.id());
    assertEquals(2, versionDeletedMap.size());
    assertVersionSoftDeleted(versionDeletedMap, 1);
    assertVersionActive(versionDeletedMap, 2);
  }

  private List<Pair<Integer, String>> listFilesetInvalidVersions(Long filesetId) {
    List<Pair<Integer, String>> deletedVersions = Lists.newArrayList();
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                String.format(
                    "SELECT version, storage_location_name FROM fileset_version_info WHERE fileset_id = %d and deleted_at > 0",
                    filesetId))) {
      while (rs.next()) {
        deletedVersions.add(Pair.of(rs.getInt("version"), rs.getString("storage_location_name")));
      }
    } catch (SQLException e) {
      throw new RuntimeException("SQL execution failed", e);
    }
    return deletedVersions;
  }

  private void assertVersionActive(Map<Integer, Long> versionDeletedMap, int version) {
    assertTrue(versionDeletedMap.containsKey(version));
    assertEquals(0L, versionDeletedMap.get(version));
  }

  private void assertVersionSoftDeleted(Map<Integer, Long> versionDeletedMap, int version) {
    assertTrue(versionDeletedMap.containsKey(version));
    assertTrue(versionDeletedMap.get(version) > 0L);
  }

  private FilesetEntity createFilesetEntity(
      Long id, Namespace namespace, String name, AuditInfo auditInfo, String location) {
    return FilesetEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(namespace)
        .withFilesetType(Fileset.Type.MANAGED)
        .withStorageLocations(ImmutableMap.of(LOCATION_NAME_UNKNOWN, location))
        .withComment("")
        .withProperties(null)
        .withAuditInfo(auditInfo)
        .build();
  }

  @TestTemplate
  public void testUpdateFilesetRollsBackNestedConflict() throws IOException {
    String filesetName = GravitinoITUtils.genRandomName("tst_fs_conflict");
    NameIdentifier filesetIdent =
        NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName);
    FilesetEntity filesetEntity =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            filesetName,
            AUDIT_INFO,
            "/tmp");
    FilesetMetaService.getInstance().insertFileset(filesetEntity, true);

    AuditInfo conflictingAuditInfo =
        AuditInfo.builder()
            .withCreator("conflicting-updater")
            .withCreateTime(Instant.now())
            .build();
    FilesetEntity updatedFilesetEntity =
        FilesetEntity.builder()
            .withId(filesetEntity.id())
            .withName(filesetEntity.name())
            .withNamespace(filesetEntity.namespace())
            .withFilesetType(filesetEntity.filesetType())
            .withStorageLocations(ImmutableMap.of(LOCATION_NAME_UNKNOWN, "/tmp-v2"))
            .withComment("comment-v2")
            .withProperties(ImmutableMap.of("version", "2"))
            .withAuditInfo(
                AuditInfo.builder()
                    .withCreator("expected-updater")
                    .withCreateTime(Instant.now())
                    .build())
            .build();

    TombstoneChangedException exception =
        Assertions.assertThrows(
            TombstoneChangedException.class,
            () ->
                FilesetMetaService.getInstance()
                    .updateFileset(
                        filesetIdent,
                        e -> {
                          // Simulate a nested write that invalidates the outer optimistic update.
                          try {
                            backend.update(
                                filesetIdent,
                                Entity.EntityType.FILESET,
                                entity -> {
                                  FilesetEntity cloned =
                                      createFilesetEntity(
                                          entity.id(),
                                          entity.namespace(),
                                          entity.name(),
                                          conflictingAuditInfo,
                                          "/tmp");
                                  return cloned;
                                });
                          } catch (Exception ex) {
                            throw new RuntimeException(ex);
                          }
                          return updatedFilesetEntity;
                        }));
    Assertions.assertTrue(
        exception.getMessage().contains("Fileset changed while updating " + filesetIdent));

    FilesetEntity persistedEntity =
        FilesetMetaService.getInstance().getFilesetByIdentifier(filesetIdent);
    Assertions.assertEquals(filesetEntity.auditInfo(), persistedEntity.auditInfo());
    Assertions.assertEquals("", persistedEntity.comment());
    Assertions.assertNull(persistedEntity.properties());
    Assertions.assertEquals("/tmp", persistedEntity.storageLocations().get(LOCATION_NAME_UNKNOWN));
    Assertions.assertNotEquals(conflictingAuditInfo, persistedEntity.auditInfo());
    Assertions.assertNotEquals(updatedFilesetEntity, persistedEntity);
  }

  private TagEntity newTag(String prefix) {
    return TagEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(GravitinoITUtils.genRandomName(prefix))
        .withNamespace(NamespaceUtil.ofTag(metalakeName))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private int countActiveRelation(String table, String typeColumn, long filesetId) {
    String sql =
        String.format(
            "SELECT count(*) FROM %s WHERE metadata_object_id = ? AND %s = 'FILESET'"
                + " AND deleted_at = 0",
            table, typeColumn);
    return countRows(sql, filesetId);
  }

  private int countActiveTagRelation(long filesetId, long tagId) {
    return countRows(
        "SELECT count(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
            + " AND metadata_object_type = 'FILESET' AND tag_id = ? AND deleted_at = 0",
        filesetId,
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
      throw new RuntimeException("Failed to count fileset rows", e);
    }
  }

  private FilesetEntity copyWithComment(FilesetEntity fileset, String comment) {
    return FilesetEntity.builder()
        .withId(fileset.id())
        .withName(fileset.name())
        .withNamespace(fileset.namespace())
        .withFilesetType(fileset.filesetType())
        .withStorageLocations(fileset.storageLocations())
        .withComment(comment)
        .withProperties(fileset.properties())
        .withAuditInfo(fileset.auditInfo())
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent fileset update");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while coordinating fileset update", e);
    }
  }
}
