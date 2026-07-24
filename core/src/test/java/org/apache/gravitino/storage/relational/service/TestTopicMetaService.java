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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.StatisticEntity;
import org.apache.gravitino.meta.TableStatisticEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
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

public class TestTopicMetaService extends TestJDBCBackend {
  private final String metalakeName = "metalake_for_topic_test";
  private final String catalogName = "catalog_for_topic_test";
  private final String schemaName = "schema_for_topic_test";

  @BeforeEach
  public void prepare() throws IOException {
    createAndInsertMakeLake(metalakeName);
    createAndInsertCatalog(metalakeName, catalogName);
    createAndInsertSchema(metalakeName, catalogName, schemaName);
  }

  @TestTemplate
  public void testInsertAlreadyExistsException() throws IOException {
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "topic",
            AUDIT_INFO);
    TopicEntity topicCopy =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "topic",
            AUDIT_INFO);
    backend.insert(topic, false);
    assertThrows(EntityAlreadyExistsException.class, () -> backend.insert(topicCopy, false));
  }

  @TestTemplate
  public void testMetaLifeCycleFromCreationToDeletion() throws IOException {
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "topic",
            AUDIT_INFO);
    backend.insert(topic, false);

    List<TopicEntity> topics = backend.list(topic.namespace(), Entity.EntityType.TOPIC, true);
    assertTrue(topics.contains(topic));

    // meta data soft delete
    backend.delete(NameIdentifierUtil.ofMetalake(metalakeName), Entity.EntityType.METALAKE, true);
    assertFalse(backend.exists(topic.nameIdentifier(), Entity.EntityType.TOPIC));

    // check legacy record after soft delete
    assertTrue(legacyRecordExistsInDB(topic.id(), Entity.EntityType.TOPIC));

    // meta data hard delete
    for (Entity.EntityType entityType : Entity.EntityType.values()) {
      backend.hardDeleteLegacyData(entityType, Instant.now().toEpochMilli() + 1000);
    }
    assertFalse(legacyRecordExistsInDB(topic.id(), Entity.EntityType.TOPIC));
  }

  @TestTemplate
  public void testUpdateTopic() throws IOException {
    TopicEntity topicWithNullComment =
        TopicEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName("test_null")
            .withNamespace(NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName))
            .withComment(null)
            .withProperties(null)
            .withAuditInfo(AUDIT_INFO)
            .build();
    backend.insert(topicWithNullComment, false);
    backend.update(
        topicWithNullComment.nameIdentifier(),
        Entity.EntityType.TOPIC,
        e ->
            TopicEntity.builder()
                .withId(topicWithNullComment.id())
                .withName(topicWithNullComment.name())
                .withNamespace(topicWithNullComment.namespace())
                .withComment("now has comment")
                .withProperties(topicWithNullComment.properties())
                .withAuditInfo(AUDIT_INFO)
                .build());
    TopicEntity updatedTopic =
        backend.get(topicWithNullComment.nameIdentifier(), Entity.EntityType.TOPIC);
    Assertions.assertEquals("now has comment", updatedTopic.comment());

    // test topic already exists exception
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "topic",
            AUDIT_INFO);
    TopicEntity topicCopy =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName),
            "topic1",
            AUDIT_INFO);
    backend.insert(topic, false);
    backend.insert(topicCopy, false);
    assertThrows(
        EntityAlreadyExistsException.class,
        () ->
            backend.update(
                topicCopy.nameIdentifier(),
                Entity.EntityType.TOPIC,
                e ->
                    createTopicEntity(topicCopy.id(), topicCopy.namespace(), "topic", AUDIT_INFO)));
  }

  @TestTemplate
  public void testRestoreExactTopicAggregatePreservesIndependentTombstonesAndVersions()
      throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        TopicEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(GravitinoITUtils.genRandomName("recoverable_topic"))
            .withNamespace(namespace)
            .withComment("metadata shell")
            .withProperties(ImmutableMap.of("retained", "true"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    TopicMetaService.getInstance().insertTopic(topic, false);

    UserEntity owner =
        createUserEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            AuthorizationUtils.ofUserNamespace(metalakeName),
            GravitinoITUtils.genRandomName("topic_owner"),
            AUDIT_INFO);
    backend.insert(owner, false);
    OwnerMetaService.getInstance()
        .setOwner(
            topic.nameIdentifier(), Entity.EntityType.TOPIC, owner.nameIdentifier(), owner.type());

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
            GravitinoITUtils.genRandomName("topic_role"),
            AUDIT_INFO,
            Lists.newArrayList(
                SecurableObjects.ofTopic(
                    schemaObject,
                    topic.name(),
                    Lists.newArrayList(Privileges.ConsumeTopic.allow()))),
            ImmutableMap.of());
    RoleMetaService.getInstance().insertRole(role, false);

    TagEntity retainedTag = newTag("retained_topic_tag");
    TagEntity independentlyRemovedTag = newTag("removed_topic_tag");
    TagMetaService.getInstance().insertTag(retainedTag, false);
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[] {
              retainedTag.nameIdentifier(), independentlyRemovedTag.nameIdentifier()
            },
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});

    StatisticEntity statistic =
        TableStatisticEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(GravitinoITUtils.genRandomName("topic_statistic"))
            .withValue(StatisticValues.longValue(1L))
            .withAuditInfo(AUDIT_INFO)
            .build();
    StatisticMetaService.getInstance()
        .batchInsertStatisticPOsOnDuplicateKeyUpdate(
            ImmutableList.of(statistic), topic.nameIdentifier(), Entity.EntityType.TOPIC);

    PolicyContent policyContent =
        PolicyContents.custom(
            ImmutableMap.of("rule", true), ImmutableSet.of(MetadataObject.Type.TOPIC), null);
    PolicyEntity policy =
        PolicyEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(GravitinoITUtils.genRandomName("topic_policy"))
            .withNamespace(NamespaceUtil.ofPolicy(metalakeName))
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withContent(policyContent)
            .withAuditInfo(AUDIT_INFO)
            .build();
    PolicyMetaService.getInstance().insertPolicy(policy, false);
    PolicyMetaService.getInstance()
        .associatePoliciesWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[] {policy.nameIdentifier()},
            new NameIdentifier[0]);

    updateRows(
        "UPDATE topic_meta SET current_version = ?, last_version = ? WHERE topic_id = ?",
        3,
        7,
        topic.id());
    long deletedAt = Instant.now().toEpochMilli();
    Assertions.assertTrue(
        TopicMetaService.getInstance()
            .deleteTopic(
                topic.nameIdentifier(), deletedAt, Configs.DEFAULT_STORE_DELETE_AFTER_TIME));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM topic_meta WHERE topic_id = ? AND current_version = 3"
                + " AND last_version = 7 AND deleted_at = ? AND deletion_id IS NOT NULL",
            topic.id(),
            deletedAt));

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .list(
                Entity.EntityType.TOPIC, schemaId, topic.name(), topic.id(), DeletionState.DELETED)
            .get(0);
    TopicEntity restored =
        TopicMetaService.getInstance()
            .restoreTopic(
                topic.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "topic-restore-etag",
                deletion.getExpiresAt());
    Assertions.assertEquals(topic.id(), restored.id());
    Assertions.assertEquals(topic.name(), restored.name());
    Assertions.assertEquals(topic.comment(), restored.comment());
    Assertions.assertEquals(topic.properties(), restored.properties());
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM topic_meta WHERE topic_id = ? AND current_version = 3"
                + " AND last_version = 7 AND deleted_at = 0 AND deletion_id IS NULL",
            topic.id()));
    Assertions.assertEquals(
        1, countActiveRelation("owner_meta", "metadata_object_type", topic.id()));
    Assertions.assertEquals(
        1, countActiveRelation("role_meta_securable_object", "type", topic.id()));
    Assertions.assertEquals(1, countActiveTagRelation(topic.id(), retainedTag.id()));
    Assertions.assertEquals(0, countActiveTagRelation(topic.id(), independentlyRemovedTag.id()));
    Assertions.assertEquals(
        1, countActiveRelation("statistic_meta", "metadata_object_type", topic.id()));
    Assertions.assertEquals(
        1, countActiveRelation("policy_relation_meta", "metadata_object_type", topic.id()));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
                + " AND tag_id = ? AND deleted_at > 0 AND deletion_id IS NULL",
            topic.id(),
            independentlyRemovedTag.id()));
    Assertions.assertEquals(
        topic.id(),
        TopicMetaService.getInstance()
            .restoreTopic(
                topic.nameIdentifier(),
                deletion,
                Instant.now().toEpochMilli(),
                "topic-restore-etag",
                deletion.getExpiresAt())
            .id());
  }

  @TestTemplate
  public void testTopicNameCollisionLatestGenerationAndOverwriteGuard() throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    String name = GravitinoITUtils.genRandomName("repeated_topic");
    TopicEntity original =
        createTopicEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(original, false);
    Assertions.assertTrue(TopicMetaService.getInstance().deleteTopic(original.nameIdentifier()));
    RecoveryConflictException overwriteFailure =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () -> TopicMetaService.getInstance().insertTopic(original, true));
    Assertions.assertEquals(RecoveryConflictReason.ENTITY_ID_REUSED, overwriteFailure.getReason());
    EntityDeletionPO originalDeletion = deletionFor(original);

    TopicEntity replacement =
        createTopicEntity(RandomIdGenerator.INSTANCE.nextId(), namespace, name, AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(replacement, false);
    RecoveryConflictException occupied =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                TopicMetaService.getInstance()
                    .restoreTopic(
                        original.nameIdentifier(),
                        originalDeletion,
                        Instant.now().toEpochMilli(),
                        "occupied-topic-etag",
                        originalDeletion.getExpiresAt()));
    Assertions.assertEquals(RecoveryConflictReason.NAME_OCCUPIED, occupied.getReason());

    Assertions.assertTrue(TopicMetaService.getInstance().deleteTopic(replacement.nameIdentifier()));
    RecoveryConflictException notLatest =
        Assertions.assertThrows(
            RecoveryConflictException.class,
            () ->
                TopicMetaService.getInstance()
                    .restoreTopic(
                        original.nameIdentifier(),
                        originalDeletion,
                        Instant.now().toEpochMilli(),
                        "old-topic-etag",
                        originalDeletion.getExpiresAt()));
    Assertions.assertEquals(RecoveryConflictReason.NOT_LATEST_TOMBSTONE, notLatest.getReason());
  }

  @TestTemplate
  public void testRecordedTopicDeletionUsesExactPurge() throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("purged_topic"),
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);
    TagEntity independentlyRemovedTag = newTag("purged_topic_removed_tag");
    TagMetaService.getInstance().insertTag(independentlyRemovedTag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()},
            new NameIdentifier[0]);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[0],
            new NameIdentifier[] {independentlyRemovedTag.nameIdentifier()});
    long deletedAt = Instant.now().minusSeconds(60).toEpochMilli();
    Assertions.assertTrue(
        TopicMetaService.getInstance().deleteTopic(topic.nameIdentifier(), deletedAt, 0L));

    Assertions.assertEquals(
        0,
        TopicMetaService.getInstance()
            .deleteTopicMetasByLegacyTimeline(Instant.now().toEpochMilli(), 100));
    Assertions.assertTrue(legacyRecordExistsInDB(topic.id(), Entity.EntityType.TOPIC));
    Assertions.assertEquals(
        1, backend.hardDeleteLegacyData(Entity.EntityType.TOPIC, Instant.now().toEpochMilli()));
    Assertions.assertFalse(legacyRecordExistsInDB(topic.id(), Entity.EntityType.TOPIC));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
                + " AND tag_id = ? AND deleted_at > 0 AND deletion_id IS NULL",
            topic.id(),
            independentlyRemovedTag.id()));
  }

  @TestTemplate
  public void testConcurrentUpdatesDeriveFromLockedTopicState() throws Exception {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("serialized_topic"),
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);

    CountDownLatch firstUpdaterEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstUpdater = new CountDownLatch(1);
    CountDownLatch secondTaskStarted = new CountDownLatch(1);
    CountDownLatch secondUpdaterEntered = new CountDownLatch(1);
    AtomicReference<String> secondObservedComment = new AtomicReference<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Function<TopicEntity, TopicEntity> firstUpdater =
          oldTopic -> {
            firstUpdaterEntered.countDown();
            await(releaseFirstUpdater);
            return copyWithComment(oldTopic, "first");
          };
      Future<TopicEntity> first =
          executor.submit(
              () ->
                  TopicMetaService.getInstance().updateTopic(topic.nameIdentifier(), firstUpdater));
      Assertions.assertTrue(firstUpdaterEntered.await(10, TimeUnit.SECONDS));

      Function<TopicEntity, TopicEntity> secondUpdater =
          oldTopic -> {
            secondUpdaterEntered.countDown();
            secondObservedComment.set(oldTopic.comment());
            return copyWithComment(oldTopic, oldTopic.comment() + "-second");
          };
      Future<TopicEntity> second =
          executor.submit(
              () -> {
                secondTaskStarted.countDown();
                return TopicMetaService.getInstance()
                    .updateTopic(topic.nameIdentifier(), secondUpdater);
              });
      Assertions.assertTrue(secondTaskStarted.await(10, TimeUnit.SECONDS));
      Assertions.assertFalse(
          secondUpdaterEntered.await(500, TimeUnit.MILLISECONDS),
          "The second updater must not derive state before acquiring the topic lock");

      releaseFirstUpdater.countDown();
      Assertions.assertEquals("first", first.get(10, TimeUnit.SECONDS).comment());
      Assertions.assertEquals("first-second", second.get(10, TimeUnit.SECONDS).comment());
      Assertions.assertEquals("first", secondObservedComment.get());
      Assertions.assertEquals(
          "first-second",
          TopicMetaService.getInstance().getTopicByIdentifier(topic.nameIdentifier()).comment());
    } finally {
      releaseFirstUpdater.countDown();
      executor.shutdownNow();
    }
  }

  @TestTemplate
  public void testInsertRejectsDeletedParentWithoutCreatingOrphan() throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("orphan_topic"),
            AUDIT_INFO);
    Assertions.assertTrue(
        SchemaMetaService.getInstance()
            .deleteSchema(NameIdentifier.of(metalakeName, catalogName, schemaName), true));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> TopicMetaService.getInstance().insertTopic(topic, false));
    Assertions.assertEquals(
        0, countRows("SELECT COUNT(*) FROM topic_meta WHERE topic_id = ?", topic.id()));
  }

  @TestTemplate
  public void testUpdateTopicRollsBackNestedConflict() throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("conflicting_topic"),
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);

    TopicEntity proposed = copyWithComment(topic, "outer");
    TombstoneChangedException exception =
        Assertions.assertThrows(
            TombstoneChangedException.class,
            () ->
                TopicMetaService.getInstance()
                    .updateTopic(
                        topic.nameIdentifier(),
                        ignored -> {
                          try {
                            backend.update(
                                topic.nameIdentifier(),
                                Entity.EntityType.TOPIC,
                                nested -> copyWithComment((TopicEntity) nested, "nested"));
                          } catch (Exception e) {
                            throw new RuntimeException(e);
                          }
                          return proposed;
                        }));
    Assertions.assertTrue(exception.getMessage().contains("Topic changed while updating"));
    Assertions.assertEquals(
        topic.comment(),
        TopicMetaService.getInstance().getTopicByIdentifier(topic.nameIdentifier()).comment());
  }

  @TestTemplate
  public void testRestoreRollsBackWhenTopicGenerationChanges() throws IOException {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("changed_generation_topic"),
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);
    TagEntity tag = newTag("changed_generation_topic_tag");
    TagMetaService.getInstance().insertTag(tag, false);
    TagMetaService.getInstance()
        .associateTagsWithMetadataObject(
            topic.nameIdentifier(),
            Entity.EntityType.TOPIC,
            new NameIdentifier[] {tag.nameIdentifier()},
            new NameIdentifier[0]);
    Assertions.assertTrue(TopicMetaService.getInstance().deleteTopic(topic.nameIdentifier()));
    EntityDeletionPO deletion = deletionFor(topic);
    updateRows(
        "UPDATE topic_meta SET current_version = current_version + 1 WHERE topic_id = ?",
        topic.id());

    Assertions.assertThrows(
        TombstoneChangedException.class,
        () ->
            TopicMetaService.getInstance()
                .restoreTopic(
                    topic.nameIdentifier(),
                    deletion,
                    Instant.now().toEpochMilli(),
                    "changed-generation-etag",
                    deletion.getExpiresAt()));
    Assertions.assertEquals(
        DeletionState.DELETED,
        EntityDeletionService.getInstance().get(deletion.getDeletionId()).getState());
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM topic_meta WHERE topic_id = ? AND deleted_at > 0"
                + " AND deletion_id = ?",
            topic.id(),
            deletion.getDeletionId()));
    Assertions.assertEquals(
        1,
        countRows(
            "SELECT COUNT(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
                + " AND tag_id = ? AND deleted_at = ? AND deletion_id = ?",
            topic.id(),
            tag.id(),
            deletion.getDeletedAt(),
            deletion.getDeletionId()));
  }

  @TestTemplate
  public void testDropWaitsForLockedTopicUpdate() throws Exception {
    Namespace namespace = NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName);
    TopicEntity topic =
        createTopicEntity(
            RandomIdGenerator.INSTANCE.nextId(),
            namespace,
            GravitinoITUtils.genRandomName("drop_serialized_topic"),
            AUDIT_INFO);
    TopicMetaService.getInstance().insertTopic(topic, false);

    CountDownLatch updaterEntered = new CountDownLatch(1);
    CountDownLatch releaseUpdater = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<TopicEntity> update =
          executor.submit(
              () ->
                  TopicMetaService.getInstance()
                      .updateTopic(
                          topic.nameIdentifier(),
                          oldTopic -> {
                            updaterEntered.countDown();
                            await(releaseUpdater);
                            return copyWithComment((TopicEntity) oldTopic, "committed-before-drop");
                          }));
      Assertions.assertTrue(updaterEntered.await(10, TimeUnit.SECONDS));

      Future<Boolean> drop =
          executor.submit(() -> TopicMetaService.getInstance().deleteTopic(topic.nameIdentifier()));
      Assertions.assertThrows(
          TimeoutException.class,
          () -> drop.get(500, TimeUnit.MILLISECONDS),
          "Drop must wait for the updater's schema/topic database locks");

      releaseUpdater.countDown();
      Assertions.assertEquals("committed-before-drop", update.get(10, TimeUnit.SECONDS).comment());
      Assertions.assertTrue(drop.get(10, TimeUnit.SECONDS));
      Assertions.assertEquals(
          1,
          countRows(
              "SELECT COUNT(*) FROM topic_meta WHERE topic_id = ?"
                  + " AND comment = 'committed-before-drop' AND deleted_at > 0",
              topic.id()));
    } finally {
      releaseUpdater.countDown();
      executor.shutdownNow();
    }
  }

  @TestTemplate
  public void testGetTopicByFullQualifiedNameMalformedNamespaceThrowsNoSuchEntityException()
      throws Exception {
    Method method =
        TopicMetaService.class.getDeclaredMethod(
            "getTopicPOByFullQualifiedName", NameIdentifier.class);
    method.setAccessible(true);

    NameIdentifier malformedIdentifier =
        NameIdentifier.of(Namespace.of(metalakeName, catalogName), "topic");

    InvocationTargetException invocationTargetException =
        assertThrows(
            InvocationTargetException.class,
            () -> method.invoke(TopicMetaService.getInstance(), malformedIdentifier));

    assertInstanceOf(NoSuchEntityException.class, invocationTargetException.getCause());
  }

  private EntityDeletionPO deletionFor(TopicEntity topic) {
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(topic.namespace().levels()), Entity.EntityType.SCHEMA);
    return EntityDeletionService.getInstance()
        .list(Entity.EntityType.TOPIC, schemaId, topic.name(), topic.id(), DeletionState.DELETED)
        .get(0);
  }

  private TagEntity newTag(String prefix) {
    return TagEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(GravitinoITUtils.genRandomName(prefix))
        .withNamespace(NamespaceUtil.ofTag(metalakeName))
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private int countActiveRelation(String table, String typeColumn, long topicId) {
    String sql =
        String.format(
            "SELECT count(*) FROM %s WHERE metadata_object_id = ? AND %s = 'TOPIC'"
                + " AND deleted_at = 0",
            table, typeColumn);
    return countRows(sql, topicId);
  }

  private int countActiveTagRelation(long topicId, long tagId) {
    return countRows(
        "SELECT count(*) FROM tag_relation_meta WHERE metadata_object_id = ?"
            + " AND metadata_object_type = 'TOPIC' AND tag_id = ? AND deleted_at = 0",
        topicId,
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
      throw new RuntimeException("Failed to count topic rows", e);
    }
  }

  private void updateRows(String sql, Object... parameters) {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      Assertions.assertEquals(1, statement.executeUpdate());
    } catch (SQLException e) {
      throw new RuntimeException("Failed to update topic rows", e);
    }
  }

  private TopicEntity copyWithComment(TopicEntity topic, String comment) {
    return TopicEntity.builder()
        .withId(topic.id())
        .withName(topic.name())
        .withNamespace(topic.namespace())
        .withComment(comment)
        .withProperties(topic.properties())
        .withAuditInfo(topic.auditInfo())
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent topic update");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while coordinating topic update", e);
    }
  }
}
