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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.ModelVersionEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.StatisticEntity;
import org.apache.gravitino.meta.TableStatisticEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.stats.StatisticValues;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ModelPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestTemplate;

public class TestModelMetaService extends TestJDBCBackend {

  private static final String METALAKE_NAME = "metalake_for_model_meta_test";

  private static final String CATALOG_NAME = "catalog_for_model_meta_test";

  private static final String SCHEMA_NAME = "schema_for_model_meta_test";

  private static final Namespace MODEL_NS = Namespace.of(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME);

  @TestTemplate
  public void testMetaLifeCycleFromCreationToDeletion() throws IOException {
    BaseMetalake metalake = createAndInsertMakeLake(METALAKE_NAME);
    createAndInsertCatalog(METALAKE_NAME, CATALOG_NAME);
    createAndInsertSchema(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME);

    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofModel(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME),
            "model",
            "model comment",
            1,
            ImmutableMap.of("key", "value"),
            AUDIT_INFO);
    backend.insert(model, false);

    List<ModelEntity> models = backend.list(model.namespace(), Entity.EntityType.MODEL, true);
    assertTrue(models.contains(model));

    // meta data soft delete
    backend.delete(metalake.nameIdentifier(), Entity.EntityType.METALAKE, true);
    assertFalse(backend.exists(model.nameIdentifier(), Entity.EntityType.MODEL));

    // check legacy record after soft delete
    assertTrue(legacyRecordExistsInDB(model.id(), Entity.EntityType.MODEL));

    // meta data hard delete
    for (Entity.EntityType entityType : Entity.EntityType.values()) {
      backend.hardDeleteLegacyData(entityType, Instant.now().toEpochMilli() + 1000);
    }
    assertFalse(legacyRecordExistsInDB(model.id(), Entity.EntityType.MODEL));
  }

  @TestTemplate
  public void testInsertAndSelectModel() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1");

    ModelEntity modelEntity =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    ModelEntity registeredModelEntity =
        ModelMetaService.getInstance().getModelByIdentifier(modelEntity.nameIdentifier());
    Assertions.assertEquals(modelEntity, registeredModelEntity);

    // Test insert again without overwrite
    Assertions.assertThrows(
        EntityAlreadyExistsException.class,
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    // Test insert again with overwrite
    ModelEntity modelEntity2 =
        createModelEntity(
            modelEntity.id(),
            modelEntity.namespace(),
            "model2",
            null,
            modelEntity.latestVersion(),
            null,
            AUDIT_INFO);
    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity2, true));
    ModelEntity registeredModelEntity2 =
        ModelMetaService.getInstance().getModelByIdentifier(modelEntity2.nameIdentifier());
    Assertions.assertEquals(modelEntity2, registeredModelEntity2);

    // Test get an in-existent model
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelMetaService.getInstance()
                .getModelByIdentifier(NameIdentifier.of(MODEL_NS, "model3")));

    // Test get model by id
    ModelPO modelPO = ModelMetaService.getInstance().getModelPOById(modelEntity.id());
    Assertions.assertEquals(
        modelEntity2, POConverters.fromModelPO(modelPO, modelEntity.namespace()));

    // Test get in-existent model by id
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> ModelMetaService.getInstance().getModelPOById(111L));

    // Test get model id by name
    Long schemaId =
        EntityIdService.getEntityId(NameIdentifier.of(MODEL_NS.levels()), Entity.EntityType.SCHEMA);
    Long modelId =
        ModelMetaService.getInstance().getModelIdBySchemaIdAndModelName(schemaId, "model2");
    Assertions.assertEquals(modelEntity2.id(), modelId);

    // Test get in-existent model id by name
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> ModelMetaService.getInstance().getModelIdBySchemaIdAndModelName(schemaId, "model3"));
  }

  @TestTemplate
  public void testInsertAndListModels() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1");

    ModelEntity modelEntity1 =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);
    ModelEntity modelEntity2 =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model2",
            "model2 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity1, false));
    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity2, false));

    List<ModelEntity> modelEntities =
        ModelMetaService.getInstance().listModelsByNamespace(MODEL_NS);
    Assertions.assertEquals(2, modelEntities.size());
    Assertions.assertTrue(modelEntities.contains(modelEntity1));
    Assertions.assertTrue(modelEntities.contains(modelEntity2));

    // Test list models by in-existent namespace
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelMetaService.getInstance()
                .listModelsByNamespace(Namespace.of(METALAKE_NAME, CATALOG_NAME, "inexistent")));
  }

  @TestTemplate
  public void testInsertAndDeleteModel() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1");

    ModelEntity modelEntity =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    Assertions.assertTrue(ModelMetaService.getInstance().deleteModel(modelEntity.nameIdentifier()));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> ModelMetaService.getInstance().getModelByIdentifier(modelEntity.nameIdentifier()));

    // Delete again should return false
    Assertions.assertFalse(
        ModelMetaService.getInstance().deleteModel(modelEntity.nameIdentifier()));

    // Test delete in-existent model
    Assertions.assertFalse(
        ModelMetaService.getInstance().deleteModel(NameIdentifier.of(MODEL_NS, "inexistent")));

    // Test delete in-existent schema
    Assertions.assertFalse(
        ModelMetaService.getInstance()
            .deleteModel(NameIdentifier.of(METALAKE_NAME, CATALOG_NAME, "inexistent", "model1")));
  }

  @TestTemplate
  public void testRestoreExactModelDeletionGeneration() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "recoverable_model",
            "model comment",
            0,
            ImmutableMap.of("key", "value"),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);

    UserEntity owner =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofUserNamespace(METALAKE_NAME),
            "model-owner",
            AUDIT_INFO);
    backend.insert(owner, false);
    OwnerMetaService.getInstance()
        .setOwner(model.nameIdentifier(), model.type(), owner.nameIdentifier(), owner.type());

    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            SecurableObjects.ofCatalog(
                CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow())),
            SCHEMA_NAME,
            Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject modelObject =
        SecurableObjects.ofModel(
            schemaObject, model.name(), Lists.newArrayList(Privileges.UseModel.allow()));
    RoleEntity role =
        createRoleEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofRoleNamespace(METALAKE_NAME),
            "model-role",
            AUDIT_INFO,
            Lists.newArrayList(modelObject),
            ImmutableMap.of());
    RoleMetaService.getInstance().insertRole(role, false);

    TagEntity retainedTag = newTag("retained-tag");
    TagEntity independentlyRemovedTag = newTag("independently-removed-tag");
    TagMetaService.getInstance().insertTag(retainedTag, false);
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            model.nameIdentifier(),
            model.type(),
            new NameIdentifier[] {
              retainedTag.nameIdentifier(), independentlyRemovedTag.nameIdentifier()
            },
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            model.nameIdentifier(),
            model.type(),
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});

    StatisticEntity statistic =
        TableStatisticEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("model-statistic")
            .withValue(StatisticValues.longValue(1L))
            .withAuditInfo(AUDIT_INFO)
            .build();
    StatisticMetaService.getInstance()
        .batchInsertStatisticPOsOnDuplicateKeyUpdate(
            ImmutableList.of(statistic), model.nameIdentifier(), model.type());

    PolicyContent policyContent =
        PolicyContents.custom(
            ImmutableMap.of("rule", true), ImmutableSet.of(MetadataObject.Type.MODEL), null);
    PolicyEntity policy =
        PolicyEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("model-policy")
            .withNamespace(NamespaceUtil.ofPolicy(METALAKE_NAME))
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withContent(policyContent)
            .withAuditInfo(AUDIT_INFO)
            .build();
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyMetaService.getInstance()
        .associatePoliciesWithMetadataObject(
            model.nameIdentifier(),
            model.type(),
            new NameIdentifier[] {policy.nameIdentifier()},
            new NameIdentifier[0]);

    Assertions.assertEquals(
        1, countActiveModelRelations("owner_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("role_meta_securable_object", "type", model.id()));
    Assertions.assertEquals(1, countActiveTagRelation(model.id(), retainedTag.id()));
    Assertions.assertEquals(0, countActiveTagRelation(model.id(), independentlyRemovedTag.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("statistic_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("policy_relation_meta", "metadata_object_type", model.id()));

    ModelVersionEntity independentlyDeletedVersion =
        createModelVersionEntity(
            model.nameIdentifier(),
            0,
            ImmutableMap.of(ModelVersion.URI_NAME_UNKNOWN, "uri-0", "uri-0-copy", "uri-0-copy"),
            ImmutableList.of("old-alias"),
            "old version",
            ImmutableMap.of(),
            AUDIT_INFO);
    ModelVersionMetaService.getInstance().insertModelVersion(independentlyDeletedVersion);
    Assertions.assertTrue(
        ModelVersionMetaService.getInstance()
            .deleteModelVersion(
                NameIdentifierUtil.ofModelVersion(
                    METALAKE_NAME,
                    CATALOG_NAME,
                    SCHEMA_NAME,
                    model.name(),
                    Integer.toString(independentlyDeletedVersion.version()))));

    ModelVersionEntity liveVersion =
        createModelVersionEntity(
            model.nameIdentifier(),
            1,
            ImmutableMap.of(ModelVersion.URI_NAME_UNKNOWN, "uri-1", "uri-1-copy", "uri-1-copy"),
            ImmutableList.of("live-alias", "latest"),
            "live version",
            ImmutableMap.of("version", "one"),
            AUDIT_INFO);
    ModelVersionMetaService.getInstance().insertModelVersion(liveVersion);

    long deletedAt = Instant.now().toEpochMilli();
    Assertions.assertTrue(
        ModelMetaService.getInstance().deleteModel(model.nameIdentifier(), deletedAt, 60_000L));
    Assertions.assertEquals(
        0, countActiveModelRelations("owner_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        0, countActiveModelRelations("role_meta_securable_object", "type", model.id()));
    Assertions.assertEquals(0, countActiveTagRelation(model.id(), retainedTag.id()));
    Assertions.assertEquals(0, countActiveTagRelation(model.id(), independentlyRemovedTag.id()));
    Assertions.assertEquals(
        0, countActiveModelRelations("statistic_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        0, countActiveModelRelations("policy_relation_meta", "metadata_object_type", model.id()));
    long schemaId =
        EntityIdService.getEntityId(NameIdentifier.of(MODEL_NS.levels()), Entity.EntityType.SCHEMA);
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.MODEL, schemaId, model.name(), model.id(), DeletionState.DELETED)
            .get(0);

    ModelEntity restored =
        ModelMetaService.getInstance()
            .restoreModel(
                model.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "model-restore-etag",
                deletion.getExpiresAt());
    Assertions.assertEquals(model.id(), restored.id());
    Assertions.assertEquals(2, restored.latestVersion());
    Assertions.assertEquals(
        1, countActiveModelRelations("owner_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("role_meta_securable_object", "type", model.id()));
    Assertions.assertEquals(1, countActiveTagRelation(model.id(), retainedTag.id()));
    Assertions.assertEquals(0, countActiveTagRelation(model.id(), independentlyRemovedTag.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("statistic_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        1, countActiveModelRelations("policy_relation_meta", "metadata_object_type", model.id()));
    Assertions.assertEquals(
        liveVersion,
        ModelVersionMetaService.getInstance()
            .getModelVersionByIdentifier(
                NameIdentifierUtil.ofModelVersion(
                    METALAKE_NAME,
                    CATALOG_NAME,
                    SCHEMA_NAME,
                    model.name(),
                    Integer.toString(liveVersion.version()))));
    Assertions.assertEquals(
        liveVersion,
        ModelVersionMetaService.getInstance()
            .getModelVersionByIdentifier(
                NameIdentifierUtil.ofModelVersion(
                    METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, model.name(), "live-alias")));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelVersionMetaService.getInstance()
                .getModelVersionByIdentifier(
                    NameIdentifierUtil.ofModelVersion(
                        METALAKE_NAME,
                        CATALOG_NAME,
                        SCHEMA_NAME,
                        model.name(),
                        Integer.toString(independentlyDeletedVersion.version()))));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelVersionMetaService.getInstance()
                .getModelVersionByIdentifier(
                    NameIdentifierUtil.ofModelVersion(
                        METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, model.name(), "old-alias")));

    Assertions.assertEquals(
        model.id(),
        ModelMetaService.getInstance()
            .restoreModel(
                model.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "model-restore-etag",
                deletion.getExpiresAt())
            .id());
  }

  @TestTemplate
  public void testOverwriteCannotResurrectRecordedModelDeletion() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "recoverable_model",
            "model comment",
            0,
            ImmutableMap.of(),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);
    Assertions.assertTrue(ModelMetaService.getInstance().deleteModel(model.nameIdentifier()));

    Assertions.assertThrows(
        RecoveryConflictException.class,
        () -> ModelMetaService.getInstance().insertModel(model, true));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> ModelMetaService.getInstance().getModelByIdentifier(model.nameIdentifier()));
  }

  @TestTemplate
  public void testRecordedModelDeletionUsesExactPurge() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    ModelEntity model =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "purge_model",
            "model comment",
            0,
            ImmutableMap.of(),
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(model, false);
    long deletedAt = Instant.now().minusSeconds(60).toEpochMilli();
    Assertions.assertTrue(
        ModelMetaService.getInstance().deleteModel(model.nameIdentifier(), deletedAt, 0L));

    Assertions.assertEquals(
        0,
        ModelMetaService.getInstance()
            .deleteModelMetasByLegacyTimeline(Instant.now().toEpochMilli(), 100));
    Assertions.assertTrue(legacyRecordExistsInDB(model.id(), Entity.EntityType.MODEL));
    Assertions.assertEquals(
        1, backend.hardDeleteLegacyData(Entity.EntityType.MODEL, Instant.now().toEpochMilli()));
    Assertions.assertFalse(legacyRecordExistsInDB(model.id(), Entity.EntityType.MODEL));
  }

  @TestTemplate
  void testInsertAndRenameModel() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1");
    String newName = "new_model_name";

    ModelEntity modelEntity =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    ModelEntity updatedModel =
        ModelEntity.builder()
            .withId(modelEntity.id())
            .withName(newName)
            .withNamespace(modelEntity.namespace())
            .withLatestVersion(modelEntity.latestVersion())
            .withAuditInfo(modelEntity.auditInfo())
            .withComment(modelEntity.comment())
            .withProperties(modelEntity.properties())
            .build();

    Function<ModelEntity, ModelEntity> renameUpdater = oldModel -> updatedModel;
    ModelEntity alteredModel =
        ModelMetaService.getInstance().updateModel(modelEntity.nameIdentifier(), renameUpdater);

    Assertions.assertEquals(alteredModel, updatedModel);
    // Test update an in-existent model
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelMetaService.getInstance()
                .updateModel(NameIdentifier.of(MODEL_NS, "model3"), renameUpdater));
  }

  @TestTemplate
  void testInsertAndUpdateModelComment() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1");
    String newComment = "new_model_comment";

    ModelEntity modelEntity =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    ModelEntity updatedModel =
        ModelEntity.builder()
            .withId(modelEntity.id())
            .withName(modelEntity.name())
            .withNamespace(modelEntity.namespace())
            .withLatestVersion(modelEntity.latestVersion())
            .withAuditInfo(modelEntity.auditInfo())
            .withComment(newComment)
            .withProperties(modelEntity.properties())
            .build();

    Function<ModelEntity, ModelEntity> renameUpdater = oldModel -> updatedModel;
    ModelEntity alteredModel =
        ModelMetaService.getInstance().updateModel(modelEntity.nameIdentifier(), renameUpdater);

    Assertions.assertEquals(alteredModel, updatedModel);
    // Test update an in-existent model
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelMetaService.getInstance()
                .updateModel(NameIdentifier.of(MODEL_NS, "model3"), renameUpdater));

    // test update model comment from null
    ModelEntity modelEntity4 =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model4",
            "model4 comment",
            0,
            properties,
            AUDIT_INFO);
    ModelMetaService.getInstance().insertModel(modelEntity4, false);

    ModelMetaService.getInstance()
        .updateModel(
            modelEntity4.nameIdentifier(),
            entity -> {
              ModelEntity model = (ModelEntity) entity;
              return ModelEntity.builder()
                  .withId(model.id())
                  .withName(model.name())
                  .withNamespace(model.namespace())
                  .withComment("model comment updated")
                  .withLatestVersion(model.latestVersion())
                  .withProperties(model.properties())
                  .withAuditInfo(model.auditInfo())
                  .build();
            });
    ModelEntity updatedModel4 =
        ModelMetaService.getInstance().getModelByIdentifier(modelEntity4.nameIdentifier());
    Assertions.assertEquals("model comment updated", updatedModel4.comment());
  }

  @TestTemplate
  void testInsertAndUpdateModelProperties() throws IOException {
    createParentEntities(METALAKE_NAME, CATALOG_NAME, SCHEMA_NAME, AUDIT_INFO);
    Map<String, String> properties = ImmutableMap.of("k1", "v1", "k2", "v2");
    Map<String, String> newProps = ImmutableMap.of("k1", "v1", "k3", "v3");

    ModelEntity modelEntity =
        createModelEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            MODEL_NS,
            "model1",
            "model1 comment",
            0,
            properties,
            AUDIT_INFO);

    Assertions.assertDoesNotThrow(
        () -> ModelMetaService.getInstance().insertModel(modelEntity, false));

    ModelEntity updatedModel =
        ModelEntity.builder()
            .withId(modelEntity.id())
            .withName(modelEntity.name())
            .withNamespace(modelEntity.namespace())
            .withLatestVersion(modelEntity.latestVersion())
            .withAuditInfo(modelEntity.auditInfo())
            .withComment(modelEntity.comment())
            .withProperties(newProps)
            .build();

    Function<ModelEntity, ModelEntity> renameUpdater = oldModel -> updatedModel;
    ModelEntity alteredModel =
        ModelMetaService.getInstance().updateModel(modelEntity.nameIdentifier(), renameUpdater);

    Assertions.assertEquals(alteredModel, updatedModel);
    // Test update an in-existent model
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            ModelMetaService.getInstance()
                .updateModel(NameIdentifier.of(MODEL_NS, "model3"), renameUpdater));
  }

  private ModelVersionEntity createModelVersionEntity(
      NameIdentifier modelIdentifier,
      Integer version,
      Map<String, String> uris,
      List<String> aliases,
      String comment,
      Map<String, String> properties,
      AuditInfo auditInfo) {
    return ModelVersionEntity.builder()
        .withModelIdentifier(modelIdentifier)
        .withVersion(version)
        .withUris(uris)
        .withAliases(aliases)
        .withComment(comment)
        .withProperties(properties)
        .withAuditInfo(auditInfo)
        .build();
  }

  private TagEntity newTag(String name) {
    return TagEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(name)
        .withNamespace(NamespaceUtil.ofTag(METALAKE_NAME))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private int countActiveModelRelations(String table, String typeColumn, long modelId) {
    String sql =
        String.format(
            "SELECT count(*) FROM %s WHERE metadata_object_id = ? AND %s = 'MODEL'"
                + " AND deleted_at = 0",
            table, typeColumn);
    return countRows(sql, modelId);
  }

  private int countActiveTagRelation(long modelId, long tagId) {
    return countRows(
        "SELECT count(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
            + " AND metadata_object_type = 'MODEL' AND tag_id = ? AND deleted_at = 0",
        modelId,
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
      throw new RuntimeException("Failed to count model relations", e);
    }
  }
}
