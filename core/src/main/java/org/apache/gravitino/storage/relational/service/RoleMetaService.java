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
 * Unless required by applicable law or agreed in writing,
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
import java.util.HashSet;
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
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.IdentityRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.RoleAggregateTable;
import org.apache.gravitino.storage.relational.mapper.RoleMetaMapper;
import org.apache.gravitino.storage.relational.mapper.SecurableObjectMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The service class for role metadata. It provides the basic database operations for role. */
public class RoleMetaService {
  private static final Logger LOG = LoggerFactory.getLogger(RoleMetaService.class);
  private static final RoleMetaService INSTANCE = new RoleMetaService();

  public static RoleMetaService getInstance() {
    return INSTANCE;
  }

  private RoleMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getRoleIdByMetalakeIdAndName")
  public Long getRoleIdByMetalakeIdAndName(Long metalakeId, String roleName) {
    Long roleId =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class,
            mapper -> mapper.selectRoleIdByMetalakeIdAndName(metalakeId, roleName));

    if (roleId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.ROLE.name().toLowerCase(),
          roleName);
    }
    return roleId;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesByUserId")
  public List<RolePO> listRolesByUserId(Long userId) {
    return SessionUtils.getWithoutCommit(
        RoleMetaMapper.class, mapper -> mapper.listRolesByUserId(userId));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesByUserIdent")
  public List<RoleEntity> listRolesByUserIdent(NameIdentifier userIdent) {
    UserEntity user = UserMetaService.getInstance().getUserByIdentifier(userIdent);
    String metalake = NameIdentifierUtil.getMetalake(userIdent);
    List<RolePO> rolePOs = listRolesByUserId(user.id());
    return rolePOs.stream()
        .map(
            po ->
                POConverters.fromRolePO(
                    po, Collections.emptyList(), AuthorizationUtils.ofRoleNamespace(metalake)))
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesByMetadataObject")
  public List<RoleEntity> listRolesByMetadataObject(
      NameIdentifier metadataObjectIdent, Entity.EntityType metadataObjectType, boolean allFields) {
    String metalake = NameIdentifierUtil.getMetalake(metadataObjectIdent);
    MetadataObject metadataObject =
        NameIdentifierUtil.toMetadataObject(metadataObjectIdent, metadataObjectType);
    long metadataObjectId = EntityIdService.getEntityId(metadataObjectIdent, metadataObjectType);
    List<RolePO> rolePOs =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class,
            mapper ->
                mapper.listRolesByMetadataObjectIdAndType(
                    metadataObjectId, metadataObject.type().name()));
    return rolePOs.stream()
        .map(
            po -> {
              if (allFields) {
                return POConverters.fromRolePO(
                    po, listSecurableObjects(po), AuthorizationUtils.ofRoleNamespace(metalake));
              } else {
                return POConverters.fromRolePO(
                    po, Collections.emptyList(), AuthorizationUtils.ofRoleNamespace(metalake));
              }
            })
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesByGroupId")
  public List<RolePO> listRolesByGroupId(Long groupId) {
    return SessionUtils.getWithoutCommit(
        RoleMetaMapper.class, mapper -> mapper.listRolesByGroupId(groupId));
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertRole")
  public void insertRole(RoleEntity roleEntity, boolean overwritten) throws IOException {
    try {
      AuthorizationUtils.checkRole(roleEntity.nameIdentifier());

      String metalake = NameIdentifierUtil.getMetalake(roleEntity.nameIdentifier());
      Long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalake);
      RolePO.Builder builder = RolePO.builder().withMetalakeId(metalakeId);
      RolePO rolePO = POConverters.initializeRolePOWithVersion(roleEntity, builder);
      List<SecurableObjectPO> securableObjectPOs = Lists.newArrayList();
      List<Long> metalakeIds = Lists.newArrayList();
      metalakeIds.add(metalakeId);
      List<Long> catalogIds = Lists.newArrayList();
      List<Long> schemaIds = Lists.newArrayList();
      for (SecurableObject object : roleEntity.securableObjects()) {
        SecurableObjectPO.Builder objectBuilder =
            POConverters.initializeSecurablePOBuilderWithVersion(
                roleEntity.id(), object, getType(object));
        NameIdentifier identifier = MetadataObjectUtil.toEntityIdent(metalake, object);
        Entity.EntityType entityType = MetadataObjectUtil.toEntityType(object.type());
        objectBuilder.withMetadataObjectId(EntityIdService.getEntityId(identifier, entityType));
        metalakeIds.add(MetadataMutationLock.metalakeId(identifier, entityType));
        catalogIds.add(MetadataMutationLock.catalogId(identifier, entityType));
        schemaIds.add(MetadataMutationLock.schemaId(identifier, entityType));
        securableObjectPOs.add(objectBuilder.build());
      }

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetadataIds(metalakeIds, catalogIds, schemaIds),
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    if (!mapper
                        .selectDeletedRolesForUpdate(List.of(rolePO.getRoleId()))
                        .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Role ID %s belongs to a recoverable deletion; use metadata restore",
                          rolePO.getRoleId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  SecurableObjectMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.softDeleteSecurableObjectsByRoleId(rolePO.getRoleId());
                    }
                    if (!securableObjectPOs.isEmpty()) {
                      mapper.batchInsertSecurableObjects(securableObjectPOs);
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  RoleMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertRoleMetaOnDuplicateKeyUpdate(rolePO);
                    } else {
                      mapper.insertRoleMeta(rolePO);
                    }
                  }));

    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.ROLE, roleEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "updateRole")
  public <E extends Entity & HasIdentifier> RoleEntity updateRole(
      NameIdentifier identifier, Function<E, E> updater) throws IOException {
    AuthorizationUtils.checkRole(identifier);

    try {
      String metalake = NameIdentifierUtil.getMetalake(identifier);
      Long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalake);

      RolePO rolePO = getRolePOByMetalakeIdAndName(metalakeId, identifier.name());
      RoleEntity oldRoleEntity =
          POConverters.fromRolePO(
              rolePO, listSecurableObjects(rolePO), AuthorizationUtils.ofRoleNamespace(metalake));

      RoleEntity newRoleEntity = (RoleEntity) updater.apply((E) oldRoleEntity);

      Preconditions.checkArgument(
          Objects.equals(oldRoleEntity.id(), newRoleEntity.id()),
          "The updated role entity id: %s should be same with the role entity id before: %s",
          newRoleEntity.id(),
          oldRoleEntity.id());

      Set<SecurableObject> oldObjects = new HashSet<>(oldRoleEntity.securableObjects());
      Set<SecurableObject> newObjects = new HashSet<>(newRoleEntity.securableObjects());
      Set<SecurableObject> insertObjects = Sets.difference(newObjects, oldObjects);
      Set<SecurableObject> deleteObjects = Sets.difference(oldObjects, newObjects);

      if (insertObjects.isEmpty() && deleteObjects.isEmpty()) {
        return newRoleEntity;
      }

      List<SecurableObjectPO> deleteSecurableObjectPOs =
          toSecurableObjectPOs(deleteObjects, oldRoleEntity, metalake);

      List<SecurableObjectPO> insertSecurableObjectPOs =
          toSecurableObjectPOs(insertObjects, oldRoleEntity, metalake);
      List<Long> metalakeIds = Lists.newArrayList();
      metalakeIds.add(metalakeId);
      List<Long> catalogIds = Lists.newArrayList();
      List<Long> schemaIds = Lists.newArrayList();
      Sets.union(insertObjects, deleteObjects)
          .forEach(
              object -> {
                NameIdentifier objectIdentifier =
                    MetadataObjectUtil.toEntityIdent(metalake, object);
                Entity.EntityType objectType = MetadataObjectUtil.toEntityType(object.type());
                metalakeIds.add(MetadataMutationLock.metalakeId(objectIdentifier, objectType));
                catalogIds.add(MetadataMutationLock.catalogId(objectIdentifier, objectType));
                schemaIds.add(MetadataMutationLock.schemaId(objectIdentifier, objectType));
              });

      SessionUtils.doMultipleWithCommit(
          () -> MetadataMutationLock.lockMetadataIds(metalakeIds, catalogIds, schemaIds),
          () ->
              SessionUtils.doWithoutCommit(
                  RoleMetaMapper.class,
                  mapper ->
                      mapper.updateRoleMeta(
                          POConverters.updateRolePOWithVersion(rolePO, newRoleEntity), rolePO)),
          () -> {
            if (deleteSecurableObjectPOs.isEmpty()) {
              return;
            }

            SessionUtils.doWithoutCommit(
                SecurableObjectMapper.class,
                mapper -> mapper.batchSoftDeleteSecurableObjects(deleteSecurableObjectPOs));
          },
          () -> {
            if (insertSecurableObjectPOs.isEmpty()) {
              return;
            }

            SessionUtils.doWithoutCommit(
                SecurableObjectMapper.class,
                mapper -> mapper.batchInsertSecurableObjects(insertSecurableObjectPOs));
          },
          () ->
              SessionUtils.doWithoutCommit(
                  RoleMetaMapper.class, mapper -> mapper.touchRoleUpdatedAt(rolePO.getRoleId())));

      return newRoleEntity;
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(re, Entity.EntityType.ROLE, identifier.toString());
      throw re;
    }
  }

  private List<SecurableObjectPO> toSecurableObjectPOs(
      Set<SecurableObject> deleteObjects, RoleEntity oldRoleEntity, String metalake) {
    List<SecurableObjectPO> securableObjectPOs = Lists.newArrayList();
    for (SecurableObject object : deleteObjects) {
      SecurableObjectPO.Builder objectBuilder =
          POConverters.initializeSecurablePOBuilderWithVersion(
              oldRoleEntity.id(), object, getType(object));
      NameIdentifier nameIdentifier = MetadataObjectUtil.toEntityIdent(metalake, object);
      Entity.EntityType entityType = MetadataObjectUtil.toEntityType(object.type());

      objectBuilder.withMetadataObjectId(EntityIdService.getEntityId(nameIdentifier, entityType));
      securableObjectPOs.add(objectBuilder.build());
    }
    return securableObjectPOs;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getRoleByIdentifier")
  public RoleEntity getRoleByIdentifier(NameIdentifier identifier) {
    AuthorizationUtils.checkRole(identifier);

    Long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    RolePO rolePO = getRolePOByMetalakeIdAndName(metalakeId, identifier.name());

    List<SecurableObject> securableObjects = listSecurableObjects(rolePO);

    return POConverters.fromRolePO(rolePO, securableObjects, identifier.namespace());
  }

  /** Lists independently deleted role roots under one live metalake. */
  public List<RolePO> listDeletedRolesByNamespace(Namespace namespace) {
    AuthorizationUtils.checkRoleNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listDeletedRootRoles(metalakeId));
  }

  /** Lists live role roots under one metalake for recovery collision checks. */
  public List<RolePO> listLiveRolePOsByNamespace(Namespace namespace) {
    AuthorizationUtils.checkRoleNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveRoles(metalakeId));
  }

  /** Lists globally live role roots matching immutable identifiers. */
  public List<RolePO> listLiveRolesByIds(List<Long> roleIds) {
    if (roleIds.isEmpty()) {
      return List.of();
    }
    return SessionUtils.getWithoutCommit(
        IdentityRecoveryMapper.class, mapper -> mapper.listLiveRolesByIds(roleIds));
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "deleteRole")
  public boolean deleteRole(NameIdentifier identifier) {
    return deleteRole(identifier, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /** Soft-deletes one role aggregate and records its recoverable deletion generation. */
  public boolean deleteRole(NameIdentifier identifier, long retentionMs) {
    return deleteRole(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /** Soft-deletes live role relations first and the role root last using one deletion token. */
  public boolean deleteRole(NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    AuthorizationUtils.checkRole(identifier);
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
                  RolePO role = mapper.lockLiveRole(metalakeId, identifier.name());
                  if (role == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.ROLE.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  long deletedAt =
                      chooseDeletedAt(
                          mapper,
                          role.getRoleId(),
                          metalakeId,
                          role.getRoleName(),
                          requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.ROLE,
                              role.getRoleId(),
                              metalakeId,
                              null,
                              metalakeId,
                              role.getRoleName(),
                              role.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  long affected = 0L;
                  for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
                    int changed =
                        mapper.softDeleteRoleAggregateRows(
                            aggregateTable, role.getRoleId(), deletedAt, deletion.getDeletionId());
                    if (aggregateTable == RoleAggregateTable.ROLE) {
                      deleted.set(changed);
                    }
                    affected = Math.addExact(affected, changed);
                  }
                  deletion.setAffectedRowCount(affected);
                  if (deleted.get() != 1
                      || generationRowCount(mapper, deletion) != affected
                      || mapper.countBrokenRoleGenerationReferences(
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
                              Entity.EntityType.ROLE.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /** Restores one exact role metadata deletion generation transactionally. */
  public RoleEntity restoreRole(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    AuthorizationUtils.checkRole(identifier);
    validateRestoreArguments(observed, restoredAt, restoreEtag, effectiveExpiresAt);
    long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    AtomicReference<RoleEntity> restored = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  IdentityRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                    validateLatestDeletion(
                        Entity.EntityType.ROLE, identifier, metalakeId, observed);
                    EntityDeletionPO actual = loadDeletion(observed.getDeletionId());
                    if (isCompletedRestoreReplay(
                        Entity.EntityType.ROLE,
                        identifier,
                        metalakeId,
                        observed,
                        actual,
                        restoreEtag)) {
                      restored.set(loadIdempotentlyRestoredRole(identifier, metalakeId, actual));
                      return;
                    }
                    validateDeletionSnapshot(
                        Entity.EntityType.ROLE, identifier, metalakeId, observed, actual);
                    validateNotExpired(actual, effectiveExpiresAt);
                    claimRestore(actual);

                    RolePO generation =
                        mapper.selectRoleGenerationForUpdate(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                    validateRoleGeneration(actual, generation);
                    validateRoleOccupancy(mapper, actual);
                    Map<RoleAggregateTable, Integer> counts = generationCounts(mapper, actual);
                    validateGenerationCompleteness(
                        actual,
                        counts.get(RoleAggregateTable.ROLE),
                        sumCounts(counts),
                        mapper.countBrokenRoleGenerationReferences(
                            actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));

                    for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
                      int changed =
                          restoreRoleGenerationRows(
                              mapper, aggregateTable, actual, restoredAt, identifier);
                      if (changed != counts.get(aggregateTable)) {
                        throw incompleteGeneration(actual.getDeletionId());
                      }
                    }
                    completeRestore(actual, restoredAt, restoreEtag);
                    insertRestoreChange(identifier, Entity.EntityType.ROLE);
                    restored.set(getRoleByIdentifier(identifier));
                  }));
    } catch (RuntimeException failure) {
      EntityDeletionPO completed =
          EntityDeletionService.getInstance().get(observed.getDeletionId());
      if (isCompletedRestoreReplay(
          Entity.EntityType.ROLE, identifier, metalakeId, observed, completed, restoreEtag)) {
        return loadIdempotentlyRestoredRole(identifier, metalakeId, completed);
      }
      throw failure;
    }
    return Objects.requireNonNull(restored.get(), "restored role must not be null");
  }

  /** Permanently deletes a bounded batch of expired recorded role generations. */
  public int purgeExpiredRoleDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.ROLE, legacyTimeline, limit);
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
                  RolePO generation =
                      mapper.selectRoleGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validateRoleGeneration(actual, generation);
                  Map<RoleAggregateTable, Integer> counts = generationCounts(mapper, actual);
                  validateGenerationCompleteness(
                      actual,
                      counts.get(RoleAggregateTable.ROLE),
                      sumCounts(counts),
                      mapper.countBrokenRoleGenerationReferences(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId()));
                  for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
                    if (mapper.hardDeleteRoleGenerationRows(
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

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listSecurableObjectsByRoleId")
  public static List<SecurableObjectPO> listSecurableObjectsByRoleId(Long roleId) {
    return SessionUtils.getWithoutCommit(
        SecurableObjectMapper.class, mapper -> mapper.listSecurableObjectsByRoleId(roleId));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesByNamespace")
  public List<RoleEntity> listRolesByNamespace(Namespace namespace) {
    AuthorizationUtils.checkRoleNamespace(namespace);
    String metalakeName = namespace.level(0);

    List<RolePO> rolePOs =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class, mapper -> mapper.listRolePOsByMetalake(metalakeName));

    return rolePOs.stream()
        .map(
            po ->
                POConverters.fromRolePO(
                    po, Collections.emptyList(), AuthorizationUtils.ofRoleNamespace(metalakeName)))
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteRoleMetasByLegacyTimeline")
  public int deleteRoleMetasByLegacyTimeline(long legacyTimeline, int limit) {
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                IdentityRecoveryMapper.class,
                mapper -> {
                  List<Long> roleIds =
                      mapper.selectLegacyRoleRootsForUpdate(legacyTimeline, limit).stream()
                          .map(RolePO::getRoleId)
                          .collect(Collectors.toList());
                  if (roleIds.isEmpty()) {
                    return;
                  }
                  for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
                    deleted.addAndGet(
                        mapper.hardDeleteLegacyRoleAggregateRows(
                            aggregateTable, roleIds, legacyTimeline));
                  }
                }));
    return deleted.get();
  }

  private static long chooseDeletedAt(
      IdentityRecoveryMapper mapper,
      long roleId,
      long metalakeId,
      String roleName,
      long requestedDeletedAt) {
    Long newest = mapper.selectNewestRoleDeletedAt(roleId, metalakeId, roleName);
    return newest == null || newest < requestedDeletedAt
        ? requestedDeletedAt
        : Math.addExact(newest, 1L);
  }

  private static long generationRowCount(IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    long count = 0L;
    for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
      count =
          Math.addExact(
              count,
              mapper.countRoleGenerationRows(
                  aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return count;
  }

  private static Map<RoleAggregateTable, Integer> generationCounts(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    Map<RoleAggregateTable, Integer> counts = new EnumMap<>(RoleAggregateTable.class);
    for (RoleAggregateTable aggregateTable : RoleAggregateTable.values()) {
      counts.put(
          aggregateTable,
          mapper.countRoleGenerationRows(
              aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId()));
    }
    return counts;
  }

  private static void validateRoleGeneration(
      EntityDeletionPO deletion, @Nullable RolePO generation) {
    if (generation == null
        || !Objects.equals(generation.getRoleId(), deletion.getEntityId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.getRoleName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw incompleteGeneration(deletion.getDeletionId());
    }
  }

  private static void validateRoleOccupancy(
      IdentityRecoveryMapper mapper, EntityDeletionPO deletion) {
    RolePO occupant = mapper.lockLiveRole(deletion.getMetalakeId(), deletion.getEntityName());
    if (occupant == null) {
      return;
    }
    if (Objects.equals(occupant.getRoleId(), deletion.getEntityId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Role ID %s is already active under a different logical role",
          deletion.getEntityId());
    }
    throw new RecoveryConflictException(
        RecoveryConflictReason.NAME_OCCUPIED,
        "A live role already occupies name %s",
        deletion.getEntityName());
  }

  private static int restoreRoleGenerationRows(
      IdentityRecoveryMapper mapper,
      RoleAggregateTable aggregateTable,
      EntityDeletionPO deletion,
      long restoredAt,
      NameIdentifier identifier) {
    try {
      return mapper.restoreRoleGenerationRows(
          aggregateTable, deletion.getDeletedAt(), deletion.getDeletionId(), restoredAt);
    } catch (RuntimeException failure) {
      try {
        ExceptionUtils.checkSQLException(failure, Entity.EntityType.ROLE, identifier.toString());
      } catch (EntityAlreadyExistsException duplicate) {
        validateRoleOccupancy(mapper, deletion);
        throw new RecoveryConflictException(
            duplicate,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live role conflicts with restore target %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve non-duplicate persistence failures.
      }
      throw failure;
    }
  }

  private static RoleEntity loadIdempotentlyRestoredRole(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    RoleEntity live;
    try {
      live = getInstance().getRoleByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw MetalakeScopedRecoveryServiceSupport.tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Role ID %s is active under a different logical role",
          deletion.getEntityId());
    }
    return live;
  }

  private static List<SecurableObject> listSecurableObjects(RolePO po) {
    List<SecurableObjectPO> securableObjectPOs = listSecurableObjectsByRoleId(po.getRoleId());
    List<SecurableObject> securableObjects = Lists.newArrayList();

    securableObjectPOs.stream()
        .collect(Collectors.groupingBy(SecurableObjectPO::getType))
        .forEach(
            (type, objects) -> {
              List<Long> objectIds =
                  objects.stream()
                      .map(SecurableObjectPO::getMetadataObjectId)
                      .collect(Collectors.toList());

              // dynamically calling getter function based on type
              Map<Long, String> objectIdAndNameMap =
                  Optional.of(MetadataObject.Type.valueOf(type))
                      .map(MetadataObjectService.TYPE_TO_FULLNAME_FUNCTION_MAP::get)
                      .map(getter -> getter.apply(objectIds))
                      .orElseThrow(
                          () ->
                              // for example: MetadataObject.Type.COLUMN
                              new IllegalArgumentException(
                                  "Unsupported metadata object type: " + type));

              for (SecurableObjectPO securableObjectPO : objects) {
                String fullName = objectIdAndNameMap.get(securableObjectPO.getMetadataObjectId());
                if (fullName != null) {
                  securableObjects.add(
                      POConverters.fromSecurableObjectPO(
                          fullName, securableObjectPO, getType(securableObjectPO.getType())));
                } else {
                  LOG.warn(
                      "The securable object {} {} may be deleted",
                      securableObjectPO.getMetadataObjectId(),
                      securableObjectPO.getType());
                }
              }
            });
    return securableObjects;
  }

  private static RolePO getRolePOByMetalakeIdAndName(Long metalakeId, String roleName) {
    RolePO rolePO =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class,
            mapper -> mapper.selectRoleMetaByMetalakeIdAndName(metalakeId, roleName));

    if (rolePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.ROLE.name().toLowerCase(),
          roleName);
    }
    return rolePO;
  }

  private static MetadataObject.Type getType(String type) {
    return MetadataObject.Type.valueOf(type);
  }

  private static String getType(SecurableObject securableObject) {
    return securableObject.type().name();
  }
}
