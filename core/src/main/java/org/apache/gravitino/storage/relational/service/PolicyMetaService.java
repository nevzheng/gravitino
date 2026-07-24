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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.GenericEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyVersionMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.PolicyMaxVersionPO;
import org.apache.gravitino.storage.relational.po.PolicyMetadataObjectRelPO;
import org.apache.gravitino.storage.relational.po.PolicyPO;
import org.apache.gravitino.storage.relational.po.PolicyVersionPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PolicyMetaService {
  private static final PolicyMetaService INSTANCE = new PolicyMetaService();
  private static final Logger LOG = LoggerFactory.getLogger(PolicyMetaService.class);

  public static PolicyMetaService getInstance() {
    return INSTANCE;
  }

  private PolicyMetaService() {}

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listPoliciesByNamespace")
  public List<PolicyEntity> listPoliciesByNamespace(Namespace namespace) {
    String metalakeName = namespace.level(0);
    List<PolicyPO> policyPOs =
        SessionUtils.getWithoutCommit(
            PolicyMetaMapper.class, mapper -> mapper.listPolicyPOsByMetalake(metalakeName));
    return policyPOs.stream()
        .map(policyPO -> POConverters.fromPolicyPO(policyPO, namespace))
        .collect(Collectors.toList());
  }

  /** Lists independently deleted policy roots under one live metalake. */
  public List<PolicyPO> listDeletedPoliciesByNamespace(Namespace namespace) {
    validatePolicyNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        PolicyRecoveryMapper.class, mapper -> mapper.listDeletedRootPolicies(metalakeId));
  }

  /** Lists live policy rows under one metalake for recovery conflict detection. */
  public List<PolicyPO> listLivePolicyPOsByNamespace(Namespace namespace) {
    validatePolicyNamespace(namespace);
    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.level(0)), Entity.EntityType.METALAKE);
    return SessionUtils.getWithoutCommit(
        PolicyRecoveryMapper.class, mapper -> mapper.listLivePolicies(metalakeId));
  }

  /** Lists globally live policy rows matching candidate immutable IDs. */
  public List<PolicyPO> listLivePoliciesByIds(List<Long> policyIds) {
    if (policyIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        PolicyRecoveryMapper.class, mapper -> mapper.listLivePoliciesByIds(policyIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getPolicyByIdentifier")
  public PolicyEntity getPolicyByIdentifier(NameIdentifier ident) {
    String metalakeName = ident.namespace().level(0);
    PolicyPO policyPO = getPolicyPOByMetalakeAndName(metalakeName, ident.name());
    return POConverters.fromPolicyPO(policyPO, ident.namespace());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertPolicy")
  public void insertPolicy(PolicyEntity policyEntity, boolean overwritten) throws IOException {
    Namespace ns = policyEntity.namespace();
    String metalakeName = ns.level(0);

    try {
      Long metalakeId =
          EntityIdService.getEntityId(NameIdentifier.of(metalakeName), Entity.EntityType.METALAKE);

      PolicyPO.Builder builder = PolicyPO.builder().withMetalakeId(metalakeId);
      PolicyPO policyPO = POConverters.initializePolicyPOWithVersion(policyEntity, builder);

      // insert both policy meta table and policy version table
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  PolicyRecoveryMapper.class,
                  mapper -> {
                    lockLiveMetalake(mapper, metalakeId);
                    if (!mapper
                        .selectDeletedPoliciesForUpdate(
                            Collections.singletonList(policyPO.getPolicyId()))
                        .isEmpty()) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "Policy ID %s belongs to a recoverable deletion; use metadata restore",
                          policyPO.getPolicyId());
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  PolicyMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertPolicyMetaOnDuplicateKeyUpdate(policyPO);
                    } else {
                      mapper.insertPolicyMeta(policyPO);
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  PolicyVersionMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertPolicyVersionOnDuplicateKeyUpdate(policyPO.getPolicyVersionPO());
                    } else {
                      mapper.insertPolicyVersion(policyPO.getPolicyVersionPO());
                    }
                  }));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, policyEntity.toString());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updatePolicy")
  public <E extends Entity & HasIdentifier> PolicyEntity updatePolicy(
      NameIdentifier ident, Function<E, E> updater) throws IOException {
    String metalakeName = ident.namespace().level(0);

    PolicyPO oldPolicyPO = getPolicyPOByMetalakeAndName(metalakeName, ident.name());
    PolicyEntity oldPolicyEntity = POConverters.fromPolicyPO(oldPolicyPO, ident.namespace());
    PolicyEntity updatedPolicyEntity = (PolicyEntity) updater.apply((E) oldPolicyEntity);
    Preconditions.checkArgument(
        Objects.equals(oldPolicyEntity.id(), updatedPolicyEntity.id()),
        "The updated policy entity id: %s must have the same id as the old entity id %s",
        updatedPolicyEntity.id(),
        oldPolicyEntity.id());

    Integer updateResult;
    try {
      boolean checkNeedUpdateVersion =
          POConverters.checkPolicyVersionNeedUpdate(
              oldPolicyPO.getPolicyVersionPO(), updatedPolicyEntity);
      PolicyPO newPolicyPO =
          POConverters.updatePolicyPOWithVersion(
              oldPolicyPO, updatedPolicyEntity, checkNeedUpdateVersion);
      if (checkNeedUpdateVersion) {
        SessionUtils.doMultipleWithCommit(
            () ->
                SessionUtils.doWithoutCommit(
                    PolicyVersionMapper.class,
                    mapper -> mapper.insertPolicyVersion(newPolicyPO.getPolicyVersionPO())),
            () ->
                SessionUtils.doWithoutCommit(
                    PolicyMetaMapper.class,
                    mapper -> mapper.updatePolicyMeta(newPolicyPO, oldPolicyPO)));
        // we set the updateResult to 1 to indicate that the update is successful
        updateResult = 1;
      } else {
        updateResult =
            SessionUtils.doWithCommitAndFetchResult(
                PolicyMetaMapper.class,
                mapper -> mapper.updatePolicyMeta(newPolicyPO, oldPolicyPO));
      }
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.POLICY, updatedPolicyEntity.nameIdentifier().toString());
      throw re;
    }

    if (updateResult > 0) {
      return updatedPolicyEntity;
    } else {
      throw new IOException("Failed to update the entity: " + updatedPolicyEntity);
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deletePolicy")
  public boolean deletePolicy(NameIdentifier ident) {
    return deletePolicy(ident, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes one policy aggregate and records its recoverable deletion generation.
   *
   * @param identifier policy identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} if a live policy was deleted
   */
  public boolean deletePolicy(NameIdentifier identifier, long retentionMs) {
    return deletePolicy(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a policy base row and the policy versions that are live at deletion time.
   *
   * <p>Policy associations, owners, grants, and external authorization state are deliberately not
   * mutated. This metadata-only recovery contract reactivates only the policy aggregate.
   *
   * @param identifier policy identifier
   * @param requestedDeletedAt requested deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} if the exact live policy snapshot was deleted
   */
  public boolean deletePolicy(
      NameIdentifier identifier, long requestedDeletedAt, long retentionMs) {
    NameIdentifierUtil.checkPolicy(identifier);
    Preconditions.checkArgument(requestedDeletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
    AtomicInteger deleted = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                PolicyRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalake(mapper, metalakeId);
                  PolicyPO policy = mapper.lockLivePolicy(metalakeId, identifier.name());
                  if (policy == null) {
                    return;
                  }
                  validateLiveCurrentVersion(policy);

                  long deletedAt =
                      chooseDeletedAt(
                          mapper,
                          policy.getPolicyId(),
                          metalakeId,
                          policy.getPolicyName(),
                          requestedDeletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.POLICY,
                              policy.getPolicyId(),
                              metalakeId,
                              null,
                              metalakeId,
                              policy.getPolicyName(),
                              policy.getCurrentVersion(),
                              deletedAt,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int versionCount =
                      mapper.softDeletePolicyVersions(
                          policy.getPolicyId(), deletedAt, deletion.getDeletionId());
                  deletion.setAffectedRowCount(Math.addExact(1L, versionCount));
                  if (versionCount <= 0
                      || mapper.countPolicyVersionGeneration(
                              policy.getPolicyId(), deletedAt, deletion.getDeletionId())
                          != versionCount
                      || mapper.countCurrentVersionGeneration(
                              policy.getPolicyId(),
                              policy.getCurrentVersion(),
                              deletedAt,
                              deletion.getDeletionId())
                          != 1) {
                    throw incompleteGeneration(deletion.getDeletionId());
                  }

                  int baseCount =
                      mapper.softDeletePolicyMeta(
                          policy.getPolicyId(),
                          metalakeId,
                          policy.getPolicyName(),
                          policy.getCurrentVersion(),
                          deletedAt,
                          deletion.getDeletionId());
                  deleted.set(baseCount);
                  if (baseCount != 1
                      || mapper.countPolicyGeneration(
                              policy.getPolicyId(), deletedAt, deletion.getDeletionId())
                          != 1) {
                    throw new TombstoneChangedException(
                        "Policy changed while deleting %s", identifier);
                  }

                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.POLICY.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleted.get() == 1;
  }

  /**
   * Restores one exact metadata-only policy deletion generation transactionally.
   *
   * @param identifier original policy identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized restoration
   * @param effectiveExpiresAt expiry derived from the current retention configuration
   * @return restored policy metadata
   */
  public PolicyEntity restorePolicy(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkPolicy(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    long metalakeId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
    AtomicReference<PolicyEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                PolicyRecoveryMapper.class,
                mapper -> {
                  lockLiveMetalakeForRestore(mapper, metalakeId, identifier);
                  validateLatestDeletion(identifier, metalakeId, observed);

                  EntityDeletionPO actual =
                      SessionUtils.getWithoutCommit(
                          EntityDeletionMapper.class,
                          deletionMapper ->
                              deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                  if (isCompletedRestoreReplay(
                      identifier, metalakeId, observed, actual, restoreEtag)) {
                    restored.set(loadIdempotentlyRestoredPolicy(identifier, metalakeId, actual));
                    return;
                  }
                  validateDeletionSnapshot(identifier, metalakeId, observed, actual);
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

                  PolicyPO generation =
                      mapper.selectPolicyGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  List<PolicyVersionPO> versions =
                      mapper.listPolicyVersionGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validatePolicyGeneration(mapper, actual, generation, versions);

                  int restoredVersions =
                      mapper.restorePolicyVersions(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  if (restoredVersions != versions.size()) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  if (restorePolicyMeta(mapper, actual, generation, identifier) != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
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
                              Entity.EntityType.POLICY.name(),
                              identifier.toString(),
                              OperateType.RESTORE));
                  restored.set(getPolicyByIdentifier(identifier));
                }));
    return Objects.requireNonNull(restored.get(), "restored policy must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired recorded policy generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  public int purgeExpiredPolicyDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.POLICY, legacyTimeline, limit);
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
                PolicyRecoveryMapper.class,
                mapper -> {
                  PolicyPO generation =
                      mapper.selectPolicyGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  List<PolicyVersionPO> versions =
                      mapper.listPolicyVersionGenerationForUpdate(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                  validatePolicyGeneration(mapper, actual, generation, versions);
                  if (mapper.hardDeletePolicyVersions(
                          actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                      != versions.size()) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                  if (mapper.hardDeletePolicyMeta(
                          actual.getEntityId(),
                          actual.getParentId(),
                          actual.getEntityName(),
                          generation.getCurrentVersion(),
                          actual.getDeletedAt(),
                          actual.getDeletionId())
                      != 1) {
                    throw incompleteGeneration(actual.getDeletionId());
                  }
                });
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
      baseMetricName = "listPoliciesForMetadataObject")
  public List<PolicyEntity> listPoliciesForMetadataObject(
      NameIdentifier objectIdent, Entity.EntityType objectType)
      throws NoSuchEntityException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    List<PolicyPO> PolicyPOs;
    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);

      PolicyPOs =
          SessionUtils.getWithoutCommit(
              PolicyMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listPolicyPOsByMetadataObjectIdAndType(
                      metadataObjectId, metadataObject.type().toString()));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, objectIdent.toString());
      throw e;
    }

    return PolicyPOs.stream()
        .map(PolicyPO -> POConverters.fromPolicyPO(PolicyPO, NamespaceUtil.ofPolicy(metalake)))
        .collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getPolicyForMetadataObject")
  public PolicyEntity getPolicyForMetadataObject(
      NameIdentifier objectIdent, Entity.EntityType objectType, NameIdentifier policyIdent)
      throws NoSuchEntityException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    PolicyPO policyPO;
    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);

      policyPO =
          SessionUtils.getWithoutCommit(
              PolicyMetadataObjectRelMapper.class,
              mapper ->
                  mapper.getPolicyPOsByMetadataObjectAndPolicyName(
                      metadataObjectId, metadataObject.type().toString(), policyIdent.name()));
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, policyIdent.toString());
      throw e;
    }

    if (policyPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.POLICY.name().toLowerCase(),
          policyIdent.name());
    }

    return POConverters.fromPolicyPO(policyPO, NamespaceUtil.ofPolicy(metalake));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listAssociatedEntitiesForPolicy")
  public List<GenericEntity> listAssociatedEntitiesForPolicy(NameIdentifier policyIdent)
      throws IOException {
    String metalakeName = policyIdent.namespace().level(0);
    String policyName = policyIdent.name();

    try {
      List<PolicyMetadataObjectRelPO> policyMetadataObjectRelPOs =
          SessionUtils.doWithCommitAndFetchResult(
              PolicyMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listPolicyMetadataObjectRelsByMetalakeAndPolicyName(
                      metalakeName, policyName));

      return policyMetadataObjectRelPOs.stream()
          .map(
              r ->
                  GenericEntity.builder()
                      .withId(r.getMetadataObjectId())
                      .withEntityType(
                          MetadataObjectUtil.toEntityType(
                              MetadataObject.Type.valueOf(r.getMetadataObjectType())))
                      .build())
          .collect(Collectors.toList());

    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, policyIdent.toString());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "associatePoliciesWithMetadataObject")
  public List<PolicyEntity> associatePoliciesWithMetadataObject(
      NameIdentifier objectIdent,
      Entity.EntityType objectType,
      NameIdentifier[] policiesToAdd,
      NameIdentifier[] policiesToRemove)
      throws NoSuchEntityException, EntityAlreadyExistsException, IOException {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(objectIdent, objectType);
    String metalake = objectIdent.namespace().level(0);

    try {
      Long metadataObjectId = EntityIdService.getEntityId(objectIdent, objectType);

      // Fetch all the policies need to associate with the metadata object.
      List<String> policyNamesToAdd =
          Arrays.stream(policiesToAdd).map(NameIdentifier::name).collect(Collectors.toList());
      List<PolicyPO> policyPOsToAdd =
          policyNamesToAdd.isEmpty()
              ? Collections.emptyList()
              : getPolicyPOsByMetalakeAndNames(metalake, policyNamesToAdd);

      // Fetch all the policies need to remove from the metadata object.
      List<String> policyNamesToRemove =
          Arrays.stream(policiesToRemove).map(NameIdentifier::name).collect(Collectors.toList());
      List<PolicyPO> policyPOsToRemove =
          policyNamesToRemove.isEmpty()
              ? Collections.emptyList()
              : getPolicyPOsByMetalakeAndNames(metalake, policyNamesToRemove);

      SessionUtils.doMultipleWithCommit(
          () -> {
            // Insert the policy metadata object relations.
            if (policyPOsToAdd.isEmpty()) {
              return;
            }

            List<PolicyMetadataObjectRelPO> policyRelsToAdd =
                policyPOsToAdd.stream()
                    .map(
                        policyPO ->
                            POConverters.initializePolicyMetadataObjectRelPOWithVersion(
                                policyPO.getPolicyId(),
                                metadataObjectId,
                                metadataObject.type().toString()))
                    .collect(Collectors.toList());
            SessionUtils.doWithoutCommit(
                PolicyMetadataObjectRelMapper.class,
                mapper -> mapper.batchInsertPolicyMetadataObjectRels(policyRelsToAdd));
          },
          () -> {
            // Remove the policy metadata object relations.
            if (policyPOsToRemove.isEmpty()) {
              return;
            }

            List<Long> policyIdsToRemove =
                policyPOsToRemove.stream().map(PolicyPO::getPolicyId).collect(Collectors.toList());
            SessionUtils.doWithoutCommit(
                PolicyMetadataObjectRelMapper.class,
                mapper ->
                    mapper.batchDeletePolicyMetadataObjectRelsByPolicyIdsAndMetadataObject(
                        metadataObjectId, metadataObject.type().toString(), policyIdsToRemove));
          });

      // Fetch all the policies associated with the metadata object after the operation.
      List<PolicyPO> policyPOs =
          SessionUtils.getWithoutCommit(
              PolicyMetadataObjectRelMapper.class,
              mapper ->
                  mapper.listPolicyPOsByMetadataObjectIdAndType(
                      metadataObjectId, metadataObject.type().toString()));

      return policyPOs.stream()
          .map(policyPO -> POConverters.fromPolicyPO(policyPO, NamespaceUtil.ofPolicy(metalake)))
          .collect(Collectors.toList());

    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, objectIdent.toString());
      throw e;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deletePolicyAndVersionMetasByLegacyTimeline")
  public int deletePolicyAndVersionMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    AtomicInteger legacyAggregateDeletedCount = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                PolicyRecoveryMapper.class,
                mapper -> {
                  List<PolicyPO> roots =
                      mapper.selectLegacyPolicyRootsForUpdate(legacyTimeline, limit);
                  if (roots.isEmpty()) {
                    return;
                  }
                  List<Long> policyIds =
                      roots.stream().map(PolicyPO::getPolicyId).collect(Collectors.toList());
                  int versionCount = mapper.hardDeleteAllPolicyVersions(policyIds);
                  int rootCount = mapper.hardDeleteLegacyPolicyRoots(policyIds, legacyTimeline);
                  if (rootCount != policyIds.size()) {
                    throw new TombstoneChangedException(
                        "Legacy policy roots changed during permanent cleanup");
                  }
                  legacyAggregateDeletedCount.set(versionCount + rootCount);
                }));
    if (legacyAggregateDeletedCount.get() > 0) {
      return legacyAggregateDeletedCount.get();
    }

    return SessionUtils.doWithCommitAndFetchResult(
        PolicyVersionMapper.class,
        mapper -> mapper.deletePolicyVersionsByLegacyTimeline(legacyTimeline, limit));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deletePolicyVersionsByRetentionCount")
  public int deletePolicyVersionsByRetentionCount(Long versionRetentionCount, int limit) {
    // get the current version of all policies.
    List<PolicyMaxVersionPO> policyMaxVersions =
        SessionUtils.getWithoutCommit(
            PolicyVersionMapper.class,
            mapper -> mapper.selectPolicyVersionsByRetentionCount(versionRetentionCount));

    // soft delete old versions that are smaller than or equal to (maxVersion -
    // versionRetentionCount).
    int totalDeletedCount = 0;
    for (PolicyMaxVersionPO policyMaxVersion : policyMaxVersions) {
      long versionRetentionLine = policyMaxVersion.getVersion() - versionRetentionCount;
      int deletedCount =
          SessionUtils.doWithCommitAndFetchResult(
              PolicyVersionMapper.class,
              mapper ->
                  mapper.softDeletePolicyVersionsByRetentionLine(
                      policyMaxVersion.getPolicyId(), versionRetentionLine, limit));
      totalDeletedCount += deletedCount;

      // log the deletion by max policy version.
      LOG.info(
          "Soft delete policyVersions count: {} which versions are smaller than or equal to"
              + " versionRetentionLine: {}, the current policyId and maxVersion is: <{}, {}>.",
          deletedCount,
          versionRetentionLine,
          policyMaxVersion.getPolicyId(),
          policyMaxVersion.getVersion());
    }
    return totalDeletedCount;
  }

  private static void validatePolicyNamespace(Namespace namespace) {
    NameIdentifierUtil.checkPolicy(NameIdentifier.of(namespace, "policy"));
  }

  private static void lockLiveMetalake(PolicyRecoveryMapper mapper, long metalakeId) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.METALAKE.name().toLowerCase(Locale.ROOT),
          metalakeId);
    }
  }

  private static void lockLiveMetalakeForRestore(
      PolicyRecoveryMapper mapper, long metalakeId, NameIdentifier identifier) {
    if (!Objects.equals(mapper.lockLiveMetalake(metalakeId), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.PARENT_CHANGED,
          "The parent metalake changed while restoring policy %s",
          identifier);
    }
  }

  private static void validateLiveCurrentVersion(PolicyPO policy) {
    PolicyVersionPO current = policy.getPolicyVersionPO();
    if (current == null
        || !Objects.equals(current.getPolicyId(), policy.getPolicyId())
        || !Objects.equals(current.getMetalakeId(), policy.getMetalakeId())
        || !Objects.equals(current.getVersion(), policy.getCurrentVersion())
        || current.getDeletedAt() != 0
        || current.getDeletionId() != null) {
      throw incompleteGeneration("unrecorded-policy-" + policy.getPolicyId());
    }
  }

  private static long chooseDeletedAt(
      PolicyRecoveryMapper mapper,
      long policyId,
      long metalakeId,
      String policyName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestPolicyDeletedAt(policyId, metalakeId, policyName);
    if (newestDeletedAt == null || newestDeletedAt < requestedDeletedAt) {
      return requestedDeletedAt;
    }
    return Math.addExact(newestDeletedAt, 1L);
  }

  private static void validateLatestDeletion(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO observed) {
    EntityDeletionPO latest =
        SessionUtils.getWithoutCommit(
            EntityDeletionMapper.class,
            mapper ->
                mapper.selectLatestEntityDeletion(
                    Entity.EntityType.POLICY.name(), metalakeId, identifier.name()));
    if (latest == null || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
          "Deletion generation %s is no longer latest for policy %s",
          observed.getDeletionId(),
          identifier);
    }
  }

  private static void validateDeletionSnapshot(
      NameIdentifier identifier,
      long metalakeId,
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
            && Objects.equals(actual.getAffectedRowCount(), observed.getAffectedRowCount())
            && Objects.equals(actual.getState(), observed.getState())
            && Objects.equals(actual.getRevision(), observed.getRevision())
            && Objects.equals(actual.getRestoredAt(), observed.getRestoredAt())
            && Objects.equals(actual.getRestoreEtag(), observed.getRestoreEtag())
            && Objects.equals(actual.getPurgedAt(), observed.getPurgedAt())
            && actual.getState() == DeletionState.DELETED
            && Entity.EntityType.POLICY.name().equals(actual.getEntityType())
            && Objects.equals(actual.getMetalakeId(), metalakeId)
            && actual.getCatalogId() == null
            && Objects.equals(actual.getParentId(), metalakeId)
            && Objects.equals(actual.getEntityName(), identifier.name());
    if (!unchanged) {
      throw tombstoneChanged(observed.getDeletionId());
    }
  }

  private static boolean isCompletedRestoreReplay(
      NameIdentifier identifier,
      long metalakeId,
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
        && Objects.equals(actual.getAffectedRowCount(), observed.getAffectedRowCount())
        && Objects.equals(actual.getMetalakeId(), metalakeId)
        && actual.getCatalogId() == null
        && Objects.equals(actual.getParentId(), metalakeId)
        && Objects.equals(actual.getEntityName(), identifier.name())
        && Objects.equals(actual.getRevision(), observed.getRevision() + 2L)
        && actual.getRestoredAt() != null
        && actual.getPurgedAt() == null
        && Objects.equals(actual.getRestoreEtag(), restoreEtag);
  }

  private static PolicyEntity loadIdempotentlyRestoredPolicy(
      NameIdentifier identifier, long metalakeId, EntityDeletionPO deletion) {
    PolicyEntity live;
    try {
      live = getInstance().getPolicyByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(live.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), metalakeId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "Policy ID %s is active under a different logical policy",
          deletion.getEntityId());
    }
    return live;
  }

  private static void validatePolicyGeneration(
      PolicyRecoveryMapper mapper,
      EntityDeletionPO deletion,
      @Nullable PolicyPO generation,
      List<PolicyVersionPO> versions) {
    if (generation == null
        || !Objects.equals(generation.getPolicyId(), deletion.getEntityId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getMetalakeId())
        || !Objects.equals(generation.getMetalakeId(), deletion.getParentId())
        || !Objects.equals(generation.getPolicyName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())
        || deletion.getAffectedRowCount() == null
        || deletion.getAffectedRowCount() != 1L + versions.size()
        || mapper.countPolicyGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != 1
        || versions.isEmpty()
        || mapper.countPolicyVersionGeneration(
                deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId())
            != versions.size()
        || mapper.countCurrentVersionGeneration(
                deletion.getEntityId(),
                deletion.getEntityVersion(),
                deletion.getDeletedAt(),
                deletion.getDeletionId())
            != 1) {
      throw incompleteGeneration(deletion.getDeletionId());
    }

    Set<Long> versionNumbers = new HashSet<>();
    for (PolicyVersionPO version : versions) {
      if (!Objects.equals(version.getPolicyId(), deletion.getEntityId())
          || !Objects.equals(version.getMetalakeId(), deletion.getMetalakeId())
          || !Objects.equals(version.getDeletedAt(), deletion.getDeletedAt())
          || !Objects.equals(version.getDeletionId(), deletion.getDeletionId())
          || !versionNumbers.add(version.getVersion())) {
        throw incompleteGeneration(deletion.getDeletionId());
      }
    }
  }

  private static int restorePolicyMeta(
      PolicyRecoveryMapper mapper,
      EntityDeletionPO deletion,
      PolicyPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restorePolicyMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.getCurrentVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live policy already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static TombstoneChangedException tombstoneChanged(String deletionId) {
    return new TombstoneChangedException("Deletion generation %s changed", deletionId);
  }

  private static RecoveryConflictException incompleteGeneration(String deletionId) {
    return new RecoveryConflictException(
        RecoveryConflictReason.INCOMPLETE_GENERATION,
        "Policy deletion generation %s is incomplete and requires manual metadata repair",
        deletionId);
  }

  private PolicyPO getPolicyPOByMetalakeAndName(String metalakeName, String policyName) {
    PolicyPO policyPO =
        SessionUtils.getWithoutCommit(
            PolicyMetaMapper.class,
            mapper -> mapper.selectPolicyMetaByMetalakeAndName(metalakeName, policyName));

    if (policyPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.POLICY.name().toLowerCase(),
          policyName);
    }
    return policyPO;
  }

  private List<PolicyPO> getPolicyPOsByMetalakeAndNames(
      String metalakeName, List<String> policyNames) {
    return SessionUtils.getWithoutCommit(
        PolicyMetaMapper.class,
        mapper -> mapper.listPolicyPOsByMetalakeAndPolicyNames(metalakeName, policyNames));
  }

  /**
   * Get policy id by policy name
   *
   * @param metalakeId metalake id
   * @param policyName policy name
   * @return policy id
   */
  public long getPolicyIdByPolicyName(long metalakeId, String policyName) {
    PolicyPO policyPO =
        SessionUtils.getWithoutCommit(
            PolicyMetaMapper.class,
            mapper -> mapper.selectPolicyMetaByMetalakeIdAndName(metalakeId, policyName));
    if (policyPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.POLICY.name().toLowerCase(),
          policyName);
    }
    return policyPO.getPolicyId();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetPolicyByIdentifier")
  public List<PolicyEntity> batchGetPolicyByIdentifier(List<NameIdentifier> identifiers) {
    NameIdentifier firstIdent = identifiers.get(0);
    String metalakeName = firstIdent.namespace().level(0);
    List<String> policyNames =
        identifiers.stream().map(NameIdentifier::name).collect(Collectors.toList());

    return SessionUtils.doWithCommitAndFetchResult(
        PolicyMetaMapper.class,
        mapper -> {
          List<PolicyPO> policyPOs =
              mapper.batchSelectPolicyByIdentifier(metalakeName, policyNames);
          return POConverters.fromPolicyPOs(policyPOs, firstIdent.namespace());
        });
  }
}
