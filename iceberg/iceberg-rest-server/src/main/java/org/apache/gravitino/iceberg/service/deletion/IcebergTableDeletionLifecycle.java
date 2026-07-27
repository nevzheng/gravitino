/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.deletion;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.meta.NamespacedEntityId;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.po.EntityDeletionAuditPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.po.TablePO;
import org.apache.gravitino.storage.relational.po.cache.OperateType;
import org.apache.gravitino.storage.relational.service.EntityDeletionAuditService;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.storage.relational.service.EntityIdService;
import org.apache.gravitino.storage.relational.service.TableDeletionService;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.LoadTableResponse;

/** Transactional Iceberg REST table deletion and UNDROP lifecycle. */
public class IcebergTableDeletionLifecycle {

  /** Persisted lifecycle state for a retained or immediately purgeable action. */
  public static final String DELETED = "DELETED";

  /** Terminal lifecycle state for a successfully restored action. */
  public static final String RESTORED = "RESTORED";

  /** Irreversible cleanup-owned lifecycle state. */
  public static final String PURGING = "PURGING";

  /** Terminal lifecycle state for a purged action receipt. */
  public static final String PURGED = "PURGED";

  /** Initial cleanup status associated with a new deletion action. */
  public static final String CLEANUP_PENDING = "PENDING";

  /** Durable worker type selected at delete time. */
  public static final String PURGE_JOB_TYPE = "ICEBERG_REST_PURGE";

  private static final String REQUEST_ID_HEADER = "X-Request-ID";
  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  private final IcebergCatalogWrapperManager wrapperManager;
  private final boolean available;
  private final boolean softDeleteEnabled;
  private final long retentionMs;
  @Nullable private final IcebergDeletionMetricsSource metricsSource;

  /**
   * Creates the lifecycle coordinator.
   *
   * @param wrapperManager Iceberg catalog wrappers
   * @param config Iceberg REST configuration
   */
  public IcebergTableDeletionLifecycle(
      IcebergCatalogWrapperManager wrapperManager, IcebergConfig config) {
    this(wrapperManager, config, true, null);
  }

  /**
   * Creates the lifecycle coordinator with an explicit relational-storage availability flag.
   *
   * @param wrapperManager Iceberg catalog wrappers
   * @param config Iceberg REST configuration
   * @param available whether the shared relational metadata store is available
   */
  public IcebergTableDeletionLifecycle(
      IcebergCatalogWrapperManager wrapperManager, IcebergConfig config, boolean available) {
    this(wrapperManager, config, available, null);
  }

  /**
   * Creates a lifecycle coordinator that records only committed lifecycle transitions.
   *
   * @param wrapperManager Iceberg catalog wrappers
   * @param config Iceberg REST configuration
   * @param available whether the shared relational metadata store is available
   * @param metricsSource deletion lifecycle metrics registered by the owning REST service, or null
   *     when metrics are not wired by a focused unit test
   */
  public IcebergTableDeletionLifecycle(
      IcebergCatalogWrapperManager wrapperManager,
      IcebergConfig config,
      boolean available,
      @Nullable IcebergDeletionMetricsSource metricsSource) {
    this.wrapperManager = Objects.requireNonNull(wrapperManager, "wrapperManager must not be null");
    this.available = available;
    this.softDeleteEnabled = config.get(IcebergConfig.SOFT_DELETE_ENABLED);
    this.retentionMs = config.get(IcebergConfig.SOFT_DELETE_RETENTION_MS);
    this.metricsSource = metricsSource;
  }

  /**
   * Returns whether this request uses the durable lifecycle instead of the legacy metadata-only
   * drop.
   *
   * @param purgeRequested Iceberg REST purge flag
   * @return whether the lifecycle owns the request
   */
  public boolean manages(boolean purgeRequested) {
    return available && (softDeleteEnabled || purgeRequested);
  }

  /**
   * Persists one complete logical table deletion in a single relational transaction.
   *
   * <p>This method never unregisters the table and never deletes files. The existing table row is
   * retained and hidden by its deletion pointer until exact UNDROP or asynchronous purge.
   *
   * @param context request context
   * @param identifier Iceberg table identifier
   * @param purgeRequested original Iceberg REST purge flag
   */
  public void delete(
      IcebergRequestContext context, TableIdentifier identifier, boolean purgeRequested) {
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, context.catalogName(), identifier, HierarchicalSchemaUtil.schemaSeparator());
    RouteIdentity requestRoute = routeIdentity(gravitinoIdentifier, identifier);
    String activeNameKey = activeNameKey(requestRoute, identifier.name());
    if (EntityDeletionService.getInstance().getByActiveName(activeNameKey) != null) {
      return;
    }

    IcebergCatalogWrapper wrapper = wrapperManager.getCatalogWrapper(context.catalogName());
    long deletedAt = System.currentTimeMillis();
    String deletionId = UUID.randomUUID().toString();
    String requestId = header(context.httpHeaders(), REQUEST_ID_HEADER);
    String correlationId = header(context.httpHeaders(), CORRELATION_ID_HEADER);
    if (StringUtils.isBlank(correlationId)) {
      correlationId = UUID.randomUUID().toString();
    }
    String finalCorrelationId = correlationId;

    AtomicReference<EntityDeletionPO> inserted = new AtomicReference<>();
    try {
      SessionUtils.doMultipleWithCommit(
          () -> {
            TablePO table = TableDeletionService.getInstance().lockLiveTable(gravitinoIdentifier);
            RouteIdentity lockedRoute = routeIdentity(table, identifier);
            if (!requestRoute.equals(lockedRoute)) {
              throw failure(Outcome.CONFLICT, "Table route changed during DELETE");
            }
            TableMetadata metadata = wrapper.loadTableMetadata(identifier);
            validateMetadata(metadata);
            EntityDeletionPO deletion =
                newDeletion(
                    deletionId,
                    table,
                    identifier,
                    activeNameKey,
                    context.userName(),
                    requestId,
                    finalCorrelationId,
                    deletedAt,
                    purgeRequested);
            IcebergDeletionContextPO icebergContext =
                newContext(deletionId, table, identifier, metadata, wrapper, deletedAt);

            TableDeletionService.getInstance().tombstone(table, deletedAt, deletionId);
            EntityDeletionService.getInstance().insert(deletion);
            new IcebergDeletionContextStore().insert(icebergContext);
            EntityDeletionAuditService.getInstance()
                .insert(
                    newAudit(
                        deletion,
                        deletion.getRevision(),
                        "DELETE_ACCEPTED",
                        null,
                        DELETED,
                        null,
                        CLEANUP_PENDING,
                        context.userName(),
                        requestId,
                        finalCorrelationId,
                        deletedAt));
            appendChange(metalake, gravitinoIdentifier, OperateType.DROP);
            inserted.set(deletion);
          });
    } catch (NoSuchEntityException e) {
      if (EntityDeletionService.getInstance().getByActiveName(activeNameKey) != null) {
        return;
      }
      throw e;
    }

    if (inserted.get() == null) {
      throw new IllegalStateException("Deletion transaction committed without an action");
    }
    if (metricsSource != null) {
      metricsSource.recordTombstone();
    }
  }

  /**
   * Returns whether a retained or purge-owned action reserves this name.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier Iceberg table identifier
   * @return whether the name is reserved
   */
  public boolean isNameReserved(String catalogName, TableIdentifier identifier) {
    if (!available) {
      return false;
    }
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, catalogName, identifier, HierarchicalSchemaUtil.schemaSeparator());
    RouteIdentity route = routeIdentity(gravitinoIdentifier, identifier);
    return EntityDeletionService.getInstance()
            .getByActiveName(activeNameKey(route, identifier.name()))
        != null;
  }

  /**
   * Discovers the action currently reserving one routed table name.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier Iceberg table identifier
   * @param serverNow authoritative request time
   * @return current deletion action, including expired and purge-owned actions
   */
  public EntityDeletionPO discover(String catalogName, TableIdentifier identifier, long serverNow) {
    requireAvailable();
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, catalogName, identifier, HierarchicalSchemaUtil.schemaSeparator());
    RouteIdentity route = routeIdentity(gravitinoIdentifier, identifier);
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .getByActiveName(activeNameKey(route, identifier.name()));
    if (deletion == null) {
      deletion =
          EntityDeletionService.getInstance()
              .getLatestByNameWhenFree(
                  activeNameKey(route, identifier.name()), route.parentId, identifier.name());
    }
    if (deletion == null) {
      throw failure(Outcome.NOT_FOUND, "No deletion status exists for this table");
    }
    validateRoute(deletion, route, identifier);
    authorize(gravitinoIdentifier, deletion);
    return deletion;
  }

  /**
   * Loads one exact deletion generation or terminal receipt for a routed table.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier routed Iceberg table name
   * @param deletionId explicit opaque deletion identifier
   * @return exact deletion action or terminal receipt
   */
  public EntityDeletionPO get(String catalogName, TableIdentifier identifier, String deletionId) {
    requireAvailable();
    if (StringUtils.isBlank(deletionId)) {
      throw failure(Outcome.BAD_REQUEST, "deletionId is required");
    }
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, catalogName, identifier, HierarchicalSchemaUtil.schemaSeparator());
    EntityDeletionPO deletion = EntityDeletionService.getInstance().get(deletionId);
    if (deletion == null) {
      throw failure(Outcome.NOT_FOUND, "Deletion action does not exist");
    }
    validateRoute(deletion, routeIdentity(gravitinoIdentifier, identifier), identifier);
    authorize(gravitinoIdentifier, deletion);
    return deletion;
  }

  /**
   * Reactivates one exact table deletion generation in a single relational transaction.
   *
   * @param context request context
   * @param identifier routed Iceberg table name
   * @param deletionId explicit opaque deletion identifier
   * @param acceptedEtag strong ETag token without quotes
   * @param serverNow authoritative request time
   * @return current ordinary Iceberg table response
   */
  public LoadTableResponse undrop(
      IcebergRequestContext context,
      TableIdentifier identifier,
      String deletionId,
      String acceptedEtag,
      long serverNow) {
    requireAvailable();
    if (StringUtils.isBlank(deletionId)) {
      throw failure(Outcome.BAD_REQUEST, "deletionId is required");
    }
    if (StringUtils.isBlank(acceptedEtag)) {
      throw failure(Outcome.PRECONDITION_REQUIRED, "If-Match is required");
    }

    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    String requestId = header(context.httpHeaders(), REQUEST_ID_HEADER);
    String correlationId = header(context.httpHeaders(), CORRELATION_ID_HEADER);
    if (StringUtils.isBlank(correlationId)) {
      correlationId = UUID.randomUUID().toString();
    }
    String finalCorrelationId = correlationId;
    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, context.catalogName(), identifier, HierarchicalSchemaUtil.schemaSeparator());
    RouteIdentity requestRoute = routeIdentity(gravitinoIdentifier, identifier);

    AtomicBoolean restored = new AtomicBoolean();
    SessionUtils.doMultipleWithCommit(
        () -> {
          EntityDeletionPO deletion = EntityDeletionService.getInstance().getForUpdate(deletionId);
          if (deletion == null) {
            throw failure(Outcome.NOT_FOUND, "Deletion action does not exist");
          }
          validateRoute(deletion, requestRoute, identifier);
          authorize(gravitinoIdentifier, deletion);

          if (RESTORED.equals(deletion.getState())) {
            if (!Objects.equals(deletion.getAcceptedRestoreEtag(), acceptedEtag)
                || TableDeletionService.getInstance().getRestoredTable(deletion) == null) {
              throw failure(Outcome.CONFLICT, "Restored deletion no longer owns the live table");
            }
            return;
          }

          validateRecoverable(deletion, serverNow);
          if (!Objects.equals(IcebergDeletionETags.strongTag(deletion, serverNow), acceptedEtag)) {
            throw failure(Outcome.PRECONDITION_FAILED, "Deletion action ETag changed");
          }
          if (!EntityDeletionService.getInstance()
              .restore(deletionId, deletion.getRevision(), serverNow, acceptedEtag)) {
            throw failure(Outcome.PRECONDITION_FAILED, "Deletion action changed during UNDROP");
          }

          TableDeletionService.getInstance().restore(deletion, serverNow);
          EntityDeletionAuditService.getInstance()
              .insert(
                  newAudit(
                      deletion,
                      deletion.getRevision() + 1,
                      "UNDROP_RESTORED",
                      DELETED,
                      RESTORED,
                      CLEANUP_PENDING,
                      null,
                      context.userName(),
                      requestId,
                      finalCorrelationId,
                      serverNow));
          appendChange(metalake, gravitinoIdentifier, OperateType.ALTER);
          restored.set(true);
        });

    if (restored.get() && metricsSource != null) {
      metricsSource.recordUndrop();
    }

    return wrapperManager.getCatalogWrapper(context.catalogName()).loadTable(identifier);
  }

  /**
   * Converts a persisted action to its safe management representation.
   *
   * @param deletion deletion action
   * @param serverNow authoritative request time
   * @return safe action representation
   */
  public IcebergDeletionAction toAction(EntityDeletionPO deletion, long serverNow) {
    return IcebergDeletionAction.builder()
        .deletionId(deletion.getDeletionId())
        .entityId(String.valueOf(deletion.getEntityId()))
        .state(deletion.getState())
        .revision(deletion.getRevision())
        .deletedAt(deletion.getDeletedAt())
        .retentionExpiresAt(deletion.getRetentionExpiresAt())
        .cleanupStatus(deletion.getCleanupStatus())
        .purgeJobId(deletion.getPurgeJobId())
        .cleanupAttemptCount(deletion.getCleanupAttemptCount())
        .cleanupLastError(deletion.getCleanupLastError())
        .deletedBy(deletion.getDeletedBy())
        .recoverable(isRecoverable(deletion, serverNow))
        .restoredAt(deletion.getRestoredAt())
        .purgedAt(deletion.getPurgedAt())
        .build();
  }

  private EntityDeletionPO newDeletion(
      String deletionId,
      TablePO table,
      TableIdentifier identifier,
      String activeNameKey,
      String actor,
      @Nullable String requestId,
      String correlationId,
      long deletedAt,
      boolean purgeRequested) {
    Long retentionExpiresAt = softDeleteEnabled ? Math.addExact(deletedAt, retentionMs) : null;
    return EntityDeletionPO.builder()
        .deletionId(deletionId)
        .entityType(Entity.EntityType.TABLE.name())
        .entityId(table.getTableId())
        .entityVersion(table.getCurrentVersion())
        .metalakeId(table.getMetalakeId())
        .catalogId(table.getCatalogId())
        .parentId(table.getSchemaId())
        .namespaceSnapshot(encodeNamespace(identifier))
        .entityNameSnapshot(identifier.name())
        .nameLookupKey(activeNameKey)
        .activeNameKey(activeNameKey)
        .state(DELETED)
        .revision(0L)
        .deletedAt(deletedAt)
        .retentionExpiresAt(retentionExpiresAt)
        .deletedBy(actor)
        .purgeRequested(purgeRequested)
        .purgeJobType(PURGE_JOB_TYPE)
        .cleanupStatus(CLEANUP_PENDING)
        .cleanupAttemptCount(0)
        .requestId(requestId)
        .correlationId(correlationId)
        .updatedAt(deletedAt)
        .build();
  }

  private static IcebergDeletionContextPO newContext(
      String deletionId,
      TablePO table,
      TableIdentifier identifier,
      TableMetadata metadata,
      IcebergCatalogWrapper wrapper,
      long createdAt) {
    String canonical =
        String.join(
            "\n",
            deletionId,
            identifier.toString(),
            metadata.uuid().toString(),
            metadata.metadataFileLocation(),
            wrapper.fileIOImpl(),
            String.valueOf(table.getCatalogId()));
    return IcebergDeletionContextPO.builder()
        .withDeletionId(deletionId)
        .withIcebergNamespace(encodeNamespace(identifier))
        .withIcebergTableName(identifier.name())
        .withIcebergTableUuid(metadata.uuid().toString())
        .withMetadataLocation(metadata.metadataFileLocation())
        .withFileIoImpl(wrapper.fileIOImpl())
        .withProtectedFileIoRef("catalog-id:" + table.getCatalogId())
        .withContextDigest(IcebergDeletionETags.sha256(canonical))
        .withCreatedAt(createdAt)
        .withUpdatedAt(createdAt)
        .build();
  }

  private static EntityDeletionAuditPO newAudit(
      EntityDeletionPO deletion,
      long actionRevision,
      String event,
      @Nullable String priorState,
      @Nullable String newState,
      @Nullable String priorCleanupStatus,
      @Nullable String newCleanupStatus,
      String actor,
      @Nullable String requestId,
      String correlationId,
      long createdAt) {
    return EntityDeletionAuditPO.builder()
        .auditId(UUID.randomUUID().toString())
        .deletionId(deletion.getDeletionId())
        .entityType(deletion.getEntityType())
        .entityId(deletion.getEntityId())
        .eventType(event)
        .actionRevision(actionRevision)
        .priorState(priorState)
        .newState(newState)
        .priorCleanupStatus(priorCleanupStatus)
        .newCleanupStatus(newCleanupStatus)
        .actor(actor)
        .requestId(requestId)
        .correlationId(correlationId)
        .createdAt(createdAt)
        .build();
  }

  private void requireAvailable() {
    if (!available) {
      throw failure(Outcome.NOT_FOUND, "Deletion lifecycle is not available on this server");
    }
  }

  private static void appendChange(
      String metalake, NameIdentifier identifier, OperateType operateType) {
    SessionUtils.doWithoutCommit(
        EntityChangeLogMapper.class,
        mapper ->
            mapper.insertEntityChange(
                metalake, Entity.EntityType.TABLE.name(), identifier.toString(), operateType));
  }

  private static void validateRoute(
      EntityDeletionPO deletion, RouteIdentity route, TableIdentifier identifier) {
    String expected = activeNameKey(route, identifier.name());
    if (!Entity.EntityType.TABLE.name().equals(deletion.getEntityType())
        || deletion.getMetalakeId() != route.metalakeId
        || deletion.getCatalogId() != route.catalogId
        || deletion.getParentId() != route.parentId
        || !Objects.equals(expected, deletion.getNameLookupKey())
        || !Objects.equals(route.namespaceSnapshot, deletion.getNamespaceSnapshot())
        || !Objects.equals(identifier.name(), deletion.getEntityNameSnapshot())) {
      throw failure(Outcome.CONFLICT, "Deletion action does not match the routed table");
    }
  }

  private static void validateRecoverable(EntityDeletionPO deletion, long serverNow) {
    if (PURGING.equals(deletion.getState()) || PURGED.equals(deletion.getState())) {
      throw failure(Outcome.GONE, "Deletion action has crossed the purge boundary");
    }
    if (!DELETED.equals(deletion.getState())) {
      throw failure(Outcome.CONFLICT, "Deletion action is not recoverable");
    }
    if (deletion.getRetentionExpiresAt() == null || deletion.getRetentionExpiresAt() <= serverNow) {
      throw failure(Outcome.GONE, "Deletion retention has expired");
    }
    if (deletion.getPurgeJobId() != null) {
      throw failure(Outcome.GONE, "Deletion action is owned by purge");
    }
  }

  private static void authorize(NameIdentifier identifier, EntityDeletionPO deletion) {
    if (!MetadataAuthzHelper.checkAccessForExactEntity(
        identifier,
        Entity.EntityType.TABLE,
        AuthorizationExpressionConstants.ICEBERG_DROP_TABLE_AUTHORIZATION_EXPRESSION,
        deletion.getEntityId(),
        TableDeletionService.getInstance().getAuthorizationOwner(deletion))) {
      throw new ForbiddenException("Not authorized to manage this table deletion");
    }
  }

  private static boolean isRecoverable(EntityDeletionPO deletion, long serverNow) {
    return DELETED.equals(deletion.getState())
        && deletion.getRetentionExpiresAt() != null
        && deletion.getRetentionExpiresAt() > serverNow
        && deletion.getPurgeJobId() == null;
  }

  static String activeNameKey(
      long metalakeId, long catalogId, long parentId, TableIdentifier identifier) {
    return activeNameKey(
        new RouteIdentity(metalakeId, catalogId, parentId, encodeNamespace(identifier)),
        identifier.name());
  }

  static String encodeNamespace(TableIdentifier identifier) {
    return IcebergDeletionNamespaceCodec.encode(identifier.namespace().levels());
  }

  private static String activeNameKey(RouteIdentity route, String tableName) {
    String canonical =
        Entity.EntityType.TABLE.name()
            + ':'
            + route.metalakeId
            + ':'
            + route.catalogId
            + ':'
            + route.parentId
            + ':'
            + route.namespaceSnapshot.length()
            + ':'
            + route.namespaceSnapshot
            + ':'
            + tableName.length()
            + ':'
            + tableName;
    return IcebergDeletionETags.sha256(canonical);
  }

  private static RouteIdentity routeIdentity(
      NameIdentifier gravitinoIdentifier, TableIdentifier icebergIdentifier) {
    NamespacedEntityId schemaIds =
        EntityIdService.getEntityIds(
            NameIdentifier.of(gravitinoIdentifier.namespace().levels()), Entity.EntityType.SCHEMA);
    long[] ancestors = schemaIds.namespaceIds();
    if (ancestors.length != 2) {
      throw new IllegalStateException("Schema route does not contain metalake and catalog IDs");
    }
    return new RouteIdentity(
        ancestors[0], ancestors[1], schemaIds.entityId(), encodeNamespace(icebergIdentifier));
  }

  private static RouteIdentity routeIdentity(TablePO table, TableIdentifier identifier) {
    return new RouteIdentity(
        table.getMetalakeId(),
        table.getCatalogId(),
        table.getSchemaId(),
        encodeNamespace(identifier));
  }

  private static void validateMetadata(TableMetadata metadata) {
    if (metadata == null
        || metadata.uuid() == null
        || StringUtils.isBlank(metadata.metadataFileLocation())) {
      throw new IllegalStateException("Iceberg table identity is incomplete");
    }
  }

  @Nullable
  private static String header(Map<String, String> headers, String name) {
    return headers.entrySet().stream()
        .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static IcebergDeletionException failure(Outcome outcome, String message) {
    return new IcebergDeletionException(outcome, message);
  }

  private static final class RouteIdentity {
    private final long metalakeId;
    private final long catalogId;
    private final long parentId;
    private final String namespaceSnapshot;

    private RouteIdentity(
        long metalakeId, long catalogId, long parentId, String namespaceSnapshot) {
      this.metalakeId = metalakeId;
      this.catalogId = catalogId;
      this.parentId = parentId;
      this.namespaceSnapshot = namespaceSnapshot;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof RouteIdentity)) {
        return false;
      }
      RouteIdentity that = (RouteIdentity) other;
      return metalakeId == that.metalakeId
          && catalogId == that.catalogId
          && parentId == that.parentId
          && Objects.equals(namespaceSnapshot, that.namespaceSnapshot);
    }

    @Override
    public int hashCode() {
      return Objects.hash(metalakeId, catalogId, parentId, namespaceSnapshot);
    }
  }
}
