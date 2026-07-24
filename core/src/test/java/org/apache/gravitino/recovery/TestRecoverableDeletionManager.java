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
package org.apache.gravitino.recovery;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.job.JobHandle;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.JobEntity;
import org.apache.gravitino.meta.JobTemplateEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.ModelVersionEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyVersionMapper;
import org.apache.gravitino.storage.relational.mapper.TableMetaMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.service.CatalogMetaService;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.FilesetMetaService;
import org.apache.gravitino.storage.relational.service.FunctionMetaService;
import org.apache.gravitino.storage.relational.service.GroupMetaService;
import org.apache.gravitino.storage.relational.service.JobMetaService;
import org.apache.gravitino.storage.relational.service.JobTemplateMetaService;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.apache.gravitino.storage.relational.service.ModelMetaService;
import org.apache.gravitino.storage.relational.service.ModelVersionMetaService;
import org.apache.gravitino.storage.relational.service.PolicyMetaService;
import org.apache.gravitino.storage.relational.service.RoleMetaService;
import org.apache.gravitino.storage.relational.service.SchemaMetaService;
import org.apache.gravitino.storage.relational.service.TableMetaService;
import org.apache.gravitino.storage.relational.service.TagMetaService;
import org.apache.gravitino.storage.relational.service.TopicMetaService;
import org.apache.gravitino.storage.relational.service.UserMetaService;
import org.apache.gravitino.storage.relational.service.ViewMetaService;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

public class TestRecoverableDeletionManager extends TestJDBCBackend {

  private static final long RETENTION_MS = 604_800_000L;
  private static final String METALAKE = "recovery_metalake";
  private static final String CATALOG = "recovery_catalog";
  private static final String SCHEMA = "recovery_schema";

  @BeforeEach
  public void initializeLockManager() throws IllegalAccessException {
    Config config = GravitinoEnv.getInstance().config();
    Mockito.when(config.get(TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100_000L);
    Mockito.when(config.get(TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1_000L);
    Mockito.when(config.get(TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36_000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @TestTemplate
  public void testTagRestoreUsesExactLiveRelationGenerationAndReplays() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    TableEntity table = newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA), "tagged");
    TableMetaService.getInstance().insertTable(table, false);
    TagEntity tag = newTag("recoverable_tag");
    TagMetaService.getInstance().insertTag(tag, false);

    associateTag(table, tag, true);
    associateTag(table, tag, false);
    associateTag(table, tag, true);
    Assertions.assertTrue(
        TagMetaService.getInstance().deleteTag(tag.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted =
        manager.getDeletedTag(NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deleted.getDeletionId());
    Assertions.assertEquals(2L, receipt.getAffectedRowCount());
    Assertions.assertEquals(1, countTagRelations(tag.id(), deleted.getDeletionId(), true));
    Assertions.assertEquals(1, countTagRelations(tag.id(), null, false));
    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () -> TagMetaService.getInstance().insertTag(tag, true));

    Assertions.assertEquals(
        tag.id(),
        manager
            .restoreDeletedTag(
                NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id(), deleted.getEtag())
            .id());
    Assertions.assertEquals(
        List.of(tag),
        TagMetaService.getInstance()
            .listTagsForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertEquals(1, countTagRelations(tag.id(), null, false));
    Assertions.assertEquals(
        tag.id(),
        manager
            .restoreDeletedTag(
                NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id(), deleted.getEtag())
            .id());
  }

  @TestTemplate
  public void testTagRestoreRejectsMissingRelationWithoutPartialRevival() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    TableEntity table = newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA), "broken_tag");
    TableMetaService.getInstance().insertTable(table, false);
    TagEntity tag = newTag("broken_tag");
    TagMetaService.getInstance().insertTag(tag, false);
    associateTag(table, tag, true);
    Assertions.assertTrue(
        TagMetaService.getInstance().deleteTag(tag.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted =
        manager.getDeletedTag(NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id());
    updateDeletionRecord(
        "DELETE FROM tag_relation_meta WHERE tag_id = ? AND deletion_id = ?",
        tag.id(),
        deleted.getDeletionId());

    assertRecoveryConflict(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        () ->
            manager.restoreDeletedTag(
                NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(tag.nameIdentifier(), Entity.EntityType.TAG));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testTagExactPurgeAndLegacyOrphanCleanup() throws Exception {
    long deletedAt = Instant.now().toEpochMilli();
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    TableEntity table = newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA), "tag_gc");
    TableMetaService.getInstance().insertTable(table, false);

    TagEntity recorded = newTag("recorded_tag_gc");
    TagMetaService.getInstance().insertTag(recorded, false);
    associateTag(table, recorded, true);
    Assertions.assertTrue(
        TagMetaService.getInstance().deleteTag(recorded.nameIdentifier(), deletedAt, RETENTION_MS));
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(deletedAt + RETENTION_MS + 1L), ZoneOffset.UTC));
    DeletedEntityDTO expired =
        manager.getDeletedTag(NamespaceUtil.ofTag(METALAKE), recorded.name(), recorded.id());
    Assertions.assertFalse(expired.getRestorable());
    Assertions.assertEquals("TOMBSTONE_EXPIRED", expired.getReason());

    Assertions.assertEquals(
        1,
        TagMetaService.getInstance().purgeExpiredTagDeletions(deletedAt + RETENTION_MS + 1L, 100));
    Assertions.assertFalse(legacyRecordExistsInDB(recorded.id(), Entity.EntityType.TAG));
    Assertions.assertEquals(0, countAllTagRelations(recorded.id()));
    Assertions.assertEquals(
        DeletionState.PURGED,
        EntityDeletionService.getInstance().get(expired.getDeletionId()).getState());

    TagEntity legacy = newTag("legacy_tag_gc");
    TagMetaService.getInstance().insertTag(legacy, false);
    associateTag(table, legacy, true);
    long legacyDeletedAt = deletedAt - 1_000L;
    updateDeletionRecord(
        "UPDATE tag_meta SET deleted_at = ?, deletion_id = NULL WHERE tag_id = ?",
        legacyDeletedAt,
        legacy.id());
    Assertions.assertEquals(1, countAllTagRelations(legacy.id()));

    Assertions.assertEquals(
        2, TagMetaService.getInstance().deleteTagMetasByLegacyTimeline(legacyDeletedAt + 1L, 100));
    Assertions.assertFalse(legacyRecordExistsInDB(legacy.id(), Entity.EntityType.TAG));
    Assertions.assertEquals(0, countAllTagRelations(legacy.id()));
  }

  @TestTemplate
  public void testSharedTagTableRelationCanRestoreEitherRootFirst() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);

    assertSharedTagTableRestoreOrder("tag_first_table_first", true, false);
    assertSharedTagTableRestoreOrder("tag_first_tag_first", true, true);
    assertSharedTagTableRestoreOrder("table_first_tag_first", false, true);
    assertSharedTagTableRestoreOrder("table_first_table_first", false, false);
  }

  @TestTemplate
  public void testJobTemplateRestoreRevivesOnlyJobsCapturedByExactGeneration() throws Exception {
    createAndInsertMakeLake(METALAKE);
    JobTemplateEntity template =
        createAndInsertShellJobTemplateEntity(
            "recoverable_job_template", "recoverable template", METALAKE);
    JobEntity prior =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId("prior-execution")
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(template.name())
            .withStatus(JobHandle.Status.SUCCEEDED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobEntity captured =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId("captured-execution")
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(template.name())
            .withStatus(JobHandle.Status.FAILED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobMetaService.getInstance().insertJob(prior, false);
    JobMetaService.getInstance().insertJob(captured, false);
    Assertions.assertTrue(JobMetaService.getInstance().deleteJob(prior.nameIdentifier()));
    Assertions.assertTrue(
        JobTemplateMetaService.getInstance()
            .deleteJobTemplate(template.nameIdentifier(), RETENTION_MS));

    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () -> JobTemplateMetaService.getInstance().insertJobTemplate(template, true));
    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () -> JobMetaService.getInstance().insertJob(captured, true));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    Namespace namespace = NamespaceUtil.ofJobTemplate(METALAKE);
    DeletedEntityDTO deleted =
        manager.getDeletedJobTemplate(namespace, template.name(), template.id());
    Assertions.assertTrue(deleted.getRestorable());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deleted.getDeletionId());
    Assertions.assertEquals(2L, receipt.getAffectedRowCount());

    Assertions.assertEquals(
        template.id(),
        manager
            .restoreDeletedJobTemplate(namespace, template.name(), template.id(), deleted.getEtag())
            .id());
    Assertions.assertEquals(
        captured.id(),
        JobMetaService.getInstance().getJobByIdentifier(captured.nameIdentifier()).id());
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> JobMetaService.getInstance().getJobByIdentifier(prior.nameIdentifier()));
    Assertions.assertEquals(
        template.id(),
        manager
            .restoreDeletedJobTemplate(namespace, template.name(), template.id(), deleted.getEtag())
            .id());
  }

  @TestTemplate
  public void testJobTemplateRestoreRejectsLiveExecutionIdConflict() throws Exception {
    createAndInsertMakeLake(METALAKE);
    JobTemplateEntity deletedTemplate =
        createAndInsertShellJobTemplateEntity("deleted_template", "deleted", METALAKE);
    JobEntity deletedJob =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId("shared-execution-id")
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(deletedTemplate.name())
            .withStatus(JobHandle.Status.SUCCEEDED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobMetaService.getInstance().insertJob(deletedJob, false);
    Assertions.assertTrue(
        JobTemplateMetaService.getInstance()
            .deleteJobTemplate(deletedTemplate.nameIdentifier(), RETENTION_MS));

    JobTemplateEntity replacementTemplate =
        createAndInsertShellJobTemplateEntity("replacement_template", "replacement", METALAKE);
    JobEntity conflictingJob =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId(deletedJob.jobExecutionId())
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(replacementTemplate.name())
            .withStatus(JobHandle.Status.FAILED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobMetaService.getInstance().insertJob(conflictingJob, false);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    Namespace namespace = NamespaceUtil.ofJobTemplate(METALAKE);
    DeletedEntityDTO deleted =
        manager.getDeletedJobTemplate(namespace, deletedTemplate.name(), deletedTemplate.id());
    assertRecoveryConflict(
        RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
        () ->
            manager.restoreDeletedJobTemplate(
                namespace, deletedTemplate.name(), deletedTemplate.id(), deleted.getEtag()));
    Assertions.assertFalse(
        backend.exists(deletedTemplate.nameIdentifier(), Entity.EntityType.JOB_TEMPLATE));
    Assertions.assertEquals(
        conflictingJob.id(),
        JobMetaService.getInstance().getJobByIdentifier(conflictingJob.nameIdentifier()).id());
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testJobTemplateRestoreRejectsMissingCapturedRunWithoutPartialRevival()
      throws Exception {
    createAndInsertMakeLake(METALAKE);
    JobTemplateEntity template =
        createAndInsertShellJobTemplateEntity("broken_job_template", "broken", METALAKE);
    JobEntity captured =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId("missing-execution")
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(template.name())
            .withStatus(JobHandle.Status.CANCELLED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobMetaService.getInstance().insertJob(captured, false);
    Assertions.assertTrue(
        JobTemplateMetaService.getInstance()
            .deleteJobTemplate(template.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    Namespace namespace = NamespaceUtil.ofJobTemplate(METALAKE);
    DeletedEntityDTO deleted =
        manager.getDeletedJobTemplate(namespace, template.name(), template.id());
    updateDeletionRecord(
        "DELETE FROM job_run_meta WHERE job_run_id = ? AND deletion_id = ?",
        captured.id(),
        deleted.getDeletionId());

    assertRecoveryConflict(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        () ->
            manager.restoreDeletedJobTemplate(
                namespace, template.name(), template.id(), deleted.getEtag()));
    Assertions.assertFalse(
        backend.exists(template.nameIdentifier(), Entity.EntityType.JOB_TEMPLATE));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testListsRecordedAndLegacyTableTombstones() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);

    TableEntity recoverable = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(recoverable, false);
    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(recoverable.nameIdentifier(), RETENTION_MS));

    TablePO recoverableTombstone =
        TableMetaService.getInstance().listDeletedTablesByNamespace(namespace).get(0);
    EntityDeletionPO deletionRecord =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE,
                recoverableTombstone.getSchemaId(),
                "orders",
                recoverable.id(),
                null)
            .get(0);

    TableEntity legacy = newTable(namespace, "legacy_orders");
    TableMetaService.getInstance().insertTable(legacy, false);
    SessionUtils.doWithCommit(
        TableMetaMapper.class, mapper -> mapper.softDeleteTableMetasByTableId(legacy.id()));

    Clock beforeExpiry =
        Clock.fixed(
            Instant.ofEpochMilli(recoverableTombstone.getDeletedAt() + 1_000L), ZoneOffset.UTC);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS, beforeExpiry);
    List<DeletedEntityDTO> deleted = manager.listDeletedTables(namespace, null, null);

    Assertions.assertEquals(2, deleted.size());
    DeletedEntityDTO recoverableDTO =
        deleted.stream()
            .filter(item -> item.getId().equals(String.valueOf(recoverable.id())))
            .findFirst()
            .orElseThrow();
    recoverableDTO.validate();
    Assertions.assertEquals(deletionRecord.getDeletionId(), recoverableDTO.getDeletionId());
    Assertions.assertNotNull(recoverableDTO.getDeletedBy());
    Assertions.assertTrue(recoverableDTO.getLatestForName());
    Assertions.assertTrue(recoverableDTO.getRestorable());
    Assertions.assertNull(recoverableDTO.getReason());

    DeletedEntityDTO legacyDTO =
        deleted.stream()
            .filter(item -> item.getId().equals(String.valueOf(legacy.id())))
            .findFirst()
            .orElseThrow();
    legacyDTO.validate();
    Assertions.assertFalse(legacyDTO.getRestorable());
    Assertions.assertEquals(RecoverableDeletionManager.LEGACY_TOMBSTONE, legacyDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.LEGACY_TOMBSTONE,
        () ->
            manager.restoreDeletedTable(
                namespace, legacy.name(), legacy.id(), legacyDTO.getEtag()));

    Assertions.assertEquals(
        List.of(recoverableDTO), manager.listDeletedTables(namespace, "orders", recoverable.id()));
    Assertions.assertEquals(
        recoverableDTO, manager.getDeletedTable(namespace, "orders", recoverable.id()));
    Assertions.assertThrows(
        TombstoneNotFoundException.class,
        () -> manager.getDeletedTable(namespace, "wrong-name", recoverable.id()));
    Assertions.assertTrue(manager.listDeletedTables(namespace, "missing", null).isEmpty());
  }

  @TestTemplate
  public void testModelDiscoveryExactReadRestoreAndReplay() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofModel(METALAKE, CATALOG, SCHEMA);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            "recommendation_model",
            "recoverable model",
            0,
            Map.of("framework", "test"),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);
    Assertions.assertTrue(
        ModelMetaService.getInstance().deleteModel(model.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    List<DeletedEntityDTO> discovered =
        manager.listDeletedModels(namespace, model.name(), model.id());
    Assertions.assertEquals(1, discovered.size());
    DeletedEntityDTO exact = manager.getDeletedModel(namespace, model.name(), model.id());
    Assertions.assertEquals(discovered.get(0).getEtag(), exact.getEtag());
    Assertions.assertEquals(String.valueOf(model.id()), exact.getId());
    Assertions.assertEquals("model", exact.getType().value());
    Assertions.assertTrue(exact.getLatestForName());
    Assertions.assertTrue(exact.getRestorable());

    ModelEntity restored =
        manager.restoreDeletedModel(namespace, model.name(), model.id(), exact.getEtag());
    Assertions.assertEquals(model, restored);
    Assertions.assertEquals(
        model.id(),
        manager.restoreDeletedModel(namespace, model.name(), model.id(), exact.getEtag()).id());
    Assertions.assertTrue(manager.listDeletedModels(namespace, model.name(), model.id()).isEmpty());
  }

  @TestTemplate
  public void testPolicyAggregateRestorePreservesPriorVersionAndAssociation() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("policy_recovery_metalake");
    CatalogEntity catalog = createAndInsertCatalog(metalake.name(), "policy_recovery_catalog");
    SchemaEntity schema =
        createAndInsertSchema(metalake.name(), catalog.name(), "policy_recovery_schema");
    TableEntity table =
        newTable(
            NamespaceUtil.ofTable(metalake.name(), catalog.name(), schema.name()),
            "policy_recovery_table");
    TableMetaService.getInstance().insertTable(table, false);

    Namespace namespace = NamespaceUtil.ofPolicy(metalake.name());
    PolicyEntity policy =
        createPolicy(
            RandomIdGenerator.INSTANCE.nextId(), namespace, "recoverable_policy", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyEntity policyV2 =
        PolicyEntity.builder()
            .withId(policy.id())
            .withNamespace(policy.namespace())
            .withName(policy.name())
            .withPolicyType(policy.policyType())
            .withComment("second version")
            .withEnabled(!policy.enabled())
            .withContent(policy.content())
            .withAuditInfo(AUDIT_INFO)
            .build();
    PolicyMetaService.getInstance().updatePolicy(policy.nameIdentifier(), ignored -> policyV2);
    PolicyMetaService.getInstance()
        .associatePoliciesWithMetadataObject(
            table.nameIdentifier(),
            Entity.EntityType.TABLE,
            new NameIdentifier[] {policy.nameIdentifier()},
            new NameIdentifier[0]);
    Assertions.assertEquals(1, countPolicyRelations(policy.id()));

    Assertions.assertEquals(
        1, PolicyMetaService.getInstance().deletePolicyVersionsByRetentionCount(1L, 100));
    Object[] priorVersion = policyVersionState(policy.id(), 1L);
    Assertions.assertTrue((Long) priorVersion[0] > 0);
    Assertions.assertNull(priorVersion[1]);

    Assertions.assertTrue(
        PolicyMetaService.getInstance().deletePolicy(policy.nameIdentifier(), RETENTION_MS));
    EntityDeletionPO receipt =
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.POLICY, metalake.id(), policy.name(), policy.id(), null)
            .get(0);
    Object[] currentVersion = policyVersionState(policy.id(), 2L);
    Assertions.assertEquals(receipt.getDeletionId(), currentVersion[1]);
    Assertions.assertArrayEquals(priorVersion, policyVersionState(policy.id(), 1L));
    Assertions.assertEquals(1, countPolicyRelations(policy.id()));
    Assertions.assertTrue(
        PolicyMetaService.getInstance()
            .listPoliciesForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE)
            .isEmpty());

    EntityCache cache = Mockito.mock(EntityCache.class);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS, cache);
    DeletedEntityDTO deleted = manager.getDeletedPolicy(namespace, policy.name(), policy.id());
    Assertions.assertEquals("policy", deleted.getType().value());
    Assertions.assertTrue(deleted.getLatestForName());
    Assertions.assertTrue(deleted.getRestorable());
    PolicyEntity restored =
        manager.restoreDeletedPolicy(namespace, policy.name(), policy.id(), deleted.getEtag());
    Assertions.assertEquals(policyV2, restored);
    Assertions.assertEquals(
        policy.id(),
        manager
            .restoreDeletedPolicy(namespace, policy.name(), policy.id(), deleted.getEtag())
            .id());
    Mockito.verify(cache, Mockito.atLeastOnce()).clear();
    Assertions.assertArrayEquals(priorVersion, policyVersionState(policy.id(), 1L));
    Assertions.assertEquals(0L, policyVersionState(policy.id(), 2L)[0]);
    Assertions.assertNull(policyVersionState(policy.id(), 2L)[1]);
    Assertions.assertEquals(1, countPolicyRelations(policy.id()));
    Assertions.assertEquals(
        List.of(policyV2),
        PolicyMetaService.getInstance()
            .listPoliciesForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE));
  }

  @TestTemplate
  public void testPolicyRestoreRejectsOlderGenerationAndNameOccupancy() throws Exception {
    long deletedAt = Instant.now().toEpochMilli();
    BaseMetalake metalake = createAndInsertMakeLake("repeated_policy_metalake");
    Namespace namespace = NamespaceUtil.ofPolicy(metalake.name());
    PolicyEntity first =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, "repeated_policy", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(first, false);
    PolicyMetaService.getInstance().deletePolicy(first.nameIdentifier(), deletedAt, RETENTION_MS);
    PolicyEntity reusedId = createPolicy(first.id(), namespace, "reused_policy_id", AUDIT_INFO);
    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () -> PolicyMetaService.getInstance().insertPolicy(reusedId, true));
    PolicyEntity second =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, first.name(), AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(second, false);
    PolicyMetaService.getInstance().deletePolicy(second.nameIdentifier(), deletedAt, RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO firstDeletion = manager.getDeletedPolicy(namespace, first.name(), first.id());
    DeletedEntityDTO secondDeletion =
        manager.getDeletedPolicy(namespace, second.name(), second.id());
    Assertions.assertFalse(firstDeletion.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", firstDeletion.getReason());
    Assertions.assertTrue(secondDeletion.getDeletedAt() > firstDeletion.getDeletedAt());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () ->
            manager.restoreDeletedPolicy(
                namespace, first.name(), first.id(), firstDeletion.getEtag()));

    PolicyEntity replacement =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, second.name(), AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(replacement, false);
    Assertions.assertEquals(
        "NAME_OCCUPIED",
        manager.getDeletedPolicy(namespace, second.name(), second.id()).getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () ->
            manager.restoreDeletedPolicy(
                namespace, second.name(), second.id(), secondDeletion.getEtag()));
  }

  @TestTemplate
  public void testPolicyLegacyExpiryAndExactPurge() throws Exception {
    long deletedAt = Instant.now().toEpochMilli();
    BaseMetalake metalake = createAndInsertMakeLake("policy_expiry_metalake");
    Namespace namespace = NamespaceUtil.ofPolicy(metalake.name());
    PolicyEntity legacy =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, "legacy_policy", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(legacy, false);
    SessionUtils.doWithCommit(
        PolicyVersionMapper.class,
        mapper ->
            mapper.softDeletePolicyVersionByMetalakeAndPolicyName(metalake.name(), legacy.name()));
    SessionUtils.doWithCommit(
        PolicyMetaMapper.class,
        mapper -> mapper.softDeletePolicyByMetalakeAndPolicyName(metalake.name(), legacy.name()));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO legacyDeletion =
        manager.getDeletedPolicy(namespace, legacy.name(), legacy.id());
    Assertions.assertFalse(legacyDeletion.getRestorable());
    Assertions.assertEquals(
        RecoverableDeletionManager.LEGACY_TOMBSTONE, legacyDeletion.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.LEGACY_TOMBSTONE,
        () ->
            manager.restoreDeletedPolicy(
                namespace, legacy.name(), legacy.id(), legacyDeletion.getEtag()));

    PolicyEntity expiring =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, "expiring_policy", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(expiring, false);
    PolicyMetaService.getInstance()
        .deletePolicy(expiring.nameIdentifier(), deletedAt, RETENTION_MS);
    RecoverableDeletionManager expiredManager =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(deletedAt + RETENTION_MS + 1L), ZoneOffset.UTC));
    DeletedEntityDTO expired =
        expiredManager.getDeletedPolicy(namespace, expiring.name(), expiring.id());
    Assertions.assertFalse(expired.getRestorable());
    Assertions.assertEquals("TOMBSTONE_EXPIRED", expired.getReason());
    Assertions.assertThrows(
        TombstoneExpiredException.class,
        () ->
            expiredManager.restoreDeletedPolicy(
                namespace, expiring.name(), expiring.id(), expired.getEtag()));

    Assertions.assertEquals(
        1,
        PolicyMetaService.getInstance()
            .purgeExpiredPolicyDeletions(deletedAt + RETENTION_MS + 1L, 100));
    Assertions.assertFalse(legacyRecordExistsInDB(expiring.id(), Entity.EntityType.POLICY));
    Assertions.assertEquals(0, countPolicyVersions(expiring.id()));
    EntityDeletionPO purged = EntityDeletionService.getInstance().get(expired.getDeletionId());
    Assertions.assertNotNull(purged);
    Assertions.assertEquals(DeletionState.PURGED, purged.getState());
  }

  @TestTemplate
  public void testPolicyRestoreRefusesMissingCurrentVersionWithoutPartialRevival()
      throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("broken_policy_metalake");
    Namespace namespace = NamespaceUtil.ofPolicy(metalake.name());
    PolicyEntity policy =
        createPolicy(RandomIdGenerator.INSTANCE.nextId(), namespace, "broken_policy", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyMetaService.getInstance().deletePolicy(policy.nameIdentifier(), RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedPolicy(namespace, policy.name(), policy.id());
    updateDeletionRecord(
        "DELETE FROM policy_version_info WHERE policy_id = ? AND version = ? AND deletion_id = ?",
        policy.id(),
        1L,
        deleted.getDeletionId());

    assertRecoveryConflict(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        () ->
            manager.restoreDeletedPolicy(namespace, policy.name(), policy.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(policy.nameIdentifier(), Entity.EntityType.POLICY));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testPolicyRestoreRefusesMissingNonCurrentVersionWithoutPartialRevival()
      throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("broken_policy_history_metalake");
    Namespace namespace = NamespaceUtil.ofPolicy(metalake.name());
    PolicyEntity policy =
        createPolicy(
            RandomIdGenerator.INSTANCE.nextId(), namespace, "broken_policy_history", AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyEntity policyV2 =
        PolicyEntity.builder()
            .withId(policy.id())
            .withNamespace(policy.namespace())
            .withName(policy.name())
            .withPolicyType(policy.policyType())
            .withComment("second version")
            .withEnabled(!policy.enabled())
            .withContent(policy.content())
            .withAuditInfo(AUDIT_INFO)
            .build();
    PolicyMetaService.getInstance().updatePolicy(policy.nameIdentifier(), ignored -> policyV2);
    PolicyMetaService.getInstance().deletePolicy(policy.nameIdentifier(), RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedPolicy(namespace, policy.name(), policy.id());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deleted.getDeletionId());
    Assertions.assertEquals(3L, receipt.getAffectedRowCount());
    updateDeletionRecord(
        "DELETE FROM policy_version_info WHERE policy_id = ? AND version = ? AND deletion_id = ?",
        policy.id(),
        1L,
        deleted.getDeletionId());

    assertRecoveryConflict(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        () ->
            manager.restoreDeletedPolicy(namespace, policy.name(), policy.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(policy.nameIdentifier(), Entity.EntityType.POLICY));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testIdentityRecoveryWhenPrincipalsOwnSharedMemberships() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("identity_principal_first");
    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofRole(metalake.name()),
            "shared_role",
            AUDIT_INFO,
            List.of(),
            Map.of());
    RoleMetaService.getInstance().insertRole(role, false);
    UserEntity user =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofUser(metalake.name()),
            "shared_user",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    UserMetaService.getInstance().insertUser(user, false);
    GroupEntity group =
        createGroupEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofGroup(metalake.name()),
            "shared_group",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    GroupMetaService.getInstance().insertGroup(group, false);

    Assertions.assertTrue(
        UserMetaService.getInstance().deleteUser(user.nameIdentifier(), RETENTION_MS));
    Assertions.assertTrue(
        GroupMetaService.getInstance().deleteGroup(group.nameIdentifier(), RETENTION_MS));
    Assertions.assertTrue(
        RoleMetaService.getInstance().deleteRole(role.nameIdentifier(), RETENTION_MS));
    Assertions.assertEquals(2L, identityDeletion(metalake, user).getAffectedRowCount().longValue());
    Assertions.assertEquals(
        2L, identityDeletion(metalake, group).getAffectedRowCount().longValue());
    Assertions.assertEquals(1L, identityDeletion(metalake, role).getAffectedRowCount().longValue());

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deletedUser = manager.getDeletedUser(user.namespace(), user.name(), user.id());
    DeletedEntityDTO deletedGroup =
        manager.getDeletedGroup(group.namespace(), group.name(), group.id());
    DeletedEntityDTO deletedRole = manager.getDeletedRole(role.namespace(), role.name(), role.id());
    manager.restoreDeletedUser(user.namespace(), user.name(), user.id(), deletedUser.getEtag());
    manager.restoreDeletedGroup(
        group.namespace(), group.name(), group.id(), deletedGroup.getEtag());

    Assertions.assertTrue(
        RoleMetaService.getInstance().listRolesByUserIdent(user.nameIdentifier()).isEmpty());
    Assertions.assertTrue(RoleMetaService.getInstance().listRolesByGroupId(group.id()).isEmpty());
    manager.restoreDeletedRole(role.namespace(), role.name(), role.id(), deletedRole.getEtag());
    Assertions.assertEquals(
        role.id(),
        RoleMetaService.getInstance().listRolesByUserIdent(user.nameIdentifier()).get(0).id());
    Assertions.assertEquals(
        role.id(), RoleMetaService.getInstance().listRolesByGroupId(group.id()).get(0).getRoleId());
    Assertions.assertEquals(
        user.id(),
        manager
            .restoreDeletedUser(user.namespace(), user.name(), user.id(), deletedUser.getEtag())
            .id());
  }

  @TestTemplate
  public void testIdentityRecoveryWhenRoleOwnsSharedMemberships() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("identity_role_first");
    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofRole(metalake.name()),
            "shared_role",
            AUDIT_INFO,
            List.of(),
            Map.of());
    RoleMetaService.getInstance().insertRole(role, false);
    UserEntity user =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofUser(metalake.name()),
            "shared_user",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    UserMetaService.getInstance().insertUser(user, false);
    GroupEntity group =
        createGroupEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofGroup(metalake.name()),
            "shared_group",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    GroupMetaService.getInstance().insertGroup(group, false);

    Assertions.assertTrue(
        RoleMetaService.getInstance().deleteRole(role.nameIdentifier(), RETENTION_MS));
    Assertions.assertTrue(
        UserMetaService.getInstance().deleteUser(user.nameIdentifier(), RETENTION_MS));
    Assertions.assertTrue(
        GroupMetaService.getInstance().deleteGroup(group.nameIdentifier(), RETENTION_MS));
    Assertions.assertEquals(3L, identityDeletion(metalake, role).getAffectedRowCount().longValue());
    Assertions.assertEquals(1L, identityDeletion(metalake, user).getAffectedRowCount().longValue());
    Assertions.assertEquals(
        1L, identityDeletion(metalake, group).getAffectedRowCount().longValue());

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deletedRole = manager.getDeletedRole(role.namespace(), role.name(), role.id());
    DeletedEntityDTO deletedUser = manager.getDeletedUser(user.namespace(), user.name(), user.id());
    DeletedEntityDTO deletedGroup =
        manager.getDeletedGroup(group.namespace(), group.name(), group.id());
    manager.restoreDeletedRole(role.namespace(), role.name(), role.id(), deletedRole.getEtag());

    Assertions.assertTrue(
        UserMetaService.getInstance().listUsersByRoleIdent(role.nameIdentifier()).isEmpty());
    Assertions.assertTrue(
        GroupMetaService.getInstance().listGroupsByRoleIdent(role.nameIdentifier()).isEmpty());
    manager.restoreDeletedUser(user.namespace(), user.name(), user.id(), deletedUser.getEtag());
    manager.restoreDeletedGroup(
        group.namespace(), group.name(), group.id(), deletedGroup.getEtag());
    Assertions.assertEquals(
        user.id(),
        UserMetaService.getInstance().listUsersByRoleIdent(role.nameIdentifier()).get(0).id());
    Assertions.assertEquals(
        group.id(),
        GroupMetaService.getInstance().listGroupsByRoleIdent(role.nameIdentifier()).get(0).id());
  }

  @TestTemplate
  public void testIdentityExternalIdConflictAndIncompleteGeneration() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("identity_conflicts");
    UserEntity original =
        UserEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("original_user")
            .withNamespace(NamespaceUtil.ofUser(metalake.name()))
            .withExternalId("external-user-1")
            .withAuditInfo(AUDIT_INFO)
            .build();
    UserMetaService.getInstance().insertUser(original, false);
    Assertions.assertTrue(
        UserMetaService.getInstance().deleteUser(original.nameIdentifier(), RETENTION_MS));
    UserEntity replacement =
        UserEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("replacement_user")
            .withNamespace(original.namespace())
            .withExternalId(original.externalId())
            .withAuditInfo(AUDIT_INFO)
            .build();
    UserMetaService.getInstance().insertUser(replacement, false);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO occupied =
        manager.getDeletedUser(original.namespace(), original.name(), original.id());
    Assertions.assertFalse(occupied.getRestorable());
    Assertions.assertEquals("EXTERNAL_ID_OCCUPIED", occupied.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
        () ->
            manager.restoreDeletedUser(
                original.namespace(), original.name(), original.id(), occupied.getEtag()));

    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofRole(metalake.name()),
            "broken_role",
            AUDIT_INFO,
            List.of(),
            Map.of());
    RoleMetaService.getInstance().insertRole(role, false);
    UserEntity broken =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofUser(metalake.name()),
            "broken_user",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    UserMetaService.getInstance().insertUser(broken, false);
    Assertions.assertTrue(
        UserMetaService.getInstance().deleteUser(broken.nameIdentifier(), RETENTION_MS));
    DeletedEntityDTO brokenDeletion =
        manager.getDeletedUser(broken.namespace(), broken.name(), broken.id());
    updateDeletionRecord(
        "DELETE FROM user_role_rel WHERE user_id = ? AND deletion_id = ?",
        broken.id(),
        brokenDeletion.getDeletionId());

    assertRecoveryConflict(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        () ->
            manager.restoreDeletedUser(
                broken.namespace(), broken.name(), broken.id(), brokenDeletion.getEtag()));
    Assertions.assertFalse(backend.exists(broken.nameIdentifier(), Entity.EntityType.USER));
    assertDeletionReceiptUnchanged(brokenDeletion.getDeletionId());
  }

  @TestTemplate
  public void testManagedFilesetDiscoveryMetadataRestoreAndReplay() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofFileset(METALAKE, CATALOG, SCHEMA);
    FilesetEntity fileset = newFileset(namespace, "managed_fileset");
    FilesetMetaService.getInstance().insertFileset(fileset, false);
    Assertions.assertTrue(
        FilesetMetaService.getInstance().deleteFileset(fileset.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO exact = manager.getDeletedFileset(namespace, fileset.name(), fileset.id());
    Assertions.assertEquals("fileset", exact.getType().value());
    Assertions.assertTrue(exact.getLatestForName());
    Assertions.assertTrue(exact.getRestorable());

    FilesetEntity restored =
        manager.restoreDeletedFileset(namespace, fileset.name(), fileset.id(), exact.getEtag());
    Assertions.assertEquals(fileset, restored);
    Assertions.assertEquals(Fileset.Type.MANAGED, restored.filesetType());
    Assertions.assertEquals(
        "/warehouse/managed_fileset",
        restored.storageLocations().get(Fileset.LOCATION_NAME_UNKNOWN));
    Assertions.assertEquals(
        fileset.id(),
        manager
            .restoreDeletedFileset(namespace, fileset.name(), fileset.id(), exact.getEtag())
            .id());
    Assertions.assertTrue(
        manager.listDeletedFilesets(namespace, fileset.name(), fileset.id()).isEmpty());
  }

  @TestTemplate
  public void testViewDiscoveryMetadataRestoreAndReplay() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofView(METALAKE, CATALOG, SCHEMA);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, "monthly_revenue");
    ViewMetaService.getInstance().insertView(view, false);
    Assertions.assertTrue(
        ViewMetaService.getInstance().deleteView(view.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    List<DeletedEntityDTO> discovered = manager.listDeletedViews(namespace, view.name(), view.id());
    Assertions.assertEquals(1, discovered.size());
    DeletedEntityDTO exact = manager.getDeletedView(namespace, view.name(), view.id());
    Assertions.assertEquals(discovered.get(0).getEtag(), exact.getEtag());
    Assertions.assertEquals(String.valueOf(view.id()), exact.getId());
    Assertions.assertEquals("view", exact.getType().value());
    Assertions.assertTrue(exact.getLatestForName());
    Assertions.assertTrue(exact.getRestorable());

    ViewEntity restored =
        manager.restoreDeletedView(namespace, view.name(), view.id(), exact.getEtag());
    Assertions.assertEquals(view.id(), restored.id());
    Assertions.assertEquals(view.name(), restored.name());
    Assertions.assertEquals(view.namespace(), restored.namespace());
    Assertions.assertEquals(view.columns().length, restored.columns().length);
    Assertions.assertArrayEquals(view.representations(), restored.representations());
    Assertions.assertEquals(
        view.id(),
        manager.restoreDeletedView(namespace, view.name(), view.id(), exact.getEtag()).id());
    Assertions.assertTrue(manager.listDeletedViews(namespace, view.name(), view.id()).isEmpty());
  }

  @TestTemplate
  public void testTopicDiscoveryMetadataRestoreAndReplay() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTopic(METALAKE, CATALOG, SCHEMA);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(), namespace, "metadata_only_topic", AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);
    Assertions.assertTrue(
        TopicMetaService.getInstance().deleteTopic(topic.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    List<DeletedEntityDTO> discovered =
        manager.listDeletedTopics(namespace, topic.name(), topic.id());
    Assertions.assertEquals(1, discovered.size());
    DeletedEntityDTO exact = manager.getDeletedTopic(namespace, topic.name(), topic.id());
    Assertions.assertEquals(discovered.get(0).getEtag(), exact.getEtag());
    Assertions.assertEquals(String.valueOf(topic.id()), exact.getId());
    Assertions.assertEquals("topic", exact.getType().value());
    Assertions.assertTrue(exact.getLatestForName());
    Assertions.assertTrue(exact.getRestorable());

    TopicEntity restored =
        manager.restoreDeletedTopic(namespace, topic.name(), topic.id(), exact.getEtag());
    Assertions.assertEquals(topic, restored);
    Assertions.assertEquals(
        topic.id(),
        manager.restoreDeletedTopic(namespace, topic.name(), topic.id(), exact.getEtag()).id());
    Assertions.assertTrue(manager.listDeletedTopics(namespace, topic.name(), topic.id()).isEmpty());
  }

  @TestTemplate
  public void testTopicRestoreFailsWhenParentDisappears() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTopic(METALAKE, CATALOG, SCHEMA);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(), namespace, "parent_bound_topic", AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);
    Assertions.assertTrue(
        TopicMetaService.getInstance().deleteTopic(topic.nameIdentifier(), RETENTION_MS));
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    String etag = manager.getDeletedTopic(namespace, topic.name(), topic.id()).getEtag();

    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(METALAKE, CATALOG, SCHEMA), true));
    assertRecoveryConflict(
        RecoveryConflictReason.PARENT_CHANGED,
        () -> manager.restoreDeletedTopic(namespace, topic.name(), topic.id(), etag));
  }

  @TestTemplate
  public void testViewRestoreFailsWhenParentDisappears() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofView(METALAKE, CATALOG, SCHEMA);
    ViewEntity view =
        createViewEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, "parent_bound_view");
    ViewMetaService.getInstance().insertView(view, false);
    Assertions.assertTrue(
        ViewMetaService.getInstance().deleteView(view.nameIdentifier(), RETENTION_MS));
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    String etag = manager.getDeletedView(namespace, view.name(), view.id()).getEtag();

    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(METALAKE, CATALOG, SCHEMA), true));
    assertRecoveryConflict(
        RecoveryConflictReason.PARENT_CHANGED,
        () -> manager.restoreDeletedView(namespace, view.name(), view.id(), etag));
  }

  @TestTemplate
  public void testLiveNameAndExpiryBlockRestore() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);

    TableEntity deletedTable = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(deletedTable, false);
    TableMetaService.getInstance().deleteTable(deletedTable.nameIdentifier(), RETENTION_MS);
    TablePO tombstone =
        TableMetaService.getInstance().listDeletedTablesByNamespace(namespace).get(0);
    EntityDeletionPO deletionRecord =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TABLE, tombstone.getSchemaId(), "orders", deletedTable.id(), null)
            .get(0);

    TableEntity replacement = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(replacement, false);
    RecoverableDeletionManager beforeExpiry =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(tombstone.getDeletedAt() + 1L), ZoneOffset.UTC));
    DeletedEntityDTO occupied =
        beforeExpiry.listDeletedTables(namespace, null, deletedTable.id()).get(0);
    Assertions.assertFalse(occupied.getRestorable());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());

    TableMetaService.getInstance().deleteTable(replacement.nameIdentifier());
    RecoverableDeletionManager afterExpiry =
        new RecoverableDeletionManager(
            RETENTION_MS,
            Clock.fixed(Instant.ofEpochMilli(deletionRecord.getExpiresAt()), ZoneOffset.UTC));
    DeletedEntityDTO expired =
        afterExpiry.listDeletedTables(namespace, null, deletedTable.id()).get(0);
    Assertions.assertFalse(expired.getRestorable());
    Assertions.assertEquals("TOMBSTONE_EXPIRED", expired.getReason());
  }

  @TestTemplate
  public void testConditionalRestoreIsIdempotentAcrossRetentionConfigChanges() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    long deletedAt = Instant.now().toEpochMilli();

    TableEntity table = newTable(namespace, "orders");
    TableMetaService.getInstance().insertTable(table, false);
    EntityDeletionPO deletion = deleteAndGetDeletionRecord(table, deletedAt, RETENTION_MS);
    EntityCache entityCache = Mockito.mock(EntityCache.class);
    RecoverableDeletionManager firstManager =
        new RecoverableDeletionManager(RETENTION_MS, entityCache);

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            firstManager.restoreDeletedTable(
                namespace,
                table.name(),
                table.id(),
                "deletion-stale-representation-0000000000000000000000000000000000000000000000000000000000000000"));

    String etag = firstManager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    TableEntity restored =
        firstManager.restoreDeletedTable(namespace, table.name(), table.id(), etag);
    Assertions.assertEquals(table.id(), restored.id());
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(etag, receipt.getRestoreEtag());

    RecoverableDeletionManager restartedManager =
        new RecoverableDeletionManager(RETENTION_MS * 2, entityCache);
    Assertions.assertEquals(
        table.id(),
        restartedManager.restoreDeletedTable(namespace, table.name(), table.id(), etag).id());
    Assertions.assertTrue(
        restartedManager.listDeletedTables(namespace, table.name(), table.id()).isEmpty());
    Mockito.verify(entityCache, Mockito.times(2))
        .invalidate(table.nameIdentifier(), Entity.EntityType.TABLE);
    for (SupportsRelationOperations.Type relationType : SupportsRelationOperations.Type.values()) {
      Mockito.verify(entityCache, Mockito.times(2))
          .invalidateRelationEntry(table.nameIdentifier(), Entity.EntityType.TABLE, relationType);
    }
  }

  @TestTemplate
  public void testConcurrentTableRestoreTransactionsConvergeToSingleGeneration() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    TableEntity table = newTable(namespace, "concurrent_orders");
    TableMetaService.getInstance().insertTable(table, false);
    EntityDeletionPO deletion =
        deleteAndGetDeletionRecord(table, Instant.now().toEpochMilli(), RETENTION_MS);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    String etag = manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();

    // Exercise the relational transaction directly to model servers with independent JVM locks.
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<TableEntity> first =
          executor.submit(
              () ->
                  restoreTableAfterSignal(
                      table, deletion, etag, deletion.getExpiresAt(), ready, start));
      Future<TableEntity> second =
          executor.submit(
              () ->
                  restoreTableAfterSignal(
                      table, deletion, etag, deletion.getExpiresAt(), ready, start));

      Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();

      Assertions.assertEquals(table.id(), first.get(30, TimeUnit.SECONDS).id());
      Assertions.assertEquals(table.id(), second.get(30, TimeUnit.SECONDS).id());
    } finally {
      start.countDown();
      executor.shutdownNow();
      Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    EntityDeletionPO restored = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    Assertions.assertEquals(DeletionState.RESTORED, restored.getState());
    Assertions.assertEquals(2L, restored.getRevision());
    Assertions.assertEquals(etag, restored.getRestoreEtag());
    Assertions.assertEquals(
        table.id(),
        TableMetaService.getInstance().getTableByIdentifier(table.nameIdentifier()).id());
    Assertions.assertTrue(manager.listDeletedTables(namespace, table.name(), table.id()).isEmpty());
  }

  @TestTemplate
  public void testImmutableTableIdReuseIsDetectedAcrossNamespaces() throws Exception {
    long deletedAt = 1_800_000_000_000L;
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    String reuseSchema = SCHEMA + "_reuse";
    createAndInsertSchema(METALAKE, CATALOG, reuseSchema);
    Namespace originalNamespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    Namespace reusedNamespace = NamespaceUtil.ofTable(METALAKE, CATALOG, reuseSchema);

    TableEntity deletedTable = newTable(originalNamespace, "orders");
    TableMetaService.getInstance().insertTable(deletedTable, false);
    deleteAndGetDeletionRecord(deletedTable, deletedAt, RETENTION_MS);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(deletedAt + 1_000L), ZoneOffset.UTC));
    String observedEtag =
        manager
            .getDeletedTable(originalNamespace, deletedTable.name(), deletedTable.id())
            .getEtag();

    TableEntity reusedTable =
        createTableEntity(deletedTable.id(), reusedNamespace, "reused_orders", AUDIT_INFO);
    TableMetaService.getInstance().insertTable(reusedTable, true);

    Assertions.assertTrue(
        manager
            .listDeletedTables(originalNamespace, deletedTable.name(), deletedTable.id())
            .isEmpty());
    assertRecoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        () ->
            manager.restoreDeletedTable(
                originalNamespace, deletedTable.name(), deletedTable.id(), observedEtag));
  }

  @TestTemplate
  public void testEntityTagCoversHiddenDeletionRecordState() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    long deletedAt = 1_800_000_000_000L;
    TableEntity table = newTable(namespace, "deletion_record_changed_orders");
    TableMetaService.getInstance().insertTable(table, false);
    EntityDeletionPO deletionRecord = deleteAndGetDeletionRecord(table, deletedAt, RETENTION_MS);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(deletedAt + 1_000L), ZoneOffset.UTC));
    String observedEtag = manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();

    updateDeletionRecord(
        "UPDATE entity_deletion SET expires_at = ? WHERE deletion_id = ?",
        deletionRecord.getExpiresAt() + 1L,
        deletionRecord.getDeletionId());
    String changedStoredExpiryEtag =
        manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    Assertions.assertNotEquals(observedEtag, changedStoredExpiryEtag);
    updateDeletionRecord(
        "UPDATE entity_deletion SET expires_at = ? WHERE deletion_id = ?",
        deletionRecord.getExpiresAt(),
        deletionRecord.getDeletionId());
    Assertions.assertEquals(
        observedEtag, manager.getDeletedTable(namespace, table.name(), table.id()).getEtag());

    updateDeletionRecord(
        "UPDATE entity_deletion SET revision = ? WHERE deletion_id = ?",
        deletionRecord.getRevision() + 1L,
        deletionRecord.getDeletionId());

    String changedEtag = manager.getDeletedTable(namespace, table.name(), table.id()).getEtag();
    Assertions.assertNotEquals(observedEtag, changedEtag);
    Assertions.assertThrows(
        TombstoneChangedException.class,
        () -> manager.restoreDeletedTable(namespace, table.name(), table.id(), observedEtag));
  }

  @TestTemplate
  public void testTableRestoreRollsBackReceiptClaimWhenGenerationValidationFails()
      throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    TableEntity table = newTable(namespace, "incomplete_generation_orders");
    TableMetaService.getInstance().insertTable(table, false);
    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(table.nameIdentifier(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedTable(namespace, table.name(), table.id());
    updateDeletionRecord(
        "DELETE FROM table_version_info WHERE table_id = ? AND deletion_id = ?",
        table.id(),
        deleted.getDeletionId());

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () -> manager.restoreDeletedTable(namespace, table.name(), table.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deleted.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(DeletionState.DELETED, receipt.getState());
    Assertions.assertEquals(0L, receipt.getRevision());
  }

  @TestTemplate
  public void testRestoreFailurePrecedence() throws Exception {
    long now = 1_800_000_000_000L;
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    Namespace namespace = NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA);
    RecoverableDeletionManager manager =
        new RecoverableDeletionManager(
            RETENTION_MS, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));

    TableEntity expiredTable = newTable(namespace, "expired_orders");
    TableMetaService.getInstance().insertTable(expiredTable, false);
    EntityDeletionPO expired =
        deleteAndGetDeletionRecord(expiredTable, now - RETENTION_MS, RETENTION_MS * 2);
    DeletedEntityDTO expiredDTO =
        manager.getDeletedTable(namespace, expiredTable.name(), expiredTable.id());
    Assertions.assertTrue(expired.getExpiresAt() > now);
    Assertions.assertEquals(now, expiredDTO.getExpiresAt());
    Assertions.assertThrows(
        TombstoneExpiredException.class,
        () ->
            manager.restoreDeletedTable(
                namespace, expiredTable.name(), expiredTable.id(), expiredDTO.getEtag()));

    TableEntity occupiedTable = newTable(namespace, "occupied_orders");
    TableMetaService.getInstance().insertTable(occupiedTable, false);
    deleteAndGetDeletionRecord(occupiedTable, now - 1_000L, RETENTION_MS);
    String beforeOccupancy =
        manager.getDeletedTable(namespace, occupiedTable.name(), occupiedTable.id()).getEtag();
    TableMetaService.getInstance().insertTable(newTable(namespace, occupiedTable.name()), false);
    DeletedEntityDTO occupiedDTO =
        manager.getDeletedTable(namespace, occupiedTable.name(), occupiedTable.id());
    Assertions.assertNotEquals(beforeOccupancy, occupiedDTO.getEtag());
    Assertions.assertEquals("NAME_OCCUPIED", occupiedDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () ->
            manager.restoreDeletedTable(
                namespace, occupiedTable.name(), occupiedTable.id(), beforeOccupancy));

    TableEntity oldTable = newTable(namespace, "repeated_orders");
    TableMetaService.getInstance().insertTable(oldTable, false);
    deleteAndGetDeletionRecord(oldTable, now - 2_000L, RETENTION_MS);
    TableEntity newTable = newTable(namespace, oldTable.name());
    TableMetaService.getInstance().insertTable(newTable, false);
    deleteAndGetDeletionRecord(newTable, now - 1_000L, RETENTION_MS);
    DeletedEntityDTO oldGenerationDTO =
        manager.getDeletedTable(namespace, oldTable.name(), oldTable.id());
    Assertions.assertFalse(oldGenerationDTO.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", oldGenerationDTO.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () ->
            manager.restoreDeletedTable(
                namespace, oldTable.name(), oldTable.id(), oldGenerationDTO.getEtag()));

    TableEntity changedParentTable = newTable(namespace, "parent_changed_orders");
    TableMetaService.getInstance().insertTable(changedParentTable, false);
    deleteAndGetDeletionRecord(changedParentTable, now - 1_000L, RETENTION_MS);
    String changedParentEtag =
        manager
            .getDeletedTable(namespace, changedParentTable.name(), changedParentTable.id())
            .getEtag();
    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(METALAKE, CATALOG, SCHEMA), true));
    createAndInsertSchema(METALAKE, CATALOG, SCHEMA);
    assertRecoveryConflict(
        RecoveryConflictReason.PARENT_CHANGED,
        () ->
            manager.restoreDeletedTable(
                namespace, changedParentTable.name(), changedParentTable.id(), changedParentEtag));
  }

  @TestTemplate
  public void testMetalakeCascadeListsOnlyRootAndRestoresExactMetadataTree() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, CATALOG);
    SchemaEntity schema = createAndInsertSchema(METALAKE, CATALOG, "metalake_tree_schema");
    TableEntity table =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schema.name()), "metalake_tree_table");
    TableMetaService.getInstance().insertTable(table, false);

    TagEntity tag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("metalake_tree_tag")
            .withNamespace(NamespaceUtil.ofTag(METALAKE))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            table.nameIdentifier(),
            Entity.EntityType.TABLE,
            new NameIdentifier[] {tag.nameIdentifier()},
            new NameIdentifier[0]);

    PolicyEntity policy =
        createPolicy(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofPolicy(METALAKE),
            "metalake_tree_policy",
            AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyMetaService.getInstance()
        .associatePoliciesWithMetadataObject(
            table.nameIdentifier(),
            Entity.EntityType.TABLE,
            new NameIdentifier[] {policy.nameIdentifier()},
            new NameIdentifier[0]);

    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofRole(METALAKE),
            "metalake_tree_role",
            AUDIT_INFO,
            CATALOG);
    RoleMetaService.getInstance().insertRole(role, false);
    UserEntity user =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofUser(METALAKE),
            "metalake_tree_user",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    UserMetaService.getInstance().insertUser(user, false);
    GroupEntity group =
        createGroupEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofGroup(METALAKE),
            "metalake_tree_group",
            AUDIT_INFO,
            List.of(role.name()),
            List.of(role.id()));
    GroupMetaService.getInstance().insertGroup(group, false);

    JobTemplateEntity jobTemplate =
        createAndInsertShellJobTemplateEntity(
            "metalake_tree_job_template", "tree restore template", METALAKE);
    JobEntity job =
        JobEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withJobExecutionId(String.valueOf(RandomIdGenerator.INSTANCE.nextId()))
            .withNamespace(NamespaceUtil.ofJob(METALAKE))
            .withJobTemplateName(jobTemplate.name())
            .withStatus(JobHandle.Status.QUEUED)
            .withAuditInfo(AUDIT_INFO)
            .build();
    JobMetaService.getInstance().insertJob(job, false);

    TableEntity priorTombstone =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schema.name()), "prior_metalake_table");
    TableMetaService.getInstance().insertTable(priorTombstone, false);
    EntityDeletionPO priorDeletion =
        deleteAndGetDeletionRecord(priorTombstone, Instant.now().toEpochMilli(), RETENTION_MS);

    Assertions.assertTrue(
        MetalakeMetaService.getInstance()
            .deleteMetalake(
                metalake.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS));

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    List<DeletedEntityDTO> deletedMetalakes =
        manager.listDeletedMetalakes(metalake.name(), metalake.id());
    Assertions.assertEquals(1, deletedMetalakes.size());
    DeletedEntityDTO deleted = deletedMetalakes.get(0);
    Assertions.assertEquals(String.valueOf(metalake.id()), deleted.getId());
    Assertions.assertEquals("metalake", deleted.getType().value());
    Assertions.assertTrue(deleted.getLatestForName());
    Assertions.assertTrue(deleted.getRestorable());

    List<EntityDeletionPO> rootReceipts =
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.METALAKE, null, metalake.name(), metalake.id(), null);
    Assertions.assertEquals(1, rootReceipts.size());
    Assertions.assertNull(rootReceipts.get(0).getParentId());
    Assertions.assertEquals(metalake.id(), rootReceipts.get(0).getMetalakeId());
    Assertions.assertTrue(
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.CATALOG, metalake.id(), catalog.name(), catalog.id(), null)
            .isEmpty());

    BaseMetalake restored =
        manager.restoreDeletedMetalake(metalake.name(), metalake.id(), deleted.getEtag());
    Assertions.assertEquals(metalake.id(), restored.id());
    Assertions.assertTrue(backend.exists(metalake.nameIdentifier(), Entity.EntityType.METALAKE));
    Assertions.assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    Assertions.assertTrue(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertTrue(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertEquals(
        List.of(tag),
        TagMetaService.getInstance()
            .listTagsForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertEquals(
        List.of(policy),
        PolicyMetaService.getInstance()
            .listPoliciesForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertEquals(
        role.id(), RoleMetaService.getInstance().getRoleByIdentifier(role.nameIdentifier()).id());
    UserEntity restoredUser =
        UserMetaService.getInstance().getUserByIdentifier(user.nameIdentifier());
    Assertions.assertEquals(user.id(), restoredUser.id());
    Assertions.assertEquals(List.of(role.id()), restoredUser.roleIds());
    GroupEntity restoredGroup =
        GroupMetaService.getInstance().getGroupByIdentifier(group.nameIdentifier());
    Assertions.assertEquals(group.id(), restoredGroup.id());
    Assertions.assertEquals(List.of(role.id()), restoredGroup.roleIds());
    Assertions.assertEquals(
        jobTemplate.id(),
        JobTemplateMetaService.getInstance()
            .getJobTemplateByIdentifier(jobTemplate.nameIdentifier())
            .id());
    Assertions.assertEquals(
        job.id(), JobMetaService.getInstance().getJobByIdentifier(job.nameIdentifier()).id());
    Assertions.assertFalse(
        backend.exists(priorTombstone.nameIdentifier(), Entity.EntityType.TABLE));
    EntityDeletionPO unchangedPrior =
        EntityDeletionService.getInstance().get(priorDeletion.getDeletionId());
    Assertions.assertNotNull(unchangedPrior);
    Assertions.assertEquals(DeletionState.DELETED, unchangedPrior.getState());
    Assertions.assertEquals(
        metalake.id(),
        manager.restoreDeletedMetalake(metalake.name(), metalake.id(), deleted.getEtag()).id());
    Assertions.assertTrue(manager.listDeletedMetalakes(metalake.name(), metalake.id()).isEmpty());
  }

  @TestTemplate
  public void testMetalakeRestoreRejectsOlderGenerationAndNameOccupancy() throws Exception {
    long requestedDeletedAt = Instant.now().toEpochMilli();
    BaseMetalake first = createAndInsertMakeLake("repeated_metalake");
    MetalakeMetaService.getInstance()
        .deleteMetalake(first.nameIdentifier(), true, requestedDeletedAt, RETENTION_MS);
    BaseMetalake second = createAndInsertMakeLake(first.name());
    MetalakeMetaService.getInstance()
        .deleteMetalake(second.nameIdentifier(), true, requestedDeletedAt, RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO firstDeletion = manager.getDeletedMetalake(first.name(), first.id());
    DeletedEntityDTO secondDeletion = manager.getDeletedMetalake(second.name(), second.id());
    Assertions.assertFalse(firstDeletion.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", firstDeletion.getReason());
    Assertions.assertTrue(secondDeletion.getLatestForName());
    Assertions.assertTrue(secondDeletion.getDeletedAt() > firstDeletion.getDeletedAt());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () -> manager.restoreDeletedMetalake(first.name(), first.id(), firstDeletion.getEtag()));

    BaseMetalake replacement = createAndInsertMakeLake(second.name());
    DeletedEntityDTO occupied = manager.getDeletedMetalake(second.name(), second.id());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () -> manager.restoreDeletedMetalake(second.name(), second.id(), secondDeletion.getEtag()));
    Assertions.assertTrue(backend.exists(replacement.nameIdentifier(), Entity.EntityType.METALAKE));
  }

  @TestTemplate
  public void testMetalakeRestoreRefusesIncompletePolicyAggregateWithoutPartialRevival()
      throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("broken_metalake");
    PolicyEntity policy =
        createPolicy(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofPolicy(metalake.name()),
            "broken_policy",
            AUDIT_INFO);
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    MetalakeMetaService.getInstance()
        .deleteMetalake(
            metalake.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedMetalake(metalake.name(), metalake.id());
    updateDeletionRecord(
        "DELETE FROM policy_version_info WHERE policy_id = ? AND deletion_id = ?",
        policy.id(),
        deleted.getDeletionId());

    RecoveryConflictException conflict =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                manager.restoreDeletedMetalake(metalake.name(), metalake.id(), deleted.getEtag()));
    Assertions.assertEquals(RecoveryConflictReason.INCOMPLETE_GENERATION, conflict.getReason());
    Assertions.assertFalse(backend.exists(metalake.nameIdentifier(), Entity.EntityType.METALAKE));
    Assertions.assertFalse(backend.exists(policy.nameIdentifier(), Entity.EntityType.POLICY));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testMetalakeRestoreRefusesContainerTreeWithMissingStampedParent() throws Exception {
    BaseMetalake metalake = createAndInsertMakeLake("broken_container_metalake");
    CatalogEntity catalog = createAndInsertCatalog(metalake.name(), "broken_container_catalog");
    SchemaEntity schema =
        createAndInsertSchema(metalake.name(), catalog.name(), "broken_container_schema");
    MetalakeMetaService.getInstance()
        .deleteMetalake(
            metalake.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedMetalake(metalake.name(), metalake.id());
    updateDeletionRecord(
        "DELETE FROM catalog_meta WHERE catalog_id = ? AND deletion_id = ?",
        catalog.id(),
        deleted.getDeletionId());

    RecoveryConflictException conflict =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                manager.restoreDeletedMetalake(metalake.name(), metalake.id(), deleted.getEtag()));
    Assertions.assertEquals(RecoveryConflictReason.INCOMPLETE_GENERATION, conflict.getReason());
    Assertions.assertFalse(backend.exists(metalake.nameIdentifier(), Entity.EntityType.METALAKE));
    Assertions.assertFalse(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testExpiredMetalakeGenerationIsPurgedAsOneAggregate() throws Exception {
    long deletedAt = Instant.now().toEpochMilli();
    BaseMetalake metalake = createAndInsertMakeLake("purged_metalake");
    CatalogEntity catalog = createAndInsertCatalog(metalake.name(), "purged_catalog");
    SchemaEntity schema = createAndInsertSchema(metalake.name(), catalog.name(), "purged_schema");
    TableEntity table =
        newTable(
            NamespaceUtil.ofTable(metalake.name(), catalog.name(), schema.name()), "purged_table");
    TableMetaService.getInstance().insertTable(table, false);
    MetalakeMetaService.getInstance()
        .deleteMetalake(metalake.nameIdentifier(), true, deletedAt, 0L);
    DeletedEntityDTO deletion =
        new RecoverableDeletionManager(RETENTION_MS)
            .getDeletedMetalake(metalake.name(), metalake.id());

    Assertions.assertEquals(
        1, MetalakeMetaService.getInstance().purgeExpiredMetalakeDeletions(deletedAt + 1L, 100));
    Assertions.assertFalse(legacyRecordExistsInDB(metalake.id(), Entity.EntityType.METALAKE));
    Assertions.assertFalse(legacyRecordExistsInDB(catalog.id(), Entity.EntityType.CATALOG));
    Assertions.assertFalse(legacyRecordExistsInDB(schema.id(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(legacyRecordExistsInDB(table.id(), Entity.EntityType.TABLE));
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(DeletionState.PURGED, receipt.getState());
  }

  @TestTemplate
  public void testCatalogCascadeListsOnlyRootAndRestoresExactMetadataTree() throws Exception {
    createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, CATALOG);
    String schemaName = "catalog_tree_a:catalog_tree_b";
    SchemaEntity schema = createAndInsertSchema(METALAKE, CATALOG, schemaName);

    TableEntity table =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schemaName), "catalog_tree_table");
    TableMetaService.getInstance().insertTable(table, false);
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(METALAKE, CATALOG, schemaName),
            "catalog_tree_fileset",
            AUDIT_INFO);
    FilesetMetaService.getInstance().insertFileset(fileset, false);
    FunctionEntity function =
        createFunctionEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFunction(METALAKE, CATALOG, schemaName),
            "catalog_tree_function",
            AUDIT_INFO);
    FunctionMetaService.getInstance().insertFunction(function, false);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofTopic(METALAKE, CATALOG, schemaName),
            "catalog_tree_topic",
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);
    ViewEntity view =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofView(METALAKE, CATALOG, schemaName),
            "catalog_tree_view");
    ViewMetaService.getInstance().insertView(view, false);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofModel(METALAKE, CATALOG, schemaName),
            "catalog_tree_model",
            "catalog tree model",
            0,
            Map.of("kind", "test"),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);
    ModelVersionEntity modelVersion =
        ModelVersionEntity.builder()
            .withModelIdentifier(model.nameIdentifier())
            .withVersion(0)
            .withUris(Map.of(ModelVersion.URI_NAME_UNKNOWN, "/models/catalog_tree_model"))
            .withAliases(List.of("production"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    ModelVersionMetaService.getInstance().insertModelVersion(modelVersion);

    TagEntity catalogTag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("catalog_tree_tag")
            .withNamespace(NamespaceUtil.ofTag(METALAKE))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(catalogTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            catalog.nameIdentifier(),
            Entity.EntityType.CATALOG,
            new NameIdentifier[] {catalogTag.nameIdentifier()},
            new NameIdentifier[0]);

    TableEntity priorTombstone =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schemaName), "prior_catalog_table");
    TableMetaService.getInstance().insertTable(priorTombstone, false);
    EntityDeletionPO priorDeletion =
        deleteAndGetDeletionRecord(priorTombstone, Instant.now().toEpochMilli(), RETENTION_MS);

    Assertions.assertTrue(
        CatalogMetaService.getInstance()
            .deleteCatalog(
                catalog.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS));

    Namespace namespace = Namespace.of(METALAKE);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    List<DeletedEntityDTO> deletedCatalogs =
        manager.listDeletedCatalogs(namespace, catalog.name(), catalog.id());
    Assertions.assertEquals(1, deletedCatalogs.size());
    DeletedEntityDTO deleted = deletedCatalogs.get(0);
    Assertions.assertEquals(String.valueOf(catalog.id()), deleted.getId());
    Assertions.assertEquals("catalog", deleted.getType().value());
    Assertions.assertTrue(deleted.getLatestForName());
    Assertions.assertTrue(deleted.getRestorable());

    long metalakeId =
        EntityIdService.getEntityId(NameIdentifier.of(METALAKE), Entity.EntityType.METALAKE);
    Assertions.assertEquals(
        1,
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.CATALOG, metalakeId, catalog.name(), catalog.id(), null)
            .size());
    Assertions.assertTrue(
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.SCHEMA, catalog.id(), schema.name(), schema.id(), null)
            .isEmpty());

    CatalogEntity restored =
        manager.restoreDeletedCatalog(namespace, catalog.name(), catalog.id(), deleted.getEtag());
    Assertions.assertEquals(catalog.id(), restored.id());
    Assertions.assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    Assertions.assertTrue(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertTrue(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertTrue(backend.exists(fileset.nameIdentifier(), Entity.EntityType.FILESET));
    Assertions.assertTrue(backend.exists(function.nameIdentifier(), Entity.EntityType.FUNCTION));
    Assertions.assertTrue(backend.exists(topic.nameIdentifier(), Entity.EntityType.TOPIC));
    Assertions.assertTrue(backend.exists(view.nameIdentifier(), Entity.EntityType.VIEW));
    Assertions.assertTrue(backend.exists(model.nameIdentifier(), Entity.EntityType.MODEL));
    Assertions.assertEquals(
        modelVersion,
        ModelVersionMetaService.getInstance()
            .getModelVersionByIdentifier(
                NameIdentifier.of(METALAKE, CATALOG, schemaName, model.name(), "production")));
    Assertions.assertEquals(
        List.of(catalogTag),
        TagMetaService.getInstance()
            .listTagsForMetadataObject(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    Assertions.assertFalse(
        backend.exists(priorTombstone.nameIdentifier(), Entity.EntityType.TABLE));
    EntityDeletionPO unchangedPrior =
        EntityDeletionService.getInstance().get(priorDeletion.getDeletionId());
    Assertions.assertNotNull(unchangedPrior);
    Assertions.assertEquals(DeletionState.DELETED, unchangedPrior.getState());
    Assertions.assertEquals(
        catalog.id(),
        manager
            .restoreDeletedCatalog(namespace, catalog.name(), catalog.id(), deleted.getEtag())
            .id());
    Assertions.assertTrue(
        manager.listDeletedCatalogs(namespace, catalog.name(), catalog.id()).isEmpty());
  }

  @TestTemplate
  public void testEmptyCatalogDeleteAndRestoreUsesSameRootProtocol() throws Exception {
    createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, "empty_catalog");
    Assertions.assertTrue(
        CatalogMetaService.getInstance()
            .deleteCatalog(catalog.nameIdentifier(), false, RETENTION_MS));

    Namespace namespace = Namespace.of(METALAKE);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedCatalog(namespace, catalog.name(), catalog.id());
    Assertions.assertTrue(deleted.getRestorable());
    Assertions.assertEquals(
        catalog.id(),
        manager
            .restoreDeletedCatalog(namespace, catalog.name(), catalog.id(), deleted.getEtag())
            .id());
  }

  @TestTemplate
  public void testCatalogRestoreRejectsOlderGenerationAndNameOccupancy() throws Exception {
    long requestedDeletedAt = Instant.now().toEpochMilli();
    createAndInsertMakeLake(METALAKE);
    CatalogEntity first = createAndInsertCatalog(METALAKE, "repeated_catalog");
    CatalogMetaService.getInstance()
        .deleteCatalog(first.nameIdentifier(), false, requestedDeletedAt, RETENTION_MS);
    CatalogEntity second = createAndInsertCatalog(METALAKE, first.name());
    CatalogMetaService.getInstance()
        .deleteCatalog(second.nameIdentifier(), false, requestedDeletedAt, RETENTION_MS);

    Namespace namespace = Namespace.of(METALAKE);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO firstDeletion = manager.getDeletedCatalog(namespace, first.name(), first.id());
    DeletedEntityDTO secondDeletion =
        manager.getDeletedCatalog(namespace, second.name(), second.id());
    Assertions.assertFalse(firstDeletion.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", firstDeletion.getReason());
    Assertions.assertTrue(secondDeletion.getLatestForName());
    Assertions.assertTrue(secondDeletion.getDeletedAt() > firstDeletion.getDeletedAt());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () ->
            manager.restoreDeletedCatalog(
                namespace, first.name(), first.id(), firstDeletion.getEtag()));

    CatalogEntity replacement = createAndInsertCatalog(METALAKE, second.name());
    DeletedEntityDTO occupied = manager.getDeletedCatalog(namespace, second.name(), second.id());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () ->
            manager.restoreDeletedCatalog(
                namespace, second.name(), second.id(), secondDeletion.getEtag()));
    Assertions.assertTrue(backend.exists(replacement.nameIdentifier(), Entity.EntityType.CATALOG));
  }

  @TestTemplate
  public void testCatalogRestoreRefusesIncompleteAggregateWithoutPartialRevival() throws Exception {
    createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, "broken_catalog");
    SchemaEntity schema = createAndInsertSchema(METALAKE, catalog.name(), "broken_schema");
    TableEntity table =
        newTable(
            NamespaceUtil.ofTable(METALAKE, catalog.name(), schema.name()), "broken_catalog_table");
    TableMetaService.getInstance().insertTable(table, false);
    CatalogMetaService.getInstance()
        .deleteCatalog(catalog.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    Namespace namespace = Namespace.of(METALAKE);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedCatalog(namespace, catalog.name(), catalog.id());
    updateDeletionRecord(
        "DELETE FROM table_version_info WHERE table_id = ? AND deletion_id = ?",
        table.id(),
        deleted.getDeletionId());

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            manager.restoreDeletedCatalog(
                namespace, catalog.name(), catalog.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    Assertions.assertFalse(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testCatalogRestoreRefusesRelationWhoseExternalSourceWasDeleted() throws Exception {
    createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, "orphan_relation_catalog");
    TagEntity tag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("orphan_relation_tag")
            .withNamespace(NamespaceUtil.ofTag(METALAKE))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            catalog.nameIdentifier(),
            Entity.EntityType.CATALOG,
            new NameIdentifier[] {tag.nameIdentifier()},
            new NameIdentifier[0]);
    CatalogMetaService.getInstance()
        .deleteCatalog(catalog.nameIdentifier(), false, Instant.now().toEpochMilli(), RETENTION_MS);

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    Namespace namespace = Namespace.of(METALAKE);
    DeletedEntityDTO deleted = manager.getDeletedCatalog(namespace, catalog.name(), catalog.id());
    Assertions.assertTrue(TagMetaService.getInstance().deleteTag(tag.nameIdentifier()));

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            manager.restoreDeletedCatalog(
                namespace, catalog.name(), catalog.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testExpiredCatalogGenerationIsPurgedAsOneAggregate() throws Exception {
    long deletedAt = Instant.now().toEpochMilli();
    createAndInsertMakeLake(METALAKE);
    CatalogEntity catalog = createAndInsertCatalog(METALAKE, "purged_catalog");
    SchemaEntity schema = createAndInsertSchema(METALAKE, catalog.name(), "purged_schema");
    TableEntity table =
        newTable(
            NamespaceUtil.ofTable(METALAKE, catalog.name(), schema.name()), "purged_catalog_table");
    TableMetaService.getInstance().insertTable(table, false);
    CatalogMetaService.getInstance().deleteCatalog(catalog.nameIdentifier(), true, deletedAt, 0L);
    DeletedEntityDTO deletion =
        new RecoverableDeletionManager(RETENTION_MS)
            .getDeletedCatalog(Namespace.of(METALAKE), catalog.name(), catalog.id());

    Assertions.assertEquals(
        1, CatalogMetaService.getInstance().purgeExpiredCatalogDeletions(deletedAt + 1L, 100));
    Assertions.assertFalse(legacyRecordExistsInDB(catalog.id(), Entity.EntityType.CATALOG));
    Assertions.assertFalse(legacyRecordExistsInDB(schema.id(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(legacyRecordExistsInDB(table.id(), Entity.EntityType.TABLE));
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletion.getDeletionId());
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(DeletionState.PURGED, receipt.getState());
  }

  @TestTemplate
  public void testSchemaCascadeListsOnlyRootAndRestoresExactMetadataTree() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    String parentName = "tree_a";
    String rootName = parentName + ":tree_b";
    String childName = rootName + ":tree_c";
    SchemaEntity child = createAndInsertSchema(METALAKE, CATALOG, childName);
    SchemaEntity parent =
        SchemaMetaService.getInstance()
            .getSchemaByIdentifier(NameIdentifier.of(METALAKE, CATALOG, parentName));
    SchemaEntity root =
        SchemaMetaService.getInstance()
            .getSchemaByIdentifier(NameIdentifier.of(METALAKE, CATALOG, rootName));

    Namespace childTableNamespace = NamespaceUtil.ofTable(METALAKE, CATALOG, childName);
    TableEntity liveTable = newTable(childTableNamespace, "tree_table");
    TableMetaService.getInstance().insertTable(liveTable, false);
    FilesetEntity fileset =
        createFilesetEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(METALAKE, CATALOG, childName),
            "tree_fileset",
            AUDIT_INFO);
    FilesetMetaService.getInstance().insertFileset(fileset, false);
    FunctionEntity function =
        createFunctionEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFunction(METALAKE, CATALOG, rootName),
            "tree_function",
            AUDIT_INFO);
    FunctionMetaService.getInstance().insertFunction(function, false);
    ViewEntity view =
        createViewEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofView(METALAKE, CATALOG, childName),
            "tree_view");
    ViewMetaService.getInstance().insertView(view, false);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofModel(METALAKE, CATALOG, childName),
            "tree_model",
            "tree model",
            0,
            Map.of("kind", "test"),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);
    ModelVersionEntity modelVersion =
        ModelVersionEntity.builder()
            .withModelIdentifier(model.nameIdentifier())
            .withVersion(0)
            .withUris(Map.of(ModelVersion.URI_NAME_UNKNOWN, "/models/tree_model"))
            .withAliases(List.of("production"))
            .withComment("recoverable model version")
            .withProperties(Map.of("stage", "production"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    ModelVersionMetaService.getInstance().insertModelVersion(modelVersion);
    TagEntity tag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("tree_tag")
            .withNamespace(NamespaceUtil.ofTag(METALAKE))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            liveTable.nameIdentifier(),
            Entity.EntityType.TABLE,
            new NameIdentifier[] {tag.nameIdentifier()},
            new NameIdentifier[0]);
    TableEntity priorTombstone = newTable(childTableNamespace, "prior_table");
    TableMetaService.getInstance().insertTable(priorTombstone, false);
    EntityDeletionPO priorDeletion =
        deleteAndGetDeletionRecord(priorTombstone, Instant.now().toEpochMilli(), RETENTION_MS);
    TopicEntity rootTopic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofTopic(METALAKE, CATALOG, rootName),
            "tree_topic",
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(rootTopic, false);

    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(
                NameIdentifier.of(METALAKE, CATALOG, rootName),
                true,
                Instant.now().toEpochMilli(),
                RETENTION_MS));

    Namespace schemaNamespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    Assertions.assertTrue(manager.listDeletedSchemas(schemaNamespace, null, null, null).isEmpty());
    List<DeletedEntityDTO> deletedRoots =
        manager.listDeletedSchemas(schemaNamespace, parentName, null, null);
    Assertions.assertEquals(1, deletedRoots.size());
    DeletedEntityDTO rootDeletion = deletedRoots.get(0);
    Assertions.assertEquals(String.valueOf(root.id()), rootDeletion.getId());
    Assertions.assertEquals(rootName, rootDeletion.getName());
    Assertions.assertEquals("schema", rootDeletion.getType().value());
    Assertions.assertTrue(rootDeletion.getLatestForName());
    Assertions.assertTrue(rootDeletion.getRestorable());
    Assertions.assertTrue(
        manager.listDeletedSchemas(schemaNamespace, parentName, childName, child.id()).isEmpty());

    List<EntityDeletionPO> rootReceipts =
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.SCHEMA, parent.id(), rootName, root.id(), null);
    Assertions.assertEquals(1, rootReceipts.size());
    Assertions.assertTrue(
        EntityDeletionService.getInstance()
            .list(Entity.EntityType.SCHEMA, root.id(), childName, child.id(), null)
            .isEmpty());

    SchemaEntity restored =
        manager.restoreDeletedSchema(schemaNamespace, rootName, root.id(), rootDeletion.getEtag());
    Assertions.assertEquals(root.id(), restored.id());
    Assertions.assertTrue(backend.exists(root.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertTrue(backend.exists(child.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertTrue(backend.exists(liveTable.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertTrue(backend.exists(fileset.nameIdentifier(), Entity.EntityType.FILESET));
    Assertions.assertTrue(backend.exists(function.nameIdentifier(), Entity.EntityType.FUNCTION));
    Assertions.assertTrue(backend.exists(view.nameIdentifier(), Entity.EntityType.VIEW));
    Assertions.assertTrue(backend.exists(model.nameIdentifier(), Entity.EntityType.MODEL));
    Assertions.assertEquals(
        modelVersion,
        ModelVersionMetaService.getInstance()
            .getModelVersionByIdentifier(
                NameIdentifier.of(METALAKE, CATALOG, childName, model.name(), "production")));
    Assertions.assertEquals(
        List.of(tag),
        TagMetaService.getInstance()
            .listTagsForMetadataObject(liveTable.nameIdentifier(), Entity.EntityType.TABLE));
    Assertions.assertTrue(backend.exists(rootTopic.nameIdentifier(), Entity.EntityType.TOPIC));
    Assertions.assertFalse(
        backend.exists(priorTombstone.nameIdentifier(), Entity.EntityType.TABLE));
    EntityDeletionPO unchangedPrior =
        EntityDeletionService.getInstance().get(priorDeletion.getDeletionId());
    Assertions.assertNotNull(unchangedPrior);
    Assertions.assertEquals(priorDeletion.getDeletedAt(), unchangedPrior.getDeletedAt());
    Assertions.assertEquals(DeletionState.DELETED, unchangedPrior.getState());
    Assertions.assertEquals(
        root.id(),
        manager
            .restoreDeletedSchema(schemaNamespace, rootName, root.id(), rootDeletion.getEtag())
            .id());
    Assertions.assertTrue(
        manager.listDeletedSchemas(schemaNamespace, parentName, null, null).isEmpty());
  }

  @TestTemplate
  public void testEmptySchemaDeleteAndRestoreUsesSameRootProtocol() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    SchemaEntity schema = createAndInsertSchema(METALAKE, CATALOG, "empty_schema");
    Assertions.assertTrue(
        SchemaMetaService.getInstance().deleteSchema(schema.nameIdentifier(), false, RETENTION_MS));

    Namespace namespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedSchema(namespace, schema.name(), schema.id());
    Assertions.assertTrue(deleted.getRestorable());
    Assertions.assertEquals(
        schema.id(),
        manager
            .restoreDeletedSchema(namespace, schema.name(), schema.id(), deleted.getEtag())
            .id());
    Assertions.assertTrue(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  public void testSchemaRestoreRejectsOlderGenerationAndNameOccupancy() throws Exception {
    long requestedDeletedAt = Instant.now().toEpochMilli();
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    SchemaEntity first = createAndInsertSchema(METALAKE, CATALOG, "repeated_schema");
    SchemaMetaService.getInstance()
        .deleteSchema(first.nameIdentifier(), false, requestedDeletedAt, RETENTION_MS);
    SchemaEntity second = createAndInsertSchema(METALAKE, CATALOG, first.name());
    SchemaMetaService.getInstance()
        .deleteSchema(second.nameIdentifier(), false, requestedDeletedAt, RETENTION_MS);

    Namespace namespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO firstDeletion = manager.getDeletedSchema(namespace, first.name(), first.id());
    DeletedEntityDTO secondDeletion =
        manager.getDeletedSchema(namespace, second.name(), second.id());
    Assertions.assertFalse(firstDeletion.getLatestForName());
    Assertions.assertEquals("NOT_LATEST_TOMBSTONE", firstDeletion.getReason());
    Assertions.assertTrue(secondDeletion.getLatestForName());
    Assertions.assertTrue(secondDeletion.getDeletedAt() > firstDeletion.getDeletedAt());
    assertRecoveryConflict(
        RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
        () ->
            manager.restoreDeletedSchema(
                namespace, first.name(), first.id(), firstDeletion.getEtag()));

    SchemaEntity replacement = createAndInsertSchema(METALAKE, CATALOG, second.name());
    DeletedEntityDTO occupied = manager.getDeletedSchema(namespace, second.name(), second.id());
    Assertions.assertEquals("NAME_OCCUPIED", occupied.getReason());
    assertRecoveryConflict(
        RecoveryConflictReason.NAME_OCCUPIED,
        () ->
            manager.restoreDeletedSchema(
                namespace, second.name(), second.id(), secondDeletion.getEtag()));
    Assertions.assertTrue(backend.exists(replacement.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  public void testSchemaRestoreRefusesIncompleteAggregateWithoutPartialRevival() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    SchemaEntity schema = createAndInsertSchema(METALAKE, CATALOG, "broken_tree");
    TableEntity table =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schema.name()), "broken_table");
    TableMetaService.getInstance().insertTable(table, false);
    SchemaMetaService.getInstance()
        .deleteSchema(schema.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    Namespace namespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedSchema(namespace, schema.name(), schema.id());
    updateDeletionRecord(
        "DELETE FROM table_version_info WHERE table_id = ? AND deletion_id = ?",
        table.id(),
        deleted.getDeletionId());

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            manager.restoreDeletedSchema(namespace, schema.name(), schema.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deleted.getDeletionId());
    Assertions.assertNotNull(receipt);
    // Restore claims the receipt before locking and validating aggregate rows so that it shares
    // GC's lock order. The failed validation must still roll the claim back atomically.
    Assertions.assertEquals(DeletionState.DELETED, receipt.getState());
    Assertions.assertEquals(0L, receipt.getRevision());
  }

  @TestTemplate
  public void testSchemaRestoreRefusesRelationWhoseExternalSourceWasDeleted() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    SchemaEntity schema = createAndInsertSchema(METALAKE, CATALOG, "orphan_schema");
    TableEntity table =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, schema.name()), "orphan_schema_table");
    TableMetaService.getInstance().insertTable(table, false);
    TagEntity tag =
        TagEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("orphan_schema_tag")
            .withNamespace(NamespaceUtil.ofTag(METALAKE))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            table.nameIdentifier(),
            Entity.EntityType.TABLE,
            new NameIdentifier[] {tag.nameIdentifier()},
            new NameIdentifier[0]);
    SchemaMetaService.getInstance()
        .deleteSchema(schema.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    Namespace namespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedSchema(namespace, schema.name(), schema.id());
    Assertions.assertTrue(TagMetaService.getInstance().deleteTag(tag.nameIdentifier()));

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            manager.restoreDeletedSchema(namespace, schema.name(), schema.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(backend.exists(table.nameIdentifier(), Entity.EntityType.TABLE));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  @TestTemplate
  public void testSchemaRestoreRefusesTreeWithMissingIntermediateSchema() throws Exception {
    createAndInsertMakeLake(METALAKE);
    createAndInsertCatalog(METALAKE, CATALOG);
    String rootName = "broken_root";
    String parentName = rootName + ":missing_parent";
    String childName = parentName + ":child";
    SchemaEntity child = createAndInsertSchema(METALAKE, CATALOG, childName);
    SchemaEntity root =
        SchemaMetaService.getInstance()
            .getSchemaByIdentifier(NameIdentifier.of(METALAKE, CATALOG, rootName));
    SchemaEntity parent =
        SchemaMetaService.getInstance()
            .getSchemaByIdentifier(NameIdentifier.of(METALAKE, CATALOG, parentName));
    SchemaMetaService.getInstance()
        .deleteSchema(root.nameIdentifier(), true, Instant.now().toEpochMilli(), RETENTION_MS);

    Namespace namespace = NamespaceUtil.ofSchema(METALAKE, CATALOG);
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedSchema(namespace, root.name(), root.id());
    updateDeletionRecord(
        "DELETE FROM schema_meta WHERE schema_id = ? AND deletion_id = ?",
        parent.id(),
        deleted.getDeletionId());

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () -> manager.restoreDeletedSchema(namespace, root.name(), root.id(), deleted.getEtag()));
    Assertions.assertFalse(backend.exists(root.nameIdentifier(), Entity.EntityType.SCHEMA));
    Assertions.assertFalse(backend.exists(child.nameIdentifier(), Entity.EntityType.SCHEMA));
    assertDeletionReceiptUnchanged(deleted.getDeletionId());
  }

  private static void assertDeletionReceiptUnchanged(String deletionId) {
    EntityDeletionPO receipt = EntityDeletionService.getInstance().get(deletionId);
    Assertions.assertNotNull(receipt);
    Assertions.assertEquals(DeletionState.DELETED, receipt.getState());
    Assertions.assertEquals(0L, receipt.getRevision());
  }

  private static TableEntity restoreTableAfterSignal(
      TableEntity table,
      EntityDeletionPO deletion,
      String etag,
      long effectiveExpiresAt,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent table restores did not start together");
    }
    return TableMetaService.getInstance()
        .restoreTable(
            table.nameIdentifier(),
            deletion,
            Instant.now().toEpochMilli(),
            etag,
            effectiveExpiresAt);
  }

  private void assertSharedTagTableRestoreOrder(
      String suffix, boolean deleteTagFirst, boolean restoreTagFirst) throws Exception {
    TableEntity table =
        newTable(NamespaceUtil.ofTable(METALAKE, CATALOG, SCHEMA), "table_" + suffix);
    TableMetaService.getInstance().insertTable(table, false);
    TagEntity tag = newTag("tag_" + suffix);
    TagMetaService.getInstance().insertTag(tag, false);
    associateTag(table, tag, true);

    if (deleteTagFirst) {
      Assertions.assertTrue(
          TagMetaService.getInstance().deleteTag(tag.nameIdentifier(), RETENTION_MS));
      Assertions.assertTrue(
          TableMetaService.getInstance().deleteTable(table.nameIdentifier(), RETENTION_MS));
    } else {
      Assertions.assertTrue(
          TableMetaService.getInstance().deleteTable(table.nameIdentifier(), RETENTION_MS));
      Assertions.assertTrue(
          TagMetaService.getInstance().deleteTag(tag.nameIdentifier(), RETENTION_MS));
    }

    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deletedTag =
        manager.getDeletedTag(NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id());
    DeletedEntityDTO deletedTable =
        manager.getDeletedTable(table.namespace(), table.name(), table.id());
    EntityDeletionPO tagReceipt =
        EntityDeletionService.getInstance().get(deletedTag.getDeletionId());
    Assertions.assertEquals(deleteTagFirst ? 2L : 1L, tagReceipt.getAffectedRowCount());

    if (restoreTagFirst) {
      manager.restoreDeletedTag(
          NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id(), deletedTag.getEtag());
      Assertions.assertTrue(
          TagMetaService.getInstance()
              .listAssociatedMetadataObjectsForTag(tag.nameIdentifier())
              .isEmpty());
      manager.restoreDeletedTable(
          table.namespace(), table.name(), table.id(), deletedTable.getEtag());
    } else {
      manager.restoreDeletedTable(
          table.namespace(), table.name(), table.id(), deletedTable.getEtag());
      Assertions.assertTrue(
          TagMetaService.getInstance()
              .listTagsForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE)
              .isEmpty());
      manager.restoreDeletedTag(
          NamespaceUtil.ofTag(METALAKE), tag.name(), tag.id(), deletedTag.getEtag());
    }

    Assertions.assertEquals(
        List.of(tag),
        TagMetaService.getInstance()
            .listTagsForMetadataObject(table.nameIdentifier(), Entity.EntityType.TABLE));
  }

  private static void associateTag(TableEntity table, TagEntity tag, boolean add) throws Exception {
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            table.nameIdentifier(),
            Entity.EntityType.TABLE,
            add ? new NameIdentifier[] {tag.nameIdentifier()} : new NameIdentifier[0],
            add ? new NameIdentifier[0] : new NameIdentifier[] {tag.nameIdentifier()});
  }

  private static int countTagRelations(long tagId, String deletionId, boolean recorded)
      throws Exception {
    String predicate = recorded ? "deletion_id = ?" : "deleted_at > 0 AND deletion_id IS NULL";
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM tag_relation_meta WHERE tag_id = ? AND " + predicate)) {
      statement.setLong(1, tagId);
      if (recorded) {
        statement.setString(2, deletionId);
      }
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  private static int countAllTagRelations(long tagId) throws Exception {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM tag_relation_meta WHERE tag_id = ?")) {
      statement.setLong(1, tagId);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  private static <E extends Entity & HasIdentifier> EntityDeletionPO identityDeletion(
      BaseMetalake metalake, E entity) {
    return EntityDeletionService.getInstance()
        .list(entity.type(), metalake.id(), entity.name(), entity.id(), null)
        .get(0);
  }

  private EntityDeletionPO deleteAndGetDeletionRecord(
      TableEntity table, long deletedAt, long retentionMs) {
    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(table.nameIdentifier(), deletedAt, retentionMs));
    return EntityDeletionService.getInstance()
        .list(
            Entity.EntityType.TABLE,
            EntityIdService.getEntityId(
                NameIdentifier.of(table.namespace().levels()), Entity.EntityType.SCHEMA),
            table.name(),
            table.id(),
            null)
        .get(0);
  }

  private static Object[] policyVersionState(long policyId, long version) throws Exception {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT deleted_at, deletion_id FROM policy_version_info"
                    + " WHERE policy_id = ? AND version = ?")) {
      statement.setLong(1, policyId);
      statement.setLong(2, version);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return new Object[] {result.getLong("deleted_at"), result.getString("deletion_id")};
      }
    }
  }

  private static int countPolicyRelations(long policyId) throws Exception {
    return countPolicyRows(
        "SELECT COUNT(*) FROM policy_relation_meta WHERE policy_id = ?", policyId);
  }

  private static int countPolicyVersions(long policyId) throws Exception {
    return countPolicyRows(
        "SELECT COUNT(*) FROM policy_version_info WHERE policy_id = ?", policyId);
  }

  private static int countPolicyRows(String sql, long policyId) throws Exception {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, policyId);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  private static void updateDeletionRecord(String sql, Object... parameters) throws Exception {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private static void assertRecoveryConflict(
      RecoveryConflictReason expectedReason, Executable executable) {
    RecoveryConflictException exception =
        Assertions.assertThrows(RecoveryConflictException.class, executable);
    Assertions.assertEquals(expectedReason, exception.getReason());
  }

  private TableEntity newTable(Namespace namespace, String name) {
    return createTableEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
  }

  private TagEntity newTag(String name) {
    return TagEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(name)
        .withNamespace(NamespaceUtil.ofTag(METALAKE))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private FilesetEntity newFileset(Namespace namespace, String name) {
    return FilesetEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(name)
        .withNamespace(namespace)
        .withFilesetType(Fileset.Type.MANAGED)
        .withStorageLocations(Map.of(Fileset.LOCATION_NAME_UNKNOWN, "/warehouse/" + name))
        .withComment("managed metadata")
        .withProperties(Map.of("owner", "test"))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }
}
