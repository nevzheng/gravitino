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
import static org.apache.gravitino.storage.relational.po.ViewPO.buildViewPO;
import static org.apache.gravitino.storage.relational.po.ViewPO.fromViewPO;
import static org.apache.gravitino.storage.relational.po.ViewPO.initializeViewPO;

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
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.ViewMetaMapper;
import org.apache.gravitino.storage.relational.mapper.ViewRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.ViewVersionInfoMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.ViewPO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/** The service class for view metadata. It provides the basic database operations for view. */
public class ViewMetaService {

  private static final ViewMetaService INSTANCE = new ViewMetaService();
  private BasePOStorageOps<ViewPO, ViewMetaMapper> ops;

  public static ViewMetaService getInstance() {
    return INSTANCE;
  }

  private ViewMetaService() {
    this.ops = new HierarchicalConversionPOStorageOps<>(new ViewPOStorageOps());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getViewIdBySchemaIdAndName")
  public Long getViewIdBySchemaIdAndName(Long schemaId, String viewName) {
    ViewPO viewPO =
        SessionUtils.getWithoutCommit(
            ViewMetaMapper.class, mapper -> ops.getPO(mapper, schemaId, viewName));

    if (viewPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.VIEW.name().toLowerCase(),
          viewName);
    }
    return viewPO.getViewId();
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listViewsByNamespace")
  public List<ViewEntity> listViewsByNamespace(Namespace namespace) {
    NamespaceUtil.checkView(namespace);
    List<ViewPO> viewPOs = listViewPOs(namespace);
    return viewPOs.stream().map(po -> fromViewPO(po, namespace)).collect(Collectors.toList());
  }

  /**
   * Lists deleted view base rows under one live schema.
   *
   * @param namespace view namespace
   * @return deleted view generations, newest first
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDeletedViewsByNamespace")
  public List<ViewPO> listDeletedViewsByNamespace(Namespace namespace) {
    NamespaceUtil.checkView(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        ViewRecoveryMapper.class, mapper -> mapper.listDeletedViews(schemaId));
  }

  /**
   * Lists live view base rows under one schema for recovery conflict detection.
   *
   * @param namespace view namespace
   * @return live view base rows
   */
  public List<ViewPO> listLiveViewPOsByNamespace(Namespace namespace) {
    NamespaceUtil.checkView(namespace);
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(namespace.levels()), Entity.EntityType.SCHEMA);
    return SessionUtils.getWithoutCommit(
        ViewRecoveryMapper.class, mapper -> mapper.listLiveViews(schemaId));
  }

  /**
   * Lists globally live view rows matching candidate immutable IDs.
   *
   * @param viewIds candidate view IDs
   * @return matching live view rows
   */
  public List<ViewPO> listLiveViewsByIds(List<Long> viewIds) {
    if (viewIds.isEmpty()) {
      return Collections.emptyList();
    }
    return SessionUtils.getWithoutCommit(
        ViewRecoveryMapper.class, mapper -> mapper.listLiveViewsByIds(viewIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getViewByIdentifier")
  public ViewEntity getViewByIdentifier(NameIdentifier identifier) {
    ViewPO viewPO = getViewPOByIdentifier(identifier);
    return fromViewPO(viewPO, identifier.namespace());
  }

  @Monitored(metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME, baseMetricName = "insertView")
  public void insertView(ViewEntity viewEntity, boolean overwrite) throws IOException {
    NameIdentifierUtil.checkView(viewEntity.nameIdentifier());

    ViewPO.ViewPOBuilder builder = ViewPO.builder();
    try {
      ViewPO po = initializeViewPO(viewEntity, builder);

      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  ViewRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, po.getSchemaId());
                    if (overwrite
                        && recoveryMapper.selectRecordedDeletedViewForUpdate(po.getViewId())
                            != null) {
                      throw new RecoveryConflictException(
                          RecoveryConflictReason.ENTITY_ID_REUSED,
                          "View ID %s belongs to a recoverable deletion; use metadata restore",
                          po.getViewId());
                    }
                    SessionUtils.doWithoutCommit(
                        ViewMetaMapper.class, mapper -> ops.insertPO(mapper, po, overwrite));
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  ViewVersionInfoMapper.class,
                  mapper -> {
                    if (overwrite) {
                      mapper.insertViewVersionInfoOnDuplicateKeyUpdate(po.getViewVersionInfoPO());
                    } else {
                      mapper.insertViewVersionInfo(po.getViewVersionInfoPO());
                    }
                  }));
    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.VIEW, viewEntity.nameIdentifier().toString());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateViewByIdentifier")
  public <E extends Entity & HasIdentifier> ViewEntity updateView(
      NameIdentifier ident, Function<E, E> updater) throws IOException {
    NameIdentifierUtil.checkView(ident);
    Objects.requireNonNull(updater, "updater must not be null");
    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(ident.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicReference<ViewEntity> candidate = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  ViewRecoveryMapper.class,
                  recoveryMapper -> {
                    lockLiveSchema(recoveryMapper, schemaId);
                    ViewPO locked = recoveryMapper.selectLiveViewForUpdate(schemaId, ident.name());
                    if (locked == null) {
                      throw new NoSuchEntityException(
                          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                          Entity.EntityType.VIEW.name().toLowerCase(Locale.ROOT),
                          ident.name());
                    }

                    ViewPO oldViewPO =
                        SessionUtils.getWithoutCommit(
                            ViewMetaMapper.class,
                            mapper ->
                                mapper.selectViewMetaBySchemaIdAndName(schemaId, ident.name()));
                    if (oldViewPO == null
                        || !Objects.equals(oldViewPO.getViewId(), locked.getViewId())) {
                      throw new TombstoneChangedException("View changed while updating %s", ident);
                    }
                    ViewEntity oldViewEntity = fromViewPO(oldViewPO, ident.namespace());
                    ViewEntity newEntity = (ViewEntity) updater.apply((E) oldViewEntity);
                    candidate.set(newEntity);
                    Preconditions.checkArgument(
                        Objects.equals(oldViewEntity.id(), newEntity.id()),
                        "The updated view entity id: %s should be same with the entity id before: %s",
                        newEntity.id(),
                        oldViewEntity.id());

                    ViewPO newViewPO = updateViewPO(oldViewPO, newEntity);
                    SessionUtils.doWithoutCommit(
                        ViewVersionInfoMapper.class,
                        mapper -> mapper.insertViewVersionInfo(newViewPO.getViewVersionInfoPO()));
                    if (SessionUtils.getWithoutCommit(
                            ViewMetaMapper.class,
                            mapper -> ops.updatePO(mapper, newViewPO, oldViewPO))
                        != 1) {
                      throw new TombstoneChangedException("View changed while updating %s", ident);
                    }

                    if (!Objects.equals(oldViewPO.getViewName(), newViewPO.getViewName())) {
                      String oldFullName =
                          NameIdentifierUtil.ofView(
                                  ident.namespace().level(0),
                                  ident.namespace().level(1),
                                  ident.namespace().level(2),
                                  oldViewPO.getViewName())
                              .toString();
                      SessionUtils.doWithoutCommit(
                          EntityChangeLogMapper.class,
                          mapper ->
                              mapper.insertEntityChange(
                                  ident.namespace().level(0),
                                  Entity.EntityType.VIEW.name(),
                                  oldFullName,
                                  OperateType.ALTER));
                    }
                  }));
    } catch (RuntimeException re) {
      ViewEntity newEntity = candidate.get();
      ExceptionUtils.checkSQLException(
          re,
          Entity.EntityType.VIEW,
          newEntity == null ? ident.toString() : newEntity.nameIdentifier().toString());
      throw re;
    }
    return Objects.requireNonNull(candidate.get(), "updated view must not be null");
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteViewByIdentifier")
  public boolean deleteView(NameIdentifier ident) {
    return deleteView(ident, Configs.DEFAULT_STORE_DELETE_AFTER_TIME);
  }

  /**
   * Soft-deletes a view and records its durable deletion generation atomically.
   *
   * @param identifier view identifier
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when a live view row was deleted
   */
  public boolean deleteView(NameIdentifier identifier, long retentionMs) {
    return deleteView(identifier, Instant.now().toEpochMilli(), retentionMs);
  }

  /**
   * Soft-deletes a view using one timestamp and generation token for every affected row.
   *
   * @param identifier view identifier
   * @param deletedAt deletion timestamp in milliseconds
   * @param retentionMs recoverability window in milliseconds
   * @return {@code true} when the exact live view snapshot was deleted
   */
  public boolean deleteView(NameIdentifier identifier, long deletedAt, long retentionMs) {
    NameIdentifierUtil.checkView(identifier);
    Preconditions.checkArgument(deletedAt > 0, "deletedAt must be positive");
    Preconditions.checkArgument(retentionMs >= 0, "retentionMs must not be negative");

    long schemaId =
        EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
    AtomicInteger deleteResult = new AtomicInteger();
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                ViewRecoveryMapper.class,
                mapper -> {
                  lockLiveSchema(mapper, schemaId);
                  ViewPO view = mapper.selectLiveViewForUpdate(schemaId, identifier.name());
                  if (view == null) {
                    throw new NoSuchEntityException(
                        NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
                        Entity.EntityType.VIEW.name().toLowerCase(Locale.ROOT),
                        identifier.name());
                  }
                  long deletionTimestamp =
                      chooseDeletedAt(
                          mapper, view.getViewId(), schemaId, view.getViewName(), deletedAt);
                  EntityDeletionPO deletion =
                      EntityDeletionService.getInstance()
                          .newDeletion(
                              Entity.EntityType.VIEW,
                              view.getViewId(),
                              view.getMetalakeId(),
                              view.getCatalogId(),
                              view.getSchemaId(),
                              view.getViewName(),
                              view.getCurrentVersion(),
                              deletionTimestamp,
                              retentionMs,
                              PrincipalUtils.getCurrentUserName());

                  int viewMeta =
                      mapper.softDeleteViewMeta(
                          view.getViewId(),
                          view.getSchemaId(),
                          view.getViewName(),
                          view.getCurrentVersion(),
                          view.getLastVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId());
                  deleteResult.set(viewMeta);
                  if (viewMeta != 1) {
                    throw new TombstoneChangedException(
                        "View changed while deleting %s", identifier);
                  }
                  if (mapper.softDeleteViewVersions(
                          view.getViewId(), deletionTimestamp, deletion.getDeletionId())
                      < 1) {
                    throw new TombstoneChangedException(
                        "View versions changed while deleting %s", identifier);
                  }
                  if (mapper.countCurrentVersionGeneration(
                          view.getViewId(),
                          view.getCurrentVersion(),
                          deletionTimestamp,
                          deletion.getDeletionId())
                      < 1) {
                    throw new TombstoneChangedException(
                        "View current version changed while deleting %s", identifier);
                  }
                  mapper.softDeleteOwnerRelations(
                      view.getViewId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteSecurableObjects(
                      view.getViewId(), deletionTimestamp, deletion.getDeletionId());
                  mapper.softDeleteTagRelations(
                      view.getViewId(), deletionTimestamp, deletion.getDeletionId());
                  EntityDeletionService.getInstance().insert(deletion);
                  SessionUtils.doWithoutCommit(
                      EntityChangeLogMapper.class,
                      changeLogMapper ->
                          changeLogMapper.insertEntityChange(
                              identifier.namespace().level(0),
                              Entity.EntityType.VIEW.name(),
                              identifier.toString(),
                              OperateType.DROP));
                }));
    return deleteResult.get() == 1;
  }

  /**
   * Restores one exact view metadata deletion generation transactionally.
   *
   * <p>This operation never calls or validates a connector or downstream view. It restores only the
   * View metadata retained by Gravitino.
   *
   * @param identifier original view identifier
   * @param observed optimistic deletion-generation snapshot
   * @param restoredAt restoration timestamp in milliseconds
   * @param restoreEtag exact entity tag whose precondition authorized the restore
   * @param effectiveExpiresAt expiry under the active retention policy
   * @return restored view entity
   */
  public ViewEntity restoreView(
      NameIdentifier identifier,
      EntityDeletionPO observed,
      long restoredAt,
      String restoreEtag,
      long effectiveExpiresAt) {
    NameIdentifierUtil.checkView(identifier);
    Objects.requireNonNull(observed, "observed deletion must not be null");
    Objects.requireNonNull(restoreEtag, "restore ETag must not be null");
    Preconditions.checkArgument(restoredAt > 0, "restoredAt must be positive");
    Preconditions.checkArgument(!restoreEtag.isEmpty(), "restore ETag must not be empty");
    Preconditions.checkArgument(effectiveExpiresAt > 0, "effectiveExpiresAt must be positive");

    AtomicReference<ViewEntity> restored = new AtomicReference<>();
    SessionUtils.doMultipleWithCommit(
        () -> {
          long schemaId =
              EntityIdService.getEntityId(
                  NameIdentifier.of(identifier.namespace().levels()), Entity.EntityType.SCHEMA);
          SessionUtils.doWithoutCommit(
              ViewRecoveryMapper.class,
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
                                Entity.EntityType.VIEW.name(), schemaId, identifier.name()));
                if (latest == null
                    || !Objects.equals(latest.getDeletionId(), observed.getDeletionId())) {
                  throw new RecoveryConflictException(
                      RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
                      "Deletion generation %s is no longer latest for view %s",
                      observed.getDeletionId(),
                      identifier);
                }

                EntityDeletionPO actual =
                    SessionUtils.getWithoutCommit(
                        EntityDeletionMapper.class,
                        deletionMapper ->
                            deletionMapper.selectEntityDeletion(observed.getDeletionId()));
                if (isCompletedRestoreReplay(identifier, schemaId, observed, actual, restoreEtag)) {
                  restored.set(loadIdempotentlyRestoredView(identifier, schemaId, actual));
                  return;
                }
                validateDeletionSnapshot(identifier, schemaId, observed, actual);
                if (Instant.now().toEpochMilli() >= effectiveExpiresAt) {
                  throw new TombstoneExpiredException(
                      "Deletion generation %s expired at %s",
                      observed.getDeletionId(), effectiveExpiresAt);
                }

                ViewPO generation =
                    mapper.selectViewGeneration(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                validateViewGeneration(actual, generation);
                if (mapper.countCurrentVersionGeneration(
                        actual.getEntityId(),
                        actual.getEntityVersion(),
                        actual.getDeletedAt(),
                        actual.getDeletionId())
                    < 1) {
                  throw tombstoneChanged(actual.getDeletionId());
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

                mapper.restoreOwnerRelations(
                    actual.getEntityId(),
                    actual.getDeletedAt(),
                    actual.getDeletionId(),
                    restoredAt);
                mapper.restoreSecurableObjects(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                mapper.restoreTagRelations(
                    actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId());
                if (mapper.restoreViewVersions(
                        actual.getEntityId(), actual.getDeletedAt(), actual.getDeletionId())
                    < 1) {
                  throw tombstoneChanged(actual.getDeletionId());
                }
                if (restoreViewMeta(mapper, actual, generation, identifier) != 1) {
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
                            Entity.EntityType.VIEW.name(),
                            identifier.toString(),
                            OperateType.RESTORE));
                restored.set(getViewByIdentifier(identifier));
              });
        });
    return Objects.requireNonNull(restored.get(), "restored view must not be null");
  }

  /**
   * Permanently deletes a bounded batch of expired, recorded view deletion generations.
   *
   * @param legacyTimeline current global retention cutoff
   * @param limit maximum deletion generations to purge
   * @return number of deletion generations purged
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "purgeExpiredViewDeletions")
  public int purgeExpiredViewDeletions(long legacyTimeline, int limit) {
    List<EntityDeletionPO> expired =
        EntityDeletionService.getInstance()
            .listExpired(Entity.EntityType.VIEW, legacyTimeline, limit);
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
                ViewRecoveryMapper.class, mapper -> purgeViewDeletionGeneration(mapper, actual));
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
      baseMetricName = "deleteViewMetasByLegacyTimeline")
  public int deleteViewMetasByLegacyTimeline(Long legacyTimeline, int limit) {
    int versionDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            ViewVersionInfoMapper.class,
            mapper -> mapper.deleteViewVersionsByLegacyTimeline(legacyTimeline, limit));

    int metaDeletedCount =
        SessionUtils.doWithCommitAndFetchResult(
            ViewMetaMapper.class,
            mapper -> mapper.deleteViewMetasByLegacyTimeline(legacyTimeline, limit));

    return versionDeletedCount + metaDeletedCount;
  }

  public BasePOStorageOps<ViewPO, ViewMetaMapper> ops() {
    return ops;
  }

  private static void lockLiveSchema(ViewRecoveryMapper mapper, long schemaId) {
    Long lockedSchemaId = mapper.lockLiveSchema(schemaId);
    if (!Objects.equals(schemaId, lockedSchemaId)) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.SCHEMA.name().toLowerCase(Locale.ROOT),
          schemaId);
    }
  }

  private static long chooseDeletedAt(
      ViewRecoveryMapper mapper,
      long viewId,
      long schemaId,
      String viewName,
      long requestedDeletedAt) {
    Long newestDeletedAt = mapper.selectNewestViewDeletedAt(viewId, schemaId, viewName);
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
            && Entity.EntityType.VIEW.name().equals(actual.getEntityType())
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

  private static ViewEntity loadIdempotentlyRestoredView(
      NameIdentifier identifier, long schemaId, EntityDeletionPO deletion) {
    ViewEntity liveView;
    try {
      liveView = getInstance().getViewByIdentifier(identifier);
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    if (!Objects.equals(liveView.id(), deletion.getEntityId())
        || !Objects.equals(deletion.getParentId(), schemaId)) {
      throw new RecoveryConflictException(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "View ID %s is active under a different logical view",
          deletion.getEntityId());
    }
    return liveView;
  }

  private static void validateViewGeneration(
      EntityDeletionPO deletion, @Nullable ViewPO generation) {
    if (generation == null
        || !Objects.equals(generation.getViewId(), deletion.getEntityId())
        || !Objects.equals(generation.getSchemaId(), deletion.getParentId())
        || !Objects.equals(generation.getViewName(), deletion.getEntityName())
        || !Objects.equals(generation.getCurrentVersion(), deletion.getEntityVersion())
        || !Objects.equals(generation.getDeletedAt(), deletion.getDeletedAt())
        || !Objects.equals(generation.getDeletionId(), deletion.getDeletionId())) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
  }

  private static int restoreViewMeta(
      ViewRecoveryMapper mapper,
      EntityDeletionPO deletion,
      ViewPO generation,
      NameIdentifier identifier) {
    try {
      return mapper.restoreViewMeta(
          deletion.getEntityId(),
          deletion.getParentId(),
          deletion.getEntityName(),
          generation.getCurrentVersion(),
          generation.getLastVersion(),
          deletion.getDeletedAt(),
          deletion.getDeletionId());
    } catch (RuntimeException e) {
      try {
        ExceptionUtils.checkSQLException(e, Entity.EntityType.VIEW, identifier.toString());
      } catch (EntityAlreadyExistsException duplicateName) {
        throw new RecoveryConflictException(
            duplicateName,
            RecoveryConflictReason.NAME_OCCUPIED,
            "A live view already occupies name %s",
            identifier);
      } catch (IOException ignored) {
        // Preserve the original persistence exception for non-duplicate SQL failures.
      }
      throw e;
    }
  }

  private static void purgeViewDeletionGeneration(
      ViewRecoveryMapper mapper, EntityDeletionPO deletion) {
    ViewPO generation =
        mapper.selectViewGeneration(
            deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    validateViewGeneration(deletion, generation);
    if (mapper.countCurrentVersionGeneration(
            deletion.getEntityId(),
            deletion.getEntityVersion(),
            deletion.getDeletedAt(),
            deletion.getDeletionId())
        < 1) {
      throw tombstoneChanged(deletion.getDeletionId());
    }
    mapper.hardDeleteOwnerRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteSecurableObjects(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteTagRelations(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    mapper.hardDeleteViewVersions(
        deletion.getEntityId(), deletion.getDeletedAt(), deletion.getDeletionId());
    if (mapper.hardDeleteViewMeta(
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

  private ViewPO updateViewPO(ViewPO oldViewPO, ViewEntity newEntity) {
    Long newVersion = oldViewPO.getLastVersion() + 1;
    ViewPO.ViewPOBuilder builder =
        ViewPO.builder()
            .withMetalakeId(oldViewPO.getMetalakeId())
            .withCatalogId(oldViewPO.getCatalogId())
            .withSchemaId(oldViewPO.getSchemaId())
            .withCurrentVersion(newVersion)
            .withLastVersion(newVersion);
    return buildViewPO(newEntity, builder, newVersion.intValue());
  }

  private ViewPO getViewPOByIdentifier(NameIdentifier identifier) {
    NameIdentifierUtil.checkView(identifier);
    ViewPO viewPO =
        SessionUtils.getWithoutCommit(
            ViewMetaMapper.class,
            mapper -> POStorageReadRouting.getPO(mapper, identifier, ops, Entity.EntityType.VIEW));
    if (viewPO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.VIEW.name().toLowerCase(),
          identifier.name());
    }

    return viewPO;
  }

  private List<ViewPO> listViewPOs(Namespace namespace) {
    return SessionUtils.getWithoutCommit(
        ViewMetaMapper.class,
        mapper -> POStorageReadRouting.listPOs(mapper, namespace, ops, Entity.EntityType.VIEW));
  }
}
