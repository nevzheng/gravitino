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

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.TopicMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TopicRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TopicPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * The service class for topic metadata. It provides the basic database operations for topic
 * metadata.
 */
public class TopicMetaService {
  private static final TopicMetaService INSTANCE = new TopicMetaService();

  public static TopicMetaService getInstance() {
    return INSTANCE;
  }

  private TopicMetaService() {}

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertTopic")
  public void insertTopic(TopicEntity topicEntity, boolean overwrite) throws IOException {
    NameIdentifierUtil.checkTopic(topicEntity.nameIdentifier());
    TopicPO.Builder builder = TopicPO.builder();
    fillTopicPOBuilderParentEntityId(builder, topicEntity.namespace());
    TopicPO po = POConverters.initializeTopicPOWithVersion(topicEntity, builder);
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  TopicRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, po.getSchemaId());
                    if (overwrite
                        && recoveryMapper.selectRecordedDeletedTopicForUpdate(po.getTopicId())
                            != null) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Topic ID %s belongs to a recoverable deletion; use metadata restore",
                          po.getTopicId());
                    }
                    SessionUtils.doWithoutCommit(
                        TopicMetaMapper.class,
                        mapper -> {
                          if (overwrite) {
                            mapper.insertTopicMetaOnDuplicateKeyUpdate(po);
                          } else {
                            mapper.insertTopicMeta(po);
                          }
                        });
                  }));
      // TODO: insert topic dataLayout version after supporting it
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.TOPIC, topicEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listTopicsByNamespace")
  public List<TopicEntity> listTopicsByNamespace(Namespace namespace) {
    NamespaceUtil.checkTopic(namespace);

    List<TopicPO> topicPOs = listTopicPOs(namespace);
    return POConverters.fromTopicPOs(topicPOs, namespace);
  }

  /**
   * Lists deleted topic base rows under one live schema.
   *
   * @param namespace topic namespace
   * @return deleted topic generations, newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedTopicsByNamespace")
  public List<TopicPO> listDeletedTopicsByNamespace(Namespace namespace) {
    NamespaceUtil.checkTopic(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        TopicRecoveryMapper.class, mapper -> mapper.listDeletedTopics(schemaId));
  }

  /**
   * Lists live topic base rows under one schema for recovery conflict detection.
   *
   * @param namespace topic namespace
   * @return live topic base rows
   */
  public List<TopicPO> listLiveTopicPOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkTopic(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        TopicRecoveryMapper.class, mapper -> mapper.listLiveTopics(schemaId));
  }

  /**
   * Lists globally live topic rows matching candidate immutable IDs.
   *
   * @param topicIds candidate topic IDs
   * @return matching live topic rows
   */
  public List<TopicPO> listLiveTopicsByIds(List<Long> topicIds) {
    if (topicIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        TopicRecoveryMapper.class, mapper -> mapper.listLiveTopicsByIds(topicIds));
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateTopic")
  public <E extends Entity & HasIdentifier> TopicEntity updateTopic(
      NameIdentifier ident, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkTopic(ident);
    Objects.requireNonNull(updater, "updater must not be null");
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(ident.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicReference<TopicEntity> candidate = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  TopicRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, schemaId);
                    TopicPO locked =
                        recoveryMapper.selectLiveTopicForUpdate(schemaId, ident.name());
                    if (locked == null) {
                      throw new NoSuchEntityException(
                          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                          Entity.EntityType.TOPIC.name().toLowerCase(Locale.ROOT),
                          ident.name());
                    }

                    TopicPO oldTopicPO =
                        SessionUtils.getWithoutCommit(
                            TopicMetaMapper.class,
                            mapper ->
                                mapper.selectTopicMetaBySchemaIdAndName(schemaId, ident.name()));
                    if (oldTopicPO == null
                        || !Objects.equals(oldTopicPO.getTopicId(), locked.getTopicId())) {
                      throw new TombstoneChangedException("Topic changed while updating %s", ident);
                    }
                    TopicEntity oldTopicEntity =
                        POConverters.fromTopicPO(oldTopicPO, ident.namespace());
                    TopicEntity newEntity = (TopicEntity) updater.apply((E) oldTopicEntity);
                    candidate.set(newEntity);
                    Preconditions.checkArgument(
                        Objects.equals(oldTopicEntity.id(), newEntity.id()),
                        "The updated topic entity id: %s should be same with the topic entity id before: %s",
                        newEntity.id(),
                        oldTopicEntity.id());

                    TopicPO newTopicPO =
                        POConverters.updateTopicPOWithVersion(oldTopicPO, newEntity);
                    int updated =
                        SessionUtils.getWithoutCommit(
                            TopicMetaMapper.class,
                            mapper -> mapper.updateTopicMeta(newTopicPO, oldTopicPO));
                    if (updated != 1) {
                      throw new TombstoneChangedException("Topic changed while updating %s", ident);
                    }

                    if (!Objects.equals(oldTopicPO.getTopicName(), newTopicPO.getTopicName())) {
                      String oldFullName =
                          NameIdentifierUtil.ofTopic(
                                  ident.namespace().level(0),
                                  ident.namespace().level(1),
                                  ident.namespace().level(2),
                                  oldTopicPO.getTopicName())
                              .toString();
                      SessionUtils.doWithoutCommit(
                          EntityChangeLogMapper.class,
                          mapper ->
                              mapper.insertEntityChange(
                                  ident.namespace().level(0),
                                  Entity.EntityType.TOPIC.name(),
                                  oldFullName,
                                  OperateType.ALTER));
                    }
                  }));
    } catch (RuntimeException re) {
      TopicEntity newEntity = candidate.get();
      ExceptionUtils.checkSQLException(
          re,
          Entity.EntityType.TOPIC,
          newEntity == null ? ident.toString() : newEntity.nameIdentifier().toString());
      throw re;
    }
    return Objects.requireNonNull(candidate.get(), "updated topic must not be null");
  }

  private TopicPO getTopicPOBySchemaIdAndName(Long schemaId, String topicName) {
    TopicPO topicPO =
        SessionUtils.getWithoutCommit(
            TopicMetaMapper.class,
            mapper -> mapper.selectTopicMetaBySchemaIdAndName(schemaId, topicName));

    if (topicPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TOPIC.name().toLowerCase(),
          topicName);
    }
    return topicPO;
  }

  private TopicPO getTopicPOByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkTopic(identifier);

    return topicPOFetcher().apply(identifier);
  }

  private List<TopicPO> listTopicPOs(Namespace namespace) {
    return topicListFetcher().apply(namespace);
  }

  private List<TopicPO> listTopicPOsBySchemaId(Namespace namespace) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);

    return SessionUtils.getWithoutCommit(
        TopicMetaMapper.class, mapper -> mapper.listTopicPOsBySchemaId(schemaId));
  }

  private List<TopicPO> listTopicPOsByFullQualifiedName(Namespace namespace) {
    if (namespace == null || namespace.length() != 3) {
      throw new NoSuchEntityException(
          "Topic namespace must have 3 levels, the input namespace is %s", namespace);
    }
    String[] namespaceLevels = namespace.levels();
    List<TopicPO> topicPOs =
        SessionUtils.getWithoutCommit(
            TopicMetaMapper.class,
            mapper ->
                mapper.listTopicPOsByFullQualifiedName(
                    namespaceLevels[0], namespaceLevels[1], namespaceLevels[2]));
    if (topicPOs.isEmpty() || topicPOs.get(0).getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          namespaceLevels[2]);
    }
    return topicPOs.stream().filter(po -> po.getTopicId() != null).collect(Collectors.toList());
  }

  private TopicPO getTopicPOBySchemaId(NameIdentifier identifier) {
    Long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    return getTopicPOBySchemaIdAndName(schemaId, identifier.name());
  }

  private TopicPO getTopicPOByFullQualifiedName(NameIdentifier identifier) {
    if (identifier == null
        || identifier.namespace() == null
        || identifier.namespace().length() != 3) {
      throw new NoSuchEntityException(
          "Topic identifier must have a 3-level namespace, the input identifier is %s", identifier);
    }
    String[] namespaceLevels = identifier.namespace().levels();
    TopicPO topicPO =
        SessionUtils.getWithoutCommit(
            TopicMetaMapper.class,
            mapper ->
                mapper.selectTopicByFullQualifiedName(
                    namespaceLevels[0], namespaceLevels[1], namespaceLevels[2], identifier.name()));

    if (topicPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TOPIC.name().toLowerCase(),
          identifier.name());
    }

    if (topicPO.getSchemaId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(),
          namespaceLevels[2]);
    }

    if (topicPO.getTopicId() == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TOPIC.name().toLowerCase(),
          identifier.name());
    }

    return topicPO;
  }

  private Function<Namespace, List<TopicPO>> topicListFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::listTopicPOsBySchemaId
        : this::listTopicPOsByFullQualifiedName;
  }

  private Function<NameIdentifier, TopicPO> topicPOFetcher() {
    return GravitinoEnv.getInstance().cacheEnabled()
        ? this::getTopicPOBySchemaId
        : this::getTopicPOByFullQualifiedName;
  }

  private void fillTopicPOBuilderParentEntityId(TopicPO.Builder builder, Namespace namespace) {
    NamespaceUtil.checkTopic(namespace);
    NamespacedEntityId namespacedEntityId =
        EntityIdService.getEntityIds(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    builder.withMetalakeId(namespacedEntityId.namespaceIds()[0]);
    builder.withCatalogId(namespacedEntityId.namespaceIds()[1]);
    builder.withSchemaId(namespacedEntityId.entityId());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTopicByIdentifier")
  public TopicEntity getTopicByIdentifier(NameIdentifier identifier) {
    TopicPO topicPO = getTopicPOByIdentifier(identifier);
    return POConverters.fromTopicPO(topicPO, identifier.namespace());
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteTopic")
  public boolean deleteTopic(NameIdentifier identifier) {
    return deleteTopic(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a topic and records its durable deletion generation atomically.
   *
   * @param identifier topic identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live topic row was deleted
   */
  public boolean deleteTopic(NameIdentifier identifier, long retentionMs) {
    return deleteTopic(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a topic using one timestamp and generation token for every affected row.
   *
   * @param identifier topic identifier
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live topic snapshot was deleted
   */
  public boolean deleteTopic(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkTopic(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicInteger deleteResult = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                TopicRecoveryMapper.class,
                mapper -> {
                  lockLiveSchema(mapper, schemaId);
                  TopicPO topic = mapper.selectLiveTopicForUpdate(schemaId, identifier.name());
                  if (topic == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.TOPIC.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  long deletionTimestamp =
                      chooseDeletedAt(
                          mapper, topic.getTopicId(), schemaId, topic.getTopicName(), deletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.TOPIC,
                              topic.getTopicId(),
                              topic.getMetalakeId(),
                              topic.getCatalogId(),
                              topic.getSchemaId(),
                              topic.getTopicName(),
                              topic.getCurrentVersion(),
                              deletionTimestamp,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int topicMeta =
                      mapper.softDeleteTopicMeta(
                          topic.getTopicId(),
                          topic.getSchemaId(),
                          topic.getTopicName(),
                          topic.getCurrentVersion(),
                          topic.getLastVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId());
                  deleteResult.set(topicMeta);
                  if (topicMeta != 1) {
                    throw new TombstoneChangedException(
                        "Topic changed while deleting %s", identifier);
                  }
                  mapper.softDeleteOwnerRelations(
                      topic.getTopicId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteSecurableObjects(
                      topic.getTopicId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteTagRelations(
                      topic.getTopicId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteStatistics(
                      topic.getTopicId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeletePolicyRelations(
                      topic.getTopicId(), deletionTimestamp, deletion.getDeletionId());
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.TOPIC.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleteResult.get() == 1;
  }

  /**
   * Restores one exact topic metadata deletion generation transactionally.
   *
   * <p>This operation never calls or validates a connector or downstream Kafka topic. It restores
   * only the Topic metadata retained by Gravitino.
   *
   * @param identifier original topic identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored topic entity
   */
  public TopicEntity restoreTopic(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkTopic(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<TopicEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          SessionUtils.doWithoutCommit(
              TopicRecoveryMapper.class,
              mapper -> {
                Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
                if (!Objects.equals(schemaId, lockedSchemaId)) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.PARENT_CHANGED,
                      "The parent schema changed while restoring %s",
                      identifier);
                }
                EntityDeletionPO latest =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectLatestEntityDeletion(
                                Entity.EntityType.TOPIC.name(), schemaId, identifier.name()));
                if (latest == null
                    || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                      "Deletion generation %s is no longer latest for topic %s",
                      observed.getDeletionId(),
                      identifier);
                }

                EntityDeletionPO actual =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
                  restored.set(loadIdempotentlyRestoredTopic(identifier, schemaId, actual));
                  return;
                }
                validateDeletionSnapshot(identifier, schemaId, observed, actual);
                if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
                  throw new TombstoneExpiredException(
                      "Deletion generation %s expired at %s",
                      observed.getDeletionId(), effectiveExpiresAt);
                }

                int claimed =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.compareAndSetState(
                                actual.getDeletionId(),
                                DeletionState.DELETED,
                                actual.getRevision(),
                                DeletionState.RESTORING,
                                null,
                                null));
                if (claimed != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }

                TopicPO generation =
                    mapper.selectTopicGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateTopicGeneration(actual, generation);

                mapper.restoreOwnerRelations(
                    actual.getEntityId(),
                    actual.getDeletedAt(),
                    actual.getDeletionId(),
                    restoredAt);
                mapper.restoreSecurableObjects(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreTagRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreStatistics(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restorePolicyRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (restoreTopicMeta(mapper, actual, generation, identifier) != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }

                int receipt =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.compareAndSetState(
                                actual.getDeletionId(),
                                DeletionState.RESTORING,
                                actual.getRevision() + 1,
                                DeletionState.RESTORED,
                                restoredAt,
                                restoreEtag));
                if (receipt != 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }
                SessionUtils.doWithoutCommit(
                    EntityChangeLogMapper.class,
                    changeLogMapper ->
                        changeLogMapper.insertEntityChange(
                            identifier.namespace().level(0),
                            Entity.EntityType.TOPIC.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getTopicByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored topic must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded topic deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredTopicDeletions")
  public int purgeExpiredTopicDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.TOPIC, legacyTimeline, limit);
    if (expired.isEmpty()) {
      return 0;
    }

    long purgedAt = Instant.now().toEpochMilli();
    AtomicInteger purged = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () -> {
          for (EntityDeletionPO observed : expired) {
            EntityDeletionPO actual =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper -> mapper.selectEntityDeletion(observed.getDeletionId()));
            if (actual == null
                || actual.getState() != DeletionState.DELETED
                || !Objects.equals(actual.getRevision(), observed.getRevision())
                || actual.getDeletedAt() <= 0
                || actual.getDeletedAt() >= legacyTimeline) {
              continue;
            }

            int claimed =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper ->
                        mapper.compareAndSetState(
                            actual.getDeletionId(),
                            DeletionState.DELETED,
                            actual.getRevision(),
                            DeletionState.PURGING,
                            null,
                            null));
            if (claimed != 1) {
              continue;
            }
            SessionUtils.doWithoutCommit(
                TopicRecoveryMapper.class, mapper -> purgeTopicDeletionGeneration(mapper, actual));
            int receipt =
                SessionUtils.getWithoutCommit(
                    EntityDeletionMapper.class,
                    mapper ->
                        mapper.compareAndSetState(
                            actual.getDeletionId(),
                            DeletionState.PURGING,
                            actual.getRevision() + 1,
                            DeletionState.PURGED,
                            purgedAt,
                            null));
            if (receipt != 1) {
              throw tombstoneChanged(actual.getDeletionId());
            }
            purged.incrementAndGet();
          }
        });
    return purged.get();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteTopicMetasByLegacyTimeline")
  public int deleteTopicMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    return SessionUtils.doWithCommitAndFetchResult(
        TopicMetaMapper.class,
        mapper -> {
          return mapper.deleteTopicMetasByLegacyTimeline(legacyTimeline, limit);
        });
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTopicIdBySchemaIdAndName")
  public Long getTopicIdBySchemaIdAndName(Long schemaId, String topicName) {
    Long topicId =
        SessionUtils.getWithoutCommit(
            TopicMetaMapper.class,
            mapper -> mapper.selectTopicIdBySchemaIdAndName(schemaId, topicName));

    if (topicId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TOPIC.name().toLowerCase(),
          topicName);
    }
    return topicId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetTopicByIdentifier")
  public List<TopicEntity> batchGetTopicByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    NameIdentifier schemaIdent = NameIdentifierUtil.getSchemaIdentifier(firstIdent);
    List<String> topicNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        TopicMetaMapper.class,
        mapper -> {
          List<TopicPO> topicPOs =
              mapper.batchSelectTopicByIdentifier(
                  schemaIdent.namespace().level(0),
                  schemaIdent.namespace().level(1),
                  schemaIdent.name(),
                  topicNames);
          return POConverters.fromTopicPOs(topicPOs, firstIdent.namespace());
        });
  }

  private static void lockLiveSchema(TopicRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      TopicRecoveryMapper mapper,
      long topicId,
      long schemaId,
      String topicName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestTopicDeletedAt(topicId, schemaId, topicName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier,
      long schemaId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual) {
    boolean unchanged =
        actual != null
            && Objects.equals(actual.getDeletionId(), observed.getDeletionId())
            && Objects.equals(actual.getEntityType(), observed.getEntityType())
            && Objects.equals(actual.getEntityId(), observed.getEntityId())
            && Objects.equals(actual.getMetalakeId(), observed.getMetalakeId())
            && Objects.equals(actual.getCatalogId(), observed.getCatalogId())
            && Objects.equals(actual.getParentId(), observed.getParentId())
            && Objects.equals(actual.getEntityName(), observed.getEntityName())
            && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
            && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
            && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
            && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
            && Objects.equals(actual.getState(), observed.getState())
            && Objects.equals(actual.getRevision(), observed.getRevision())
            && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
            && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
            && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt())
            && actual.getState() == DeletionState.DELETED
            && Entity.EntityType.TOPIC.name().equals(actual.getEntityType())
            && Objects.equals(actual.getParentId(), schemaId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      long schemaId,
      EntityDeletionPO observed,
      @Nullable EntityDeletionPO actual,
      String restoreEtag) {
    return actual != null
        && observed.getState() == DeletionState.DELETED
        && actual.getState() == DeletionState.RESTORED
        && Objects.equals(actual.getDeletionId(), observed.getDeletionId())
        && Objects.equals(actual.getEntityType(), observed.getEntityType())
        && Objects.equals(actual.getEntityId(), observed.getEntityId())
        && Objects.equals(actual.getMetalakeId(), observed.getMetalakeId())
        && Objects.equals(actual.getCatalogId(), observed.getCatalogId())
        && Objects.equals(actual.getParentId(), observed.getParentId())
        && Objects.equals(actual.getEntityName(), observed.getEntityName())
        && Objects.equals(actual.getDeletedAt(), observed.getDeletedAt())
        && Objects.equals(actual.getExpiresAt(), observed.getExpiresAt())
        && Objects.equals(actual.getDeletedBy(), observed.getDeletedBy())
        && Objects.equals(actual.getEntityVersion(), observed.getEntityVersion())
        && Objects.equals(actual.getParentId(), schemaId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  private static TopicEntity loadIdempotentlyRestoredTopic(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    TopicEntity liveTopic;
    try {
      liveTopic = getInstance().getTopicByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveTopic.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Topic ID %s is active under a different logical topic",
          deletion.getEntityId());
    }
    return liveTopic;
  }

  private static void validateTopicGeneration(
      EntityDeletionPO deletion, @Nullable TopicPO generation) {
    if (generation == null
        || !Objects.equals(generation.getTopicId(), deletion.getEntityId())
        || !Objects.equals(generation.getSchemaId(), deletion.getParentId())
        || !Objects.equals(generation.getTopicName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static int restoreTopicMeta(
      TopicRecoveryMapper mapper,
      EntityDeletionPO deletion,
      TopicPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreTopicMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.getCurrentVersion(),
          generation.getLastVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.TOPIC, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live topic already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void purgeTopicDeletionGeneration(
      TopicRecoveryMapper mapper, EntityDeletionPO deletion) {
    TopicPO generation =
        mapper.selectTopicGeneration(
            deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    validateTopicGeneration(deletion, generation);
    mapper.hardDeleteOwnerRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteSecurableObjects(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteTagRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteStatistics(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeletePolicyRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    if (mapper.hardDeleteTopicMeta(
            deletion.getEntityId(),
            deletion.getParentId(),
            deletion.getEntityName(),
            generation.getCurrentVersion(),
            generation.getLastVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        != 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }
}
