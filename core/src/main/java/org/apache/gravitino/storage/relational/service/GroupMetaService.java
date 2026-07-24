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
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.lockLiveMetalake;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.lockLiveMetalakeForRestore;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.sumCounts;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateDeletionSnapshot;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateGenerationCompleteness;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateLatestDeletion;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateNotExpired;
import static org.apache.gravitino.storage.relational.service.MetalakeScopedRecoveryServiceSupport.validateRestoreArguments;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.GroupAggregateTable;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper;
import org.apache.gravitino.storage.relational.mapper.IdentityRecoveryMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ExtendedGroupPO;
import org.apache.gravitino.storage.relational.po.GroupPO;
import org.apache.gravitino.storage.relational.po.GroupRoleRelPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/** The service class for group metadata. It provides the basic database operations for group. */
public class GroupMetaService {
  private static final GroupMetaService INSTANCE = new GroupMetaService();

  public static GroupMetaService getInstance() {
    return INSTANCE;
  }

  private GroupMetaService() {}

  private GroupPO getGroupPOByMetalakeIdAndName(Long metalakeId, String groupName) {
    GroupPO GroupPO =
        SessionUtils.getWithoutCommit(
            GroupMetaMapper.class,
            mapper -> mapper.selectGroupMetaByMetalakeIdAndName(metalakeId, groupName));

    if (GroupPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.GROUP.name().toLowerCase(),
          groupName);
    }
    return GroupPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getGroupIdByMetalakeIdAndName")
  public Long getGroupIdByMetalakeIdAndName(Long metalakeId, String groupName) {
    Long groupId =
        SessionUtils.getWithoutCommit(
            GroupMetaMapper.class,
            mapper -> mapper.selectGroupIdBySchemaIdAndName(metalakeId, groupName));

    if (groupId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.GROUP.name().toLowerCase(),
          groupName);
    }
    return groupId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getGroupByIdentifier")
  public GroupEntity getGroupByIdentifier(NameIdentifier identifier) {
    AuthorizationUtils.checkGroup(identifier);

    NameIdentifier metalakeIdent = NameIdentifier.of(NameIdentifierUtil.getMetalake(identifier));
    long metalakeId = EntityIdService.getEntityId(metalakeIdent, Entity.EntityType.METALAKE);
    GroupPO groupPO = getGroupPOByMetalakeIdAndName(metalakeId, identifier.name());
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByGroupId(groupPO.getGroupId());

    return POConverters.fromGroupPO(groupPO, rolePOs, identifier.namespace());
  }

  /** Lists independently deleted group roots under one live metalake. */
  public List<GroupPO> listDeletedGroupsByNamespace(Namespace namespace) {
    AuthorizationUtils.checkGroupNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listDeletedRootGroups(metalakeId));
  }

  /** Lists live group roots under one metalake for recovery collision checks. */
  public List<GroupPO> listLiveGroupPOsByNamespace(Namespace namespace) {
    AuthorizationUtils.checkGroupNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveGroups(metalakeId));
  }

  /** Lists globally live group roots matching immutable identifiers. */
  public List<GroupPO> listLiveGroupsByIds(List<Long> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveGroupsByIds(groupIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetGroupByIdentifier")
  public List<GroupEntity> batchGetGroupByIdentifier(List<NameIdentifier> identifiers) {
    if (identifiers == null || identifiers.isEmpty()) {
      return Collections.emptyList();
    }

    NameIdentifier firstIdent = identifiers.get(0);
    Namespace namespace = firstIdent.namespace();
    String metalake = NameIdentifierUtil.getMetalake(firstIdent);

    for (NameIdentifier identifier : identifiers) {
      AuthorizationUtils.checkGroup(identifier);
      Preconditions.checkArgument(
          identifier.namespace().equals(namespace),
          "All group identifiers must belong to the same namespace, expected %s but got %s",
          namespace,
          identifier.namespace());
    }

    long metalakeId =
        EntityIdService.getEntityId(NameIdentifier.of(metalake), Entity.EntityType.METALAKE);
    List<String> groupNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        GroupMetaMapper.class,
        mapper -> {
          List<ExtendedGroupPO> extendedPOs =
              mapper.listExtendedGroupPOsByMetalakeIdAndNames(metalakeId, groupNames);
          return extendedPOs.stream()
              .map(po -> POConverters.fromExtendedGroupPO(po, namespace))
              .collect(Collectors.toList());
        });
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupsByRoleIdent")
  public List<GroupEntity> listGroupsByRoleIdent(NameIdentifier roleIdent) {
    RoleEntity roleEntity = RoleMetaService.getInstance().getRoleByIdentifier(roleIdent);
    List<GroupPO> groupPOs =
        SessionUtils.getWithoutCommit(
            GroupMetaMapper.class, mapper -> mapper.listGroupsByRoleId(roleEntity.id()));
    return groupPOs.stream()
        .map(
            po ->
                POConverters.fromGroupPO(
                    po,
                    Collections.emptyList(),
                    AuthorizationUtils.ofGroupNamespace(roleIdent.namespace().level(0))))
        .collect(Collectors.toList());
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertGroup")
  public void insertGroup(GroupEntity groupEntity, boolean overwritten) throws IOException {
    try {
      AuthorizationUtils.checkGroup(groupEntity.nameIdentifier());

      NameIdentifier metalakeIdent =
          NameIdentifier.of(NameIdentifierUtil.getMetalake(groupEntity.nameIdentifier()));
      Long metalakeId = EntityIdService.getEntityId(metalakeIdent, Entity.EntityType.METALAKE);

      GroupPO.Builder builder = GroupPO.builder().withMetalakeId(metalakeId);
      GroupPO GroupPO = POConverters.initializeGroupPOWithVersion(groupEntity, builder);

      List<Long> roleIds = Optional.ofNullable(groupEntity.roleIds()).orElse(Lists.newArrayList());
      List<GroupRoleRelPO> groupRoleRelPOS =
          POConverters.initializeGroupRoleRelsPOWithVersion(groupEntity, roleIds);

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    if (!mapper
                        .selectDeletedGroupsForUpdate(List.of(GroupPO.getGroupId()))
                        .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Group ID %s belongs to a recoverable deletion; use metadata restore",
                          GroupPO.getGroupId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  GroupMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertGroupMetaOnDuplicateKeyUpdate(GroupPO);
                    } else {
                      mapper.insertGroupMeta(GroupPO);
                    }
                  }),
          () -> {
            SessionUtils.doWithoutCommit(
                GroupRoleRelMapper.class,
                mapper -> {
                  if (overwritten) {
                    mapper.softDeleteGroupRoleRelByGroupId(groupEntity.id());
                  }
                  if (!groupRoleRelPOS.isEmpty()) {
                    mapper.batchInsertGroupRoleRel(groupRoleRelPOS);
                  }
                });
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.GROUP, groupEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteGroup")
  public boolean deleteGroup(NameIdentifier identifier) {
    return deleteGroup(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one group aggregate and records its recoverable deletion generation. */
  public boolean deleteGroup(NameIdentifier identifier, long retentionMs) {
    return deleteGroup(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes live group-owned relations first and the group root last using one deletion token.
   */
  public boolean deleteGroup(NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    AuthorizationUtils.checkGroup(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                IdentityRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalake(mapper, metalakeId);
                  GroupPO group = mapper.lockLiveGroup(metalakeId, identifier.name());
                  if (group == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.GROUP.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  long deletedAt =
                      chooseDeletedAt(
                          mapper,
                          group.getGroupId(),
                          metalakeId,
                          group.getGroupName(),
                          group.getExternalId(),
                          requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.GROUP,
                              group.getGroupId(),
                              metalakeId,
                              null,
                              metalakeId,
                              group.getGroupName(),
                              group.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  long affected = 0L;
                  for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
                    int changed =
                        mapper.softDeleteGroupAggregateRows(
                            aggregateTable,
                            group.getGroupId(),
                            deletedAt,
                            deletion.getDeletionId());
                    if (aggregateTable == GroupAggregateTable.GROUP) {
                      deleted.set(changed);
                    }
                    affected = Math.addExact(affected, changed);
                  }
                  deletion.setAffectedRowCount(affected);
                  if (deleted.get() != 1
                      || generationRowCount(mapper, deletion) != affected
                      || mapper.countBrokenGroupGenerationReferences(
                              deletion.getEntityId(),
                              deletion.getDeletedAt(),
                              deletion.getDeletionId())
                          != 0) {
                    throw incompleteGeneration(deletion.getDeletionId());
                  }
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.GROUP.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /** Restores one exact group metadata deletion generation transactionally. */
  public GroupEntity restoreGroup(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    AuthorizationUtils.checkGroup(identifier);
    validateRestoreArguments(observed, restoredAt, restoreEtag, effectiveExpiresAt);
    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicReference<GroupEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                    validateLatestDeletion(
                        Entity.EntityType.GROUP, identifier, metalakeId, observed);
                    EntityDeletionPO actual = loadDeletion(observed.getDeletionId());
                    if (isCompletedRestoreReplay(
                        Entity.EntityType.GROUP,
                        identifier,
                        metalakeId,
                        observed,
                        actual,
                        restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredGroup(identifier, metalakeId, actual));
                      return;
                    }
                    validateDeletionSnapshot(
                        Entity.EntityType.GROUP, identifier, metalakeId, observed, actual);
                    validateNotExpired(actual, effectiveExpiresAt);
                    claimRestore(actual);

                    GroupPO generation =
                        mapper.selectGroupGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateGroupGeneration(actual, generation);
                    validateGroupOccupancy(mapper, actual, generation);
                    Map<GroupAggregateTable, Integer> counts = generationCounts(mapper, actual);
                    validateGenerationCompleteness(
                        actual,
                        counts.get(GroupAggregateTable.GROUP),
                        sumCounts(counts),
                        mapper.countBrokenGroupGenerationReferences(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));

                    for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
                      int changed =
                          restoreGroupGenerationRows(
                              mapper, aggregateTable, actual, generation, restoredAt, identifier);
                      if (changed != counts.get(aggregateTable)) {
                        throw incompleteGeneration(actual.getDeletionId());
                      }
                    }
                    completeRestore(actual, restoredAt, restoreEtag);
                    insertRestoreChange(identifier, Entity.EntityType.GROUP);
                    restored.set(getGroupByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(
          Entity.EntityType.GROUP, identifier, metalakeId, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredGroup(identifier, metalakeId, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored group must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded group generations. */
  public int purgeExpiredGroupDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.GROUP, legacyTimeline, limit);
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
                IdentityRecoveryMapper.class,
                mapper -> {
                  GroupPO generation =
                      mapper.selectGroupGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateGroupGeneration(actual, generation);
                  Map<GroupAggregateTable, Integer> counts = generationCounts(mapper, actual);
                  validateGenerationCompleteness(
                      actual,
                      counts.get(GroupAggregateTable.GROUP),
                      sumCounts(counts),
                      mapper.countBrokenGroupGenerationReferences(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));
                  for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
                    if (mapper.hardDeleteGroupGenerationRows(
                            aggregateTable, actual.getDeletedAt(), actual.getDeletionId())
                        != counts.get(aggregateTable)) {
                      throw incompleteGeneration(actual.getDeletionId());
                    }
                  }
                });
            completePurge(actual, purgedAt);
            purged.incrementAndGet();
          }
        });
    return purged.get();
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateGroup")
  public <E extends Entity & HasIdentifier> GroupEntity updateGroup(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    AuthorizationUtils.checkGroup(identifier);

    NameIdentifier metalakeIdent = NameIdentifier.of(NameIdentifierUtil.getMetalake(identifier));
    Long metalakeId = EntityIdService.getEntityId(metalakeIdent, Entity.EntityType.METALAKE);

    GroupPO oldGroupPO = getGroupPOByMetalakeIdAndName(metalakeId, identifier.name());
    List<RolePO> rolePOs =
        RoleMetaService.getInstance().listRolesByGroupId(oldGroupPO.getGroupId());
    GroupEntity oldGroupEntity =
        POConverters.fromGroupPO(oldGroupPO, rolePOs, identifier.namespace());

    GroupEntity newEntity = (GroupEntity) updater.apply((E) oldGroupEntity);
    Preconditions.checkArgument(
        Objects.equals(oldGroupEntity.id(), newEntity.id()),
        "The updated group entity id: %s should be same with the group entity id before: %s",
        newEntity.id(),
        oldGroupEntity.id());

    Set<Long> oldRoleIds =
        oldGroupEntity.roleIds() == null
            ? Sets.newHashSet()
            : Sets.newHashSet(oldGroupEntity.roleIds());
    Set<Long> newRoleIds =
        newEntity.roleIds() == null ? Sets.newHashSet() : Sets.newHashSet(newEntity.roleIds());

    Set<Long> insertRoleIds = Sets.difference(newRoleIds, oldRoleIds);
    Set<Long> deleteRoleIds = Sets.difference(oldRoleIds, newRoleIds);

    if (insertRoleIds.isEmpty() && deleteRoleIds.isEmpty()) {
      return newEntity;
    }
    try {
      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              SessionUtils.doWithoutCommit(
                  GroupMetaMapper.class,
                  mapper ->
                      mapper.updateGroupMeta(
                          POConverters.updateGroupPOWithVersion(oldGroupPO, newEntity),
                          oldGroupPO)),
          () -> {
            if (insertRoleIds.isEmpty()) {
              return;
            }
            SessionUtils.doWithoutCommit(
                GroupRoleRelMapper.class,
                mapper ->
                    mapper.batchInsertGroupRoleRel(
                        POConverters.initializeGroupRoleRelsPOWithVersion(
                            newEntity, Lists.newArrayList(insertRoleIds))));
          },
          () -> {
            if (deleteRoleIds.isEmpty()) {
              return;
            }
            SessionUtils.doWithoutCommit(
                GroupRoleRelMapper.class,
                mapper ->
                    mapper.softDeleteGroupRoleRelByGroupAndRoles(
                        newEntity.id(), Lists.newArrayList(deleteRoleIds)));
          },
          () ->
              SessionUtils.doWithoutCommit(
                  GroupMetaMapper.class,
                  mapper -> mapper.touchGroupUpdatedAt(oldGroupPO.getGroupId())));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.GROUP, newEntity.nameIdentifier().toString());
      throw re;
    }
    return newEntity;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupsByNamespace")
  public List<GroupEntity> listGroupsByNamespace(Namespace namespace, boolean allFields) {
    AuthorizationUtils.checkGroupNamespace(namespace);
    String metalakeName = namespace.level(0);

    if (allFields) {
      NameIdentifier metalakeIdent = NameIdentifier.of(metalakeName);
      long metalakeId = EntityIdService.getEntityId(metalakeIdent, Entity.EntityType.METALAKE);
      List<ExtendedGroupPO> groupPOs =
          SessionUtils.getWithoutCommit(
              GroupMetaMapper.class, mapper -> mapper.listExtendedGroupPOsByMetalakeId(metalakeId));
      return groupPOs.stream()
          .map(
              po ->
                  POConverters.fromExtendedGroupPO(
                      po, AuthorizationUtils.ofGroupNamespace(metalakeName)))
          .collect(Collectors.toList());
    } else {
      List<GroupPO> groupPOs =
          SessionUtils.getWithoutCommit(
              GroupMetaMapper.class, mapper -> mapper.listGroupPOsByMetalake(metalakeName));
      return groupPOs.stream()
          .map(
              po ->
                  POConverters.fromGroupPO(
                      po,
                      Collections.emptyList(),
                      AuthorizationUtils.ofGroupNamespace(metalakeName)))
          .collect(Collectors.toList());
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteGroupMetasByLegacyTimeline")
  public int deleteGroupMetasByLegacyTimeline(long legacyTimeline, int limit) {
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                IdentityRecoveryMapper.class,
                mapper -> {
                  List<Long> groupIds =
                      mapper.selectLegacyGroupRootsForUpdate(legacyTimeline, limit).stream()
                          .map(GroupPO::getGroupId)
                          .collect(Collectors.toList());
                  if (groupIds.isEmpty()) {
                    return;
                  }
                  for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
                    deleted.addAndGet(
                        mapper.hardDeleteLegacyGroupAggregateRows(
                            aggregateTable, groupIds, legacyTimeline));
                  }
                }));
    return deleted.get();
  }

  private static long chooseDeletedAt(
      IdentityRecoveryMapper mapper,
      long groupId,
      long metalakeId,
      String groupName,
      @Nullable String externalId,
      long requestedDeletedAt) {
    Long newest = mapper.selectNewestGroupDeletedAt(groupId, metalakeId, groupName, externalId);
    return newest == null || newest < requestedDeletedAt
        ? requestedDeletedAt
        : Math.addExact(newest, 1L);
  }

  private static long generationRowCount(IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    long count = 0L;
    for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
      count =
          Math.addExact(
              count,
              mapper.countGroupGenerationRows(
                  aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return count;
  }

  private static Map<GroupAggregateTable, Integer> generationCounts(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<GroupAggregateTable, Integer> counts = new EnumMap<>(GroupAggregateTable.class);
    for (GroupAggregateTable aggregateTable : GroupAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countGroupGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static void validateGroupGeneration(
      EntityDeletionPO deletion, @Nullable GroupPO generation) {
    if (generation == null
        || !Objects.equals(generation.getGroupId(), deletion.getEntityId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.getGroupName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw incompleteGeneration(deletion.getDeletionId());
    }
  }

  private static void validateGroupOccupancy(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion, GroupPO generation) {
    List<GroupPO> occupants =
        mapper.listLiveGroupOccupantsForUpdate(
            deletion.getMetalakeId(), deletion.getEntityName(), generation.getExternalId());
    for (GroupPO occupant : occupants) {
      if (Objects.equals(occupant.getGroupId(), deletion.getEntityId())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.ENTITY_ID_REUSED,
            "Group ID %s is already active under a different logical group",
            deletion.getEntityId());
      }
      if (Objects.equals(occupant.getGroupName(), deletion.getEntityName())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live group already occupies name %s",
            deletion.getEntityName());
      }
      if (generation.getExternalId() != null
          && Objects.equals(occupant.getExternalId(), generation.getExternalId())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
            "A live group already occupies external ID %s",
            generation.getExternalId());
      }
    }
  }

  private static int restoreGroupGenerationRows(
      IdentityRecoveryMapper mapper,
      GroupAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      GroupPO generation,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreGroupGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException failure) {
      try {
        ExceptionUtils.checkSQLException(failure, Entity.EntityType.GROUP, identifier.toString());
      } catch (EntityAlreadyExistsException duplicate) {
        validateGroupOccupancy(mapper, deletion, generation);
        throw new RecoveryConflictException(
            duplicate,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live group conflicts with restore target %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve non-duplicate persistence failures.
      }
      throw failure;
    }
  }

  private static GroupEntity loadIdempotentlyRestoredGroup(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    GroupEntity live;
    try {
      live = getInstance().getGroupByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw MetalakeScopedRecoveryServiceSupport.tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Group ID %s is active under a different logical group",
          deletion.getEntityId());
    }
    return live;
  }

  private GroupPO getGroupPOByMetalakeNameAndExternalId(String metalakeName, String externalId) {
    GroupPO groupPO =
        SessionUtils.getWithoutCommit(
            GroupMetaMapper.class,
            mapper -> mapper.selectGroupMetaByMetalakeNameAndExternalId(metalakeName, externalId));

    if (groupPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.GROUP.name().toLowerCase(),
          externalId);
    }
    return groupPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getGroupByExternalId")
  public GroupEntity getGroupByExternalId(NameIdentifier ident) {
    AuthorizationUtils.checkGroupExternalId(ident);
    String metalake = ident.namespace().level(0);
    String externalId = ident.name();
    GroupPO groupPO = getGroupPOByMetalakeNameAndExternalId(metalake, externalId);
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByGroupId(groupPO.getGroupId());
    return POConverters.fromGroupPO(
        groupPO, rolePOs, AuthorizationUtils.ofGroupNamespace(metalake));
  }
}
