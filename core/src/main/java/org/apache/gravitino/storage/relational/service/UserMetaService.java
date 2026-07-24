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
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.IdentityRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.UserAggregateTable;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ExtendedUserPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.PrincipalUtils;

/** The service class for user metadata. It provides the basic database operations for user. */
public class UserMetaService {
  private static final UserMetaService INSTANCE = new UserMetaService();

  public static UserMetaService getInstance() {
    return INSTANCE;
  }

  private UserMetaService() {}

  private UserPO getUserPOByMetalakeIdAndName(Long metalakeId, String userName) {
    UserPO userPO =
        SessionUtils.getWithoutCommit(
            UserMetaMapper.class,
            mapper -> mapper.selectUserMetaByMetalakeIdAndName(metalakeId, userName));

    if (userPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.USER.name().toLowerCase(),
          userName);
    }
    return userPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getUserIdByMetalakeIdAndName")
  public Long getUserIdByMetalakeIdAndName(Long metalakeId, String userName) {
    Long userId =
        SessionUtils.getWithoutCommit(
            UserMetaMapper.class,
            mapper -> mapper.selectUserIdByMetalakeIdAndName(metalakeId, userName));

    if (userId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.USER.name().toLowerCase(),
          userName);
    }
    return userId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getUserByIdentifier")
  public UserEntity getUserByIdentifier(NameIdentifier identifier) {
    AuthorizationUtils.checkUser(identifier);

    Long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    UserPO userPO = getUserPOByMetalakeIdAndName(metalakeId, identifier.name());
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByUserId(userPO.getUserId());

    return POConverters.fromUserPO(userPO, rolePOs, identifier.namespace());
  }

  /** Lists independently deleted user roots under one live metalake. */
  public List<UserPO> listDeletedUsersByNamespace(Namespace namespace) {
    AuthorizationUtils.checkUserNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listDeletedRootUsers(metalakeId));
  }

  /** Lists live user roots under one metalake for recovery collision checks. */
  public List<UserPO> listLiveUserPOsByNamespace(Namespace namespace) {
    AuthorizationUtils.checkUserNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveUsers(metalakeId));
  }

  /** Lists globally live user roots matching immutable identifiers. */
  public List<UserPO> listLiveUsersByIds(List<Long> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveUsersByIds(userIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listUsersByRoleIdent")
  public List<UserEntity> listUsersByRoleIdent(NameIdentifier roleIdent) {
    RoleEntity roleEntity = RoleMetaService.getInstance().getRoleByIdentifier(roleIdent);
    List<UserPO> userPOs =
        SessionUtils.getWithoutCommit(
            UserMetaMapper.class, mapper -> mapper.listUsersByRoleId(roleEntity.id()));
    return userPOs.stream()
        .map(
            po ->
                POConverters.fromUserPO(
                    po,
                    Collections.emptyList(),
                    AuthorizationUtils.ofUserNamespace(roleIdent.namespace().level(0))))
        .collect(Collectors.toList());
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertUser")
  public void insertUser(UserEntity userEntity, boolean overwritten) throws IOException {
    try {
      AuthorizationUtils.checkUser(userEntity.nameIdentifier());

      Long metalakeId =
          MetalakeMetaService.getInstance().getMetalakeIdByName(userEntity.namespace().level(0));
      UserPO.Builder builder = UserPO.builder().withMetalakeId(metalakeId);
      UserPO userPO = POConverters.initializeUserPOWithVersion(userEntity, builder);

      List<Long> roleIds = Optional.ofNullable(userEntity.roleIds()).orElse(Lists.newArrayList());
      List<UserRoleRelPO> userRoleRelPOs =
          POConverters.initializeUserRoleRelsPOWithVersion(userEntity, roleIds);

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(metalakeId),
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    if (!mapper
                        .selectDeletedUsersForUpdate(List.of(userPO.getUserId()))
                        .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "User ID %s belongs to a recoverable deletion; use metadata restore",
                          userPO.getUserId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  UserMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertUserMetaOnDuplicateKeyUpdate(userPO);
                    } else {
                      mapper.insertUserMeta(userPO);
                    }
                  }),
          () -> {
            SessionUtils.doWithoutCommit(
                UserRoleRelMapper.class,
                mapper -> {
                  if (overwritten) {
                    mapper.softDeleteUserRoleRelByUserId(userEntity.id());
                  }
                  if (!userRoleRelPOs.isEmpty()) {
                    mapper.batchInsertUserRoleRel(userRoleRelPOs);
                  }
                });
          });
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.USER, userEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteUser")
  public boolean deleteUser(NameIdentifier identifier) {
    return deleteUser(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one user aggregate and records its recoverable deletion generation. */
  public boolean deleteUser(NameIdentifier identifier, long retentionMs) {
    return deleteUser(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes live user-owned relations first and the user root last using one deletion token.
   */
  public boolean deleteUser(NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    AuthorizationUtils.checkUser(identifier);
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
                  UserPO user = mapper.lockLiveUser(metalakeId, identifier.name());
                  if (user == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.USER.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }

                  long deletedAt =
                      chooseDeletedAt(
                          mapper,
                          user.getUserId(),
                          metalakeId,
                          user.getUserName(),
                          user.getExternalId(),
                          requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.USER,
                              user.getUserId(),
                              metalakeId,
                              null,
                              metalakeId,
                              user.getUserName(),
                              user.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  long affected = 0L;
                  for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
                    int changed =
                        mapper.softDeleteUserAggregateRows(
                            aggregateTable, user.getUserId(), deletedAt, deletion.getDeletionId());
                    if (aggregateTable == UserAggregateTable.USER) {
                      deleted.set(changed);
                    }
                    affected = Math.addExact(affected, changed);
                  }
                  deletion.setAffectedRowCount(affected);
                  if (deleted.get() != 1
                      || generationRowCount(mapper, deletion) != affected
                      || mapper.countBrokenUserGenerationReferences(
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
                              Entity.EntityType.USER.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /** Restores one exact user metadata deletion generation transactionally. */
  public UserEntity restoreUser(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    AuthorizationUtils.checkUser(identifier);
    validateRestoreArguments(observed, restoredAt, restoreEtag, effectiveExpiresAt);

    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicReference<UserEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                    validateLatestDeletion(
                        Entity.EntityType.USER, identifier, metalakeId, observed);
                    EntityDeletionPO actual = loadDeletion(observed.getDeletionId());
                    if (isCompletedRestoreReplay(
                        Entity.EntityType.USER,
                        identifier,
                        metalakeId,
                        observed,
                        actual,
                        restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredUser(identifier, metalakeId, actual));
                      return;
                    }
                    validateDeletionSnapshot(
                        Entity.EntityType.USER, identifier, metalakeId, observed, actual);
                    validateNotExpired(actual, effectiveExpiresAt);
                    claimRestore(actual);

                    UserPO generation =
                        mapper.selectUserGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateUserGeneration(actual, generation);
                    validateUserOccupancy(mapper, actual, generation);
                    Map<UserAggregateTable, Integer> counts = generationCounts(mapper, actual);
                    validateGenerationCompleteness(
                        actual,
                        counts.get(UserAggregateTable.USER),
                        sumCounts(counts),
                        mapper.countBrokenUserGenerationReferences(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));

                    for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
                      int changed =
                          restoreUserGenerationRows(
                              mapper, aggregateTable, actual, generation, restoredAt, identifier);
                      if (changed != counts.get(aggregateTable)) {
                        throw incompleteGeneration(actual.getDeletionId());
                      }
                    }
                    completeRestore(actual, restoredAt, restoreEtag);
                    insertRestoreChange(identifier, Entity.EntityType.USER);
                    restored.set(getUserByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(
          Entity.EntityType.USER, identifier, metalakeId, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredUser(identifier, metalakeId, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored user must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded user generations. */
  public int purgeExpiredUserDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.USER, legacyTimeline, limit);
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
                  UserPO generation =
                      mapper.selectUserGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateUserGeneration(actual, generation);
                  Map<UserAggregateTable, Integer> counts = generationCounts(mapper, actual);
                  validateGenerationCompleteness(
                      actual,
                      counts.get(UserAggregateTable.USER),
                      sumCounts(counts),
                      mapper.countBrokenUserGenerationReferences(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));
                  for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
                    if (mapper.hardDeleteUserGenerationRows(
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

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateUser")
  public <E extends Entity & HasIdentifier> UserEntity updateUser(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    AuthorizationUtils.checkUser(identifier);

    Long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    UserPO oldUserPO = getUserPOByMetalakeIdAndName(metalakeId, identifier.name());
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByUserId(oldUserPO.getUserId());
    UserEntity oldUserEntity = POConverters.fromUserPO(oldUserPO, rolePOs, identifier.namespace());

    UserEntity newEntity = (UserEntity) updater.apply((E) oldUserEntity);
    Preconditions.checkArgument(
        Objects.equals(oldUserEntity.id(), newEntity.id()),
        "The updated user entity id: %s should be same with the user entity id before: %s",
        newEntity.id(),
        oldUserEntity.id());

    Set<Long> oldRoleIds =
        oldUserEntity.roleIds() == null
            ? Sets.newHashSet()
            : Sets.newHashSet(oldUserEntity.roleIds());
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
                  UserMetaMapper.class,
                  mapper ->
                      mapper.updateUserMeta(
                          POConverters.updateUserPOWithVersion(oldUserPO, newEntity), oldUserPO)),
          () -> {
            if (insertRoleIds.isEmpty()) {
              return;
            }
            SessionUtils.doWithoutCommit(
                UserRoleRelMapper.class,
                mapper ->
                    mapper.batchInsertUserRoleRel(
                        POConverters.initializeUserRoleRelsPOWithVersion(
                            newEntity, Lists.newArrayList(insertRoleIds))));
          },
          () -> {
            if (deleteRoleIds.isEmpty()) {
              return;
            }
            SessionUtils.doWithoutCommit(
                UserRoleRelMapper.class,
                mapper ->
                    mapper.softDeleteUserRoleRelByUserAndRoles(
                        newEntity.id(), Lists.newArrayList(deleteRoleIds)));
          },
          () ->
              SessionUtils.doWithoutCommit(
                  UserMetaMapper.class,
                  mapper -> mapper.touchUserUpdatedAt(oldUserPO.getUserId())));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.USER, newEntity.nameIdentifier().toString());
      throw re;
    }
    return newEntity;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listUsersByNamespace")
  public List<UserEntity> listUsersByNamespace(Namespace namespace, boolean allFields) {
    AuthorizationUtils.checkUserNamespace(namespace);
    String metalakeName = namespace.level(0);

    if (allFields) {
      Long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);
      List<ExtendedUserPO> userPOs =
          SessionUtils.getWithoutCommit(
              UserMetaMapper.class, mapper -> mapper.listExtendedUserPOsByMetalakeId(metalakeId));
      return userPOs.stream()
          .map(
              po ->
                  POConverters.fromExtendedUserPO(
                      po, AuthorizationUtils.ofUserNamespace(metalakeName)))
          .collect(Collectors.toList());
    } else {
      List<UserPO> userPOs =
          SessionUtils.getWithoutCommit(
              UserMetaMapper.class, mapper -> mapper.listUserPOsByMetalake(metalakeName));
      return userPOs.stream()
          .map(
              po ->
                  POConverters.fromUserPO(
                      po,
                      Collections.emptyList(),
                      AuthorizationUtils.ofUserNamespace(metalakeName)))
          .collect(Collectors.toList());
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteUserMetasByLegacyTimeline")
  public int deleteUserMetasByLegacyTimeline(long legacyTimeline, int limit) {
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                IdentityRecoveryMapper.class,
                mapper -> {
                  List<Long> userIds =
                      mapper.selectLegacyUserRootsForUpdate(legacyTimeline, limit).stream()
                          .map(UserPO::getUserId)
                          .collect(Collectors.toList());
                  if (userIds.isEmpty()) {
                    return;
                  }
                  for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
                    deleted.addAndGet(
                        mapper.hardDeleteLegacyUserAggregateRows(
                            aggregateTable, userIds, legacyTimeline));
                  }
                }));
    return deleted.get();
  }

  private static long chooseDeletedAt(
      IdentityRecoveryMapper mapper,
      long userId,
      long metalakeId,
      String userName,
      @Nullable String externalId,
      long requestedDeletedAt) {
    Long newest = mapper.selectNewestUserDeletedAt(userId, metalakeId, userName, externalId);
    return newest == null || newest < requestedDeletedAt
        ? requestedDeletedAt
        : Math.addExact(newest, 1L);
  }

  private static long generationRowCount(IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    long count = 0L;
    for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
      count =
          Math.addExact(
              count,
              mapper.countUserGenerationRows(
                  aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return count;
  }

  private static Map<UserAggregateTable, Integer> generationCounts(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<UserAggregateTable, Integer> counts = new EnumMap<>(UserAggregateTable.class);
    for (UserAggregateTable aggregateTable : UserAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countUserGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static void validateUserGeneration(
      EntityDeletionPO deletion, @Nullable UserPO generation) {
    if (generation == null
        || !Objects.equals(generation.getUserId(), deletion.getEntityId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.getUserName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw incompleteGeneration(deletion.getDeletionId());
    }
  }

  private static void validateUserOccupancy(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion, UserPO generation) {
    List<UserPO> occupants =
        mapper.listLiveUserOccupantsForUpdate(
            deletion.getMetalakeId(), deletion.getEntityName(), generation.getExternalId());
    for (UserPO occupant : occupants) {
      if (Objects.equals(occupant.getUserId(), deletion.getEntityId())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.ENTITY_ID_REUSED,
            "User ID %s is already active under a different logical user",
            deletion.getEntityId());
      }
      if (Objects.equals(occupant.getUserName(), deletion.getEntityName())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live user already occupies name %s",
            deletion.getEntityName());
      }
      if (generation.getExternalId() != null
          && Objects.equals(occupant.getExternalId(), generation.getExternalId())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.EXTERNAL_ID_OCCUPIED,
            "A live user already occupies external ID %s",
            generation.getExternalId());
      }
    }
  }

  private static int restoreUserGenerationRows(
      IdentityRecoveryMapper mapper,
      UserAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      UserPO generation,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreUserGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException failure) {
      try {
        ExceptionUtils.checkSQLException(failure, Entity.EntityType.USER, identifier.toString());
      } catch (EntityAlreadyExistsException duplicate) {
        validateUserOccupancy(mapper, deletion, generation);
        throw new RecoveryConflictException(
            duplicate,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live user conflicts with restore target %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve non-duplicate persistence failures.
      }
      throw failure;
    }
  }

  private static UserEntity loadIdempotentlyRestoredUser(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    UserEntity live;
    try {
      live = getInstance().getUserByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw MetalakeScopedRecoveryServiceSupport.tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "User ID %s is active under a different logical user",
          deletion.getEntityId());
    }
    return live;
  }

  private UserPO getUserPOByMetalakeNameAndExternalId(String metalakeName, String externalId) {
    UserPO userPO =
        SessionUtils.getWithoutCommit(
            UserMetaMapper.class,
            mapper -> mapper.selectUserMetaByMetalakeNameAndExternalId(metalakeName, externalId));

    if (userPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.USER.name().toLowerCase(),
          externalId);
    }
    return userPO;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getUserByExternalId")
  public UserEntity getUserByExternalId(NameIdentifier ident) {
    AuthorizationUtils.checkUserExternalId(ident);
    String metalake = ident.namespace().level(0);
    String externalId = ident.name();
    Namespace userNamespace = AuthorizationUtils.ofUserNamespace(metalake);
    UserPO userPO = getUserPOByMetalakeNameAndExternalId(metalake, externalId);
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByUserId(userPO.getUserId());
    return POConverters.fromUserPO(userPO, rolePOs, userNamespace);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateUserByExternalId")
  public <E extends Entity & HasIdentifier> UserEntity updateUserByExternalId(
      NameIdentifier ident, Function<E, E> updater) throws IOException {
    AuthorizationUtils.checkUserExternalId(ident);
    String metalake = ident.namespace().level(0);
    String externalId = ident.name();
    Namespace userNamespace = AuthorizationUtils.ofUserNamespace(metalake);
    UserPO oldUserPO = getUserPOByMetalakeNameAndExternalId(metalake, externalId);
    List<RolePO> rolePOs = RoleMetaService.getInstance().listRolesByUserId(oldUserPO.getUserId());
    UserEntity oldEntity = POConverters.fromUserPO(oldUserPO, rolePOs, userNamespace);
    UserEntity newEntity = (UserEntity) updater.apply((E) oldEntity);
    Preconditions.checkArgument(
        Objects.equals(oldEntity.id(), newEntity.id()),
        "The updated user entity id: %s should be same with the user entity id before: %s",
        newEntity.id(),
        oldEntity.id());

    try {
      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetalakeId(oldUserPO.getMetalakeId()),
          () ->
              SessionUtils.doWithoutCommit(
                  UserMetaMapper.class,
                  mapper ->
                      mapper.updateUserMetaByExternalId(
                          POConverters.updateUserPOWithVersion(oldUserPO, newEntity), oldUserPO)),
          () ->
              SessionUtils.doWithoutCommit(
                  UserMetaMapper.class,
                  mapper -> mapper.touchUserUpdatedAt(oldUserPO.getUserId())));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.USER, newEntity.nameIdentifier().toString());
      throw re;
    }
    return newEntity;
  }
}
