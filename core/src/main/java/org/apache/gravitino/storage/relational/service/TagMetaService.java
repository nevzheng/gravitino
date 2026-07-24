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
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.claimPurge;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.claimRestore;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.completePurge;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.completeRestore;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.eligibleForPurge;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.incompleteGeneration;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.insertRestoreChange;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.isCompletedRestoreReplay;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.loadDeletion;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.tombstoneChanged;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateDeletionSnapshot;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateLatestDeletion;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateNotExpired;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateRestoreArguments;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchTagException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.meta.GenericEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.TagRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TagMetadataObjectRelPO;
import org.apache.gravitino.storage.relational.po.TagPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

public class TagMetaService {

  private static final TagMetaService INSTANCE = new TagMetaService();
  private static final Set<String> SUPPORTED_RELATION_TYPES =
      Set.of(
          MetadataObject.Type.CATALOG.name(),
          MetadataObject.Type.SCHEMA.name(),
          MetadataObject.Type.TABLE.name(),
          MetadataObject.Type.VIEW.name(),
          MetadataObject.Type.FILESET.name(),
          MetadataObject.Type.TOPIC.name(),
          MetadataObject.Type.COLUMN.name(),
          MetadataObject.Type.MODEL.name(),
          MetadataObject.Type.FUNCTION.name());

  public static TagMetaService getInstance() {
    return INSTANCE;
  }

  private TagMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listTagsByNamespace")
  public List<TagEntity> listTagsByNamespace(Namespace ns) {
    String metalakeName = ns.level(0);
    List<TagPO> tagPOs =
        SessionUtils.getWithoutCommit(
            TagMetaMapper.class, mapper -> mapper.listTagPOsByMetalake(metalakeName));
    return tagPOs.stream()
        .map(tagPO -> POConverters.fromTagPO(tagPO, ns))
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTagByIdentifier")
  public TagEntity getTagByIdentifier(NameIdentifier ident) {
    String metalakeName = ident.namespace().level(0);
    TagPO tagPO = getTagPOByMetalakeAndName(metalakeName, ident.name());
    return POConverters.fromTagPO(tagPO, ident.namespace());
  }

  /** Lists independently deleted tag roots under one live metalake. */
  public List<TagPO> listDeletedTagsByNamespace(Namespace namespace) {
    NameIdentifierUtil.checkTag(NameIdentifier.of(namespace, "tag"));
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        TagRecoveryMapper.class, mapper -> mapper.listDeletedRootTags(metalakeId));
  }

  /** Lists live tag roots under one metalake for recovery collision checks. */
  public List<TagPO> listLiveTagPOsByNamespace(Namespace namespace) {
    NameIdentifierUtil.checkTag(NameIdentifier.of(namespace, "tag"));
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        TagRecoveryMapper.class, mapper -> mapper.listLiveTags(metalakeId));
  }

  /** Lists globally live tag roots matching immutable identifiers. */
  public List<TagPO> listLiveTagsByIds(List<Long> tagIds) {
    if (tagIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        TagRecoveryMapper.class, mapper -> mapper.listLiveTagsByIds(tagIds));
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertTag")
  public void insertTag(TagEntity tagEntity, boolean overwritten) throws IOException {
    Namespace ns = tagEntity.namespace();
    String metalakeName = ns.level(0);

    try {
      Long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);

      TagPO.Builder builder = TagPO.builder().withMetalakeId(metalakeId);
      TagPO tagPO = POConverters.initializeTagPOWithVersion(tagEntity, builder);

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              SessionUtils.doWithoutCommit(
                  TagRecoveryMapper.class,
                  mapper -> {
                    if (!mapper
                        .selectDeletedTagsForUpdate(Collections.singletonList(tagPO.getTagId()))
                        .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Tag ID %s belongs to a deleted root; use metadata restore",
                          tagPO.getTagId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  TagMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertTagMetaOnDuplicateKeyUpdate(tagPO);
                    } else {
                      mapper.insertTagMeta(tagPO);
                    }
                  }));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, tagEntity.toString());
      throw e;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateTag")
  public <E extends Entity & HasIdentifier> TagEntity updateTag(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    String metalakeName = identifier.namespace().level(0);

    try {
      TagPO tagPO = getTagPOByMetalakeAndName(metalakeName, identifier.name());
      TagEntity oldTagEntity = POConverters.fromTagPO(tagPO, identifier.namespace());
      TagEntity updatedTagEntity = (TagEntity) updater.apply((E) oldTagEntity);
      Preconditions.checkArgument(
          Objects.equals(oldTagEntity.id(), updatedTagEntity.id()),
          "The updated tag entity id: %s must have the same id as the old entity id %s",
          updatedTagEntity.id(),
          oldTagEntity.id());

      long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);
      int[] result = new int[1];
      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              result[0] =
                  SessionUtils.getWithoutCommit(
                      TagMetaMapper.class,
                      mapper ->
                          mapper.updateTagMeta(
                              POConverters.updateTagPOWithVersion(tagPO, updatedTagEntity),
                              tagPO)));

      if (result[0] == 0) {
        throw new IOException("Failed to update the entity: " + identifier);
      }

      return updatedTagEntity;

    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, identifier.toString());
      throw e;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteTag")
  public boolean deleteTag(NameIdentifier identifier) {
    return deleteTag(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one tag aggregate and records its recoverable deletion generation. */
  public boolean deleteTag(NameIdentifier identifier, long retentionMs) {
    return deleteTag(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /** Soft-deletes live tag relations first and the exact tag root last using one token. */
  public boolean deleteTag(NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkTag(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                TagRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalake(mapper, metalakeId);
                  TagPO tag = mapper.lockLiveTag(metalakeId, identifier.name());
                  if (tag == null) {
                    return;
                  }

                  long deletedAt =
                      chooseDeletedAt(
                          mapper, tag.getTagId(), metalakeId, tag.getTagName(), requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.TAG,
                              tag.getTagId(),
                              metalakeId,
                              null,
                              metalakeId,
                              tag.getTagName(),
                              tag.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int relationCount =
                      mapper.softDeleteTagRelations(
                          tag.getTagId(), deletedAt, deletion.getDeletionId());
                  deletion.setAffectedRowCount(Math.addExact(1L, relationCount));

                  int rootCount =
                      mapper.softDeleteTag(
                          tag.getTagId(),
                          metalakeId,
                          tag.getTagName(),
                          tag.getCurrentVersion(),
                          deletedAt,
                          deletion.getDeletionId());
                  deleted.set(rootCount);
                  if (rootCount != 1) {
                    throw tombstoneChanged(deletion.getDeletionId());
                  }

                  TagPO generation =
                      mapper.selectTagGenerationForUpdate(
                          tag.getTagId(), deletedAt, deletion.getDeletionId());
                  List<TagMetadataObjectRelPO> relations =
                      mapper.listTagRelationGenerationForUpdate(
                          tag.getTagId(), deletedAt, deletion.getDeletionId());
                  validateTagGeneration(mapper, deletion, generation, relations);

                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.TAG.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /** Restores one exact tag-and-relation deletion generation transactionally. */
  public TagEntity restoreTag(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkTag(identifier);
    validateRestoreArguments(observed, restoredAt, restoreEtag, effectiveExpiresAt);
    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicReference<TagEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  TagRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                    validateLatestDeletion(Entity.EntityType.TAG, identifier, metalakeId, observed);
                    EntityDeletionPO actual = loadDeletion(observed.getDeletionId());
                    if (isCompletedRestoreReplay(
                        Entity.EntityType.TAG,
                        identifier,
                        metalakeId,
                        observed,
                        actual,
                        restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredTag(identifier, metalakeId, actual));
                      return;
                    }
                    validateDeletionSnapshot(
                        Entity.EntityType.TAG, identifier, metalakeId, observed, actual);
                    validateNotExpired(actual, effectiveExpiresAt);
                    claimRestore(actual);

                    TagPO generation =
                        mapper.selectTagGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    List<TagMetadataObjectRelPO> relations =
                        mapper.listTagRelationGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateTagGeneration(mapper, actual, generation, relations);
                    validateTagOccupancy(mapper, actual);

                    int restoredRelations =
                        restoreTagRelations(mapper, actual, identifier, relations.size());
                    if (restoredRelations != relations.size()) {
                      throw incompleteGeneration(actual.getDeletionId());
                    }
                    int restoredRoot = restoreTagRoot(mapper, actual, generation, identifier);
                    if (restoredRoot != 1) {
                      throw incompleteGeneration(actual.getDeletionId());
                    }

                    completeRestore(actual, restoredAt, restoreEtag);
                    insertRestoreChange(identifier, Entity.EntityType.TAG);
                    restored.set(getTagByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(
          Entity.EntityType.TAG, identifier, metalakeId, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredTag(identifier, metalakeId, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored tag must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded tag generations. */
  public int purgeExpiredTagDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.TAG, legacyTimeline, limit);
    if (expired.isEmpty()) {
      return 0;
    }

    long purgedAt = Instant.now().toEpochMilli();
    AtomicInteger purged = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () -> {
          for (EntityDeletionPO observed : expired) {
            EntityDeletionPO actual = loadDeletion(observed.getDeletionId());
            if (!eligibleForPurge(observed, actual, legacyTimeline) || !claimPurge(actual)) {
              continue;
            }
            SessionUtils.doWithoutCommit(
                TagRecoveryMapper.class,
                mapper -> {
                  TagPO generation =
                      mapper.selectTagGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  List<TagMetadataObjectRelPO> relations =
                      mapper.listTagRelationGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateTagGeneration(mapper, actual, generation, relations);

                  if (mapper.hardDeleteTagRelations(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                      != relations.size()) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  if (mapper.hardDeleteTag(
                          actual.getEntityId(),
                          actual.getMetalakeId(),
                          actual.getEntityName(),
                          actual.getEntityVersion(),
                          actual.getDeletedAt(),
                          actual.getDeletionId())
                      != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                });
            completePurge(actual, purgedAt);
            purged.incrementAndGet();
          }
        });
    return purged.get();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listTagsForMetadataObject")
  public List<TagEntity> listTagsForMetadataObject(
      NameIdentifier objectIdent, Entity.EntityType objectType)
      throws NoSuchTagException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    List<TagPO> tagPOs = null;
    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);

      tagPOs =
          SessionUtils.getWithoutCommit(
              TagMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listTagPOsByMetadataObjectIdAndType(
                      metadataObjectId, metadataObject.type().toString()));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, objectIdent.toString());
      throw e;
    }

    return tagPOs.stream()
        .map(tagPO -> POConverters.fromTagPO(tagPO, NamespaceUtil.ofTag(metalake)))
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getTagForMetadataObject")
  public TagEntity getTagForMetadataObject(
      NameIdentifier objectIdent, Entity.EntityType objectType, NameIdentifier tagIdent)
      throws NoSuchEntityException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    TagPO tagPO = null;
    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);

      tagPO =
          SessionUtils.getWithoutCommit(
              TagMetadataObjectRelMapper.class,
              mapper ->
                  mapper.getTagPOsByMetadataObjectAndTagName(
                      metadataObjectId, metadataObject.type().toString(), tagIdent.name()));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, tagIdent.toString());
      throw e;
    }

    if (tagPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TAG.name().toLowerCase(),
          tagIdent.name());
    }

    return POConverters.fromTagPO(tagPO, NamespaceUtil.ofTag(metalake));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listAssociatedMetadataObjectsForTag")
  public List<GenericEntity> listAssociatedMetadataObjectsForTag(NameIdentifier tagIdent)
      throws IOException {
    String metalakeName = tagIdent.namespace().level(0);
    String tagName = tagIdent.name();

    try {
      List<TagMetadataObjectRelPO> tagMetadataObjectRelPOs =
          SessionUtils.doWithCommitAndFetchResult(
              TagMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listTagMetadataObjectRelsByMetalakeAndTagName(metalakeName, tagName));

      List<GenericEntity> metadataObjects = Lists.newArrayList();
      Map<String, List<TagMetadataObjectRelPO>> tagMetadataObjectRelPOsByType =
          tagMetadataObjectRelPOs.stream()
              .collect(Collectors.groupingBy(TagMetadataObjectRelPO::getMetadataObjectType));

      for (Map.Entry<String, List<TagMetadataObjectRelPO>> entry :
          tagMetadataObjectRelPOsByType.entrySet()) {
        String metadataObjectType = entry.getKey();
        List<TagMetadataObjectRelPO> rels = entry.getValue();

        List<Long> metadataObjectIds =
            rels.stream()
                .map(TagMetadataObjectRelPO::getMetadataObjectId)
                .collect(Collectors.toList());
        Map<Long, String> metadataObjectNames =
            MetadataObjectService.TYPE_TO_FULLNAME_FUNCTION_MAP
                .get(MetadataObject.Type.valueOf(metadataObjectType))
                .apply(metadataObjectIds);

        for (Map.Entry<Long, String> metadataObjectName : metadataObjectNames.entrySet()) {
          String fullName = metadataObjectName.getValue();

          // Metadata object may be deleted asynchronously when we query the name, so it will
          // return null, we should skip this metadata object.
          if (fullName != null) {
            metadataObjects.add(
                GenericEntity.builder()
                    .withName(fullName)
                    .withEntityType(Entity.EntityType.valueOf(metadataObjectType))
                    .withId(metadataObjectName.getKey())
                    .build());
          }
        }
      }

      return metadataObjects;
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, tagIdent.toString());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "associateTagsWithMetadataObject")
  public List<TagEntity> associateTagsWithMetadataObject(
      NameIdentifier objectIdent,
      Entity.EntityType objectType,
      NameIdentifier[] tagsToAdd,
      NameIdentifier[] tagsToRemove)
      throws NoSuchEntityException, EntityAlreadyExistsException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);
      Long metalakeId = MetadataMutationLock.metalakeId(objectIdent, objectType);
      Long catalogId = MetadataMutationLock.catalogId(objectIdent, objectType);
      Long schemaId = MetadataMutationLock.schemaId(objectIdent, objectType);

      // Fetch all the tags need to associate with the metadata object.
      List<String> tagNamesToAdd =
          Arrays.stream(tagsToAdd).map(NameIdentifier::name).collect(Collectors.toList());
      List<TagPO> tagPOsToAdd =
          tagNamesToAdd.isEmpty()
              ? Collections.emptyList()
              : getTagPOsByMetalakeAndNames(metalake, tagNamesToAdd);

      // Fetch all the tags need to remove from the metadata object.
      List<String> tagNamesToRemove =
          Arrays.stream(tagsToRemove).map(NameIdentifier::name).collect(Collectors.toList());
      List<TagPO> tagPOsToRemove =
          tagNamesToRemove.isEmpty()
              ? Collections.emptyList()
              : getTagPOsByMetalakeAndNames(metalake, tagNamesToRemove);

      SessionUtils.doMultipleWithCommit(
          () ->
              MetadataMutationLock.lockMetadataIds(
                  Collections.singletonList(metalakeId),
                  Collections.singletonList(catalogId),
                  Collections.singletonList(schemaId)),
          () -> {
            // Insert the tag metadata object relations.
            if (tagPOsToAdd.isEmpty()) {
              return;
            }

            List<TagMetadataObjectRelPO> tagRelsToAdd =
                tagPOsToAdd.stream()
                    .map(
                        tagPO ->
                            POConverters.initializeTagMetadataObjectRelPOWithVersion(
                                tagPO.getTagId(),
                                metadataObjectId,
                                metadataObject.type().toString()))
                    .collect(Collectors.toList());
            SessionUtils.doWithoutCommit(
                TagMetadataObjectRelMapper.class,
                mapper -> mapper.batchInsertTagMetadataObjectRels(tagRelsToAdd));
          },
          () -> {
            // Remove the tag metadata object relations.
            if (tagPOsToRemove.isEmpty()) {
              return;
            }

            List<Long> tagIdsToRemove =
                tagPOsToRemove.stream().map(TagPO::getTagId).collect(Collectors.toList());
            SessionUtils.doWithoutCommit(
                TagMetadataObjectRelMapper.class,
                mapper ->
                    mapper.batchDeleteTagMetadataObjectRelsByTagIdsAndMetadataObject(
                        metadataObjectId, metadataObject.type().toString(), tagIdsToRemove));
          });

      // Fetch all the tags associated with the metadata object after the operation.
      List<TagPO> tagPOs =
          SessionUtils.getWithoutCommit(
              TagMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listTagPOsByMetadataObjectIdAndType(
                      metadataObjectId, metadataObject.type().toString()));

      return tagPOs.stream()
          .map(tagPO -> POConverters.fromTagPO(tagPO, NamespaceUtil.ofTag(metalake)))
          .collect(Collectors.toList());

    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.TAG, objectIdent.toString());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteTagMetasByLegacyTimeline")
  public int deleteTagMetasByLegacyTimeline(long legacyTimeline, int limit) {
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                TagRecoveryMapper.class,
                mapper -> {
                  List<Long> tagIds =
                      mapper.selectLegacyTagRootsForUpdate(legacyTimeline, limit).stream()
                          .map(TagPO::getTagId)
                          .collect(Collectors.toList());
                  if (tagIds.isEmpty()) {
                    return;
                  }
                  // Old root-first tag deletion could leave a live null-token relation behind.
                  // Remove all such rows for selected legacy roots before deleting those roots.
                  deleted.addAndGet(mapper.hardDeleteLegacyTagRelations(tagIds));
                  deleted.addAndGet(mapper.hardDeleteLegacyTags(tagIds, legacyTimeline));
                }),
        () ->
            deleted.addAndGet(
                SessionUtils.getWithoutCommit(
                    TagMetadataObjectRelMapper.class,
                    mapper -> mapper.deleteTagEntityRelsByLegacyTimeline(legacyTimeline, limit))));

    return deleted.get();
  }

  private static long chooseDeletedAt(
      TagRecoveryMapper mapper,
      long tagId,
      long metalakeId,
      String tagName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestTagDeletedAt(tagId, metalakeId, tagName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static void lockLiveMetalake(TagRecoveryMapper mapper, long metalakeId) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(),
          metalakeId);
    }
  }

  private static void lockLiveMetalakeForRestore(
      TagRecoveryMapper mapper, long metalakeId, NameIdentifier identifier) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.PARENT_CHANGED,
          "The parent metalake changed while restoring tag %s",
          identifier);
    }
  }

  private static void validateTagGeneration(
      TagRecoveryMapper mapper,
      EntityDeletionPO deletion,
      TagPO generation,
      List<TagMetadataObjectRelPO> relations) {
    Long expected = deletion.getAffectedRowCount();
    if (generation == null
        || !Objects.equals(generation.getTagId(), deletion.getEntityId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getParentId())
        || !Objects.equals(generation.getTagName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())
        || expected == null
        || expected <= 0
        || expected != Math.addExact(1L, relations.size())
        || mapper.countTagGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != 1
        || mapper.countTagRelationGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != relations.size()
        || mapper.countLiveRelationDuplicates(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != 0) {
      throw incompleteGeneration(deletion.getDeletionId());
    }

    Set<Long> relationIds = new HashSet<>();
    Set<String> relationKeys = new HashSet<>();
    for (TagMetadataObjectRelPO relation : relations) {
      String relationKey =
          relation.getMetadataObjectType() + '\u0000' + relation.getMetadataObjectId();
      if (relation.getId() == null
          || relation.getId() <= 0
          || !relationIds.add(relation.getId())
          || !Objects.equals(relation.getTagId(), deletion.getEntityId())
          || relation.getMetadataObjectId() == null
          || relation.getMetadataObjectId() <= 0
          || !SUPPORTED_RELATION_TYPES.contains(relation.getMetadataObjectType())
          || !relationKeys.add(relationKey)
          || relation.getAuditInfo() == null
          || relation.getCurrentVersion() == null
          || relation.getCurrentVersion() <= 0
          || relation.getLastVersion() == null
          || relation.getLastVersion() < relation.getCurrentVersion()
          || !Objects.equals(relation.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(relation.getDeletionId(), deletion.getDeletionId())) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }
  }

  private static void validateTagOccupancy(TagRecoveryMapper mapper, EntityDeletionPO deletion) {
    TagPO occupant = mapper.lockLiveTag(deletion.getMetalakeId(), deletion.getEntityName());
    if (occupant == null) {
      return;
    }
    if (Objects.equals(occupant.getTagId(), deletion.getEntityId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Tag ID %s is already active under a different logical tag",
          deletion.getEntityId());
    }
    throw new RecoveryConflictException(
        RecoveryConflictReason.NAME_OCCUPIED,
        "A live tag already occupies name %s",
        deletion.getEntityName());
  }

  private static int restoreTagRelations(
      TagRecoveryMapper mapper,
      EntityDeletionPO deletion,
      NameIdentifier identifier,
      int expectedCount) {
    try {
      int restored =
          mapper.restoreTagRelations(
              deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
      if (restored != expectedCount) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
      return restored;
    } catch (RuntimeException failure) {
      try {
        ExceptionUtils.checkSQLException(failure, Entity.EntityType.TAG, identifier.toString());
      } catch (EntityAlreadyExistsException duplicate) {
        throw new RecoveryConflictException(
            duplicate,
            RecoveryConflictReason.INCOMPLETE_GENERATION,
            "A live relation conflicts with tag deletion generation %s; manual repair is required",
            deletion.getDeletionId());
      } catch (IOException ignored) {
        // Preserve non-duplicate persistence failures.
      }
      throw failure;
    }
  }

  private static int restoreTagRoot(
      TagRecoveryMapper mapper,
      EntityDeletionPO deletion,
      TagPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreTag(
          deletion.getEntityId(),
          deletion.getMetalakeId(),
          deletion.getEntityName(),
          generation.getCurrentVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException failure) {
      try {
        ExceptionUtils.checkSQLException(failure, Entity.EntityType.TAG, identifier.toString());
      } catch (EntityAlreadyExistsException duplicate) {
        throw new RecoveryConflictException(
            duplicate,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live tag already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve non-duplicate persistence failures.
      }
      throw failure;
    }
  }

  private static TagEntity loadIdempotentlyRestoredTag(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    TagEntity live;
    try {
      live = getInstance().getTagByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Tag ID %s is active under a different logical tag",
          deletion.getEntityId());
    }
    return live;
  }

  private TagPO getTagPOByMetalakeAndName(String metalakeName, String tagName) {
    TagPO tagPO =
        SessionUtils.getWithoutCommit(
            TagMetaMapper.class,
            mapper -> mapper.selectTagMetaByMetalakeAndName(metalakeName, tagName));

    if (tagPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TAG.name().toLowerCase(),
          tagName);
    }
    return tagPO;
  }

  public Long getTagIdByTagName(Long metalakeId, String tagName) {
    TagPO tagPO =
        SessionUtils.getWithoutCommit(
            TagMetaMapper.class,
            mapper -> mapper.selectTagMetaByMetalakeIdAndName(metalakeId, tagName));

    if (tagPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.TAG.name().toLowerCase(),
          tagName);
    }
    return tagPO.getTagId();
  }

  private List<TagPO> getTagPOsByMetalakeAndNames(String metalakeName, List<String> tagNames) {
    return SessionUtils.getWithoutCommit(
        TagMetaMapper.class,
        mapper -> mapper.listTagPOsByMetalakeAndTagNames(metalakeName, tagNames));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetTagByIdentifier")
  public List<TagEntity> batchGetTagByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    String metalakeName = firstIdent.namespace().level(0);
    List<String> tagNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        TagMetaMapper.class,
        mapper -> {
          List<TagPO> tagPOs = mapper.batchSelectTagByIdentifier(metalakeName, tagNames);
          return POConverters.fromTagPOs(tagPOs, firstIdent.namespace());
        });
  }
}
