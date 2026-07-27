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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException.Outcome;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
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
import org.apache.iceberg.catalog.Namespace;
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

  /**
   * Creates the lifecycle coordinator.
   *
   * @param wrapperManager Iceberg catalog wrappers
   * @param config Iceberg REST configuration
   */
  public IcebergTableDeletionLifecycle(
      IcebergCatalogWrapperManager wrapperManager, IcebergConfig config) {
    this(wrapperManager, config, true);
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
    this.wrapperManager = Objects.requireNonNull(wrapperManager, "wrapperManager must not be null");
    this.available = available;
    this.softDeleteEnabled = config.get(IcebergConfig.SOFT_DELETE_ENABLED);
    this.retentionMs = config.get(IcebergConfig.SOFT_DELETE_RETENTION_MS);
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
    String activeNameKey = activeNameKey(metalake, context.catalogName(), identifier);
    long deletedAt = System.currentTimeMillis();
    String deletionId = UUID.randomUUID().toString();
    String requestId = header(context.httpHeaders(), REQUEST_ID_HEADER);
    String correlationId = header(context.httpHeaders(), CORRELATION_ID_HEADER);
    if (StringUtils.isBlank(correlationId)) {
      correlationId = UUID.randomUUID().toString();
    }
    String finalCorrelationId = correlationId;

    NameIdentifier gravitinoIdentifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, context.catalogName(), identifier, HierarchicalSchemaUtil.schemaSeparator());
    SessionUtils.doMultipleWithCommit(
        () -> {
          try {
            TablePO table = TableDeletionService.getInstance().lockLiveTable(gravitinoIdentifier);
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
            TableDeletionService.getInstance().tombstone(table, deletedAt, deletionId);
            EntityDeletionService.getInstance().insert(deletion);
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
          } catch (NoSuchEntityException e) {
            if (EntityDeletionService.getInstance().getByActiveName(activeNameKey) == null) {
              throw failure(Outcome.NOT_FOUND, "Table does not exist");
            }
          }
        });
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
    return EntityDeletionService.getInstance()
            .getByActiveName(activeNameKey(metalake, catalogName, identifier))
        != null;
  }

  /**
   * Lists retained table actions under one existing Iceberg namespace.
   *
   * <p>The storage lookup joins {@code table_meta.deletion_id} to the action primary key, so this
   * method cannot surface an unrelated historical action or a later same-name generation.
   *
   * @param catalogName Iceberg catalog name
   * @param namespace Iceberg namespace
   * @return retained table actions in the namespace
   */
  public List<EntityDeletionPO> listDeleted(String catalogName, Namespace namespace) {
    requireAvailable();
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    long parentId = schemaId(metalake, catalogName, namespace);
    List<EntityDeletionPO> deletions =
        EntityDeletionService.getInstance().listRetainedTables(parentId);
    for (EntityDeletionPO deletion : deletions) {
      validateRoute(
          deletion,
          metalake,
          catalogName,
          TableIdentifier.of(namespace, deletion.getEntityNameSnapshot()));
    }
    return deletions;
  }

  /**
   * Loads the retained action currently referenced by one exact table row.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier exact Iceberg table name
   * @return retained table action
   */
  public EntityDeletionPO getDeleted(String catalogName, TableIdentifier identifier) {
    requireAvailable();
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    long parentId = schemaId(metalake, catalogName, identifier.namespace());
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance().getRetainedTable(parentId, identifier.name());
    if (deletion == null) {
      throw failure(Outcome.NOT_FOUND, "Deleted table does not exist");
    }
    validateRoute(deletion, metalake, catalogName, identifier);
    return deletion;
  }

  /**
   * Returns the active deletion action reserving a routed table name, if one exists.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier routed Iceberg table name
   * @return active deletion action, or {@code null}
   */
  @Nullable
  public EntityDeletionPO findActive(String catalogName, TableIdentifier identifier) {
    if (!available) {
      return null;
    }
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .getByActiveName(activeNameKey(metalake, catalogName, identifier));
    if (deletion != null) {
      validateRoute(deletion, metalake, catalogName, identifier);
    }
    return deletion;
  }

  /**
   * Returns one exact deletion action after validating its routed table snapshots.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier routed Iceberg table name
   * @param deletionId exact opaque deletion identifier
   * @return exact deletion action
   */
  public EntityDeletionPO getAction(
      String catalogName, TableIdentifier identifier, String deletionId) {
    requireAvailable();
    EntityDeletionPO deletion = EntityDeletionService.getInstance().get(deletionId);
    if (deletion == null) {
      throw failure(Outcome.NOT_FOUND, "Deletion action does not exist");
    }
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    validateRoute(deletion, metalake, catalogName, identifier);
    return deletion;
  }

  /**
   * Discovers the recoverable action reserving one routed table name.
   *
   * @param catalogName Iceberg catalog name
   * @param identifier Iceberg table identifier
   * @param serverNow authoritative request time
   * @return exact deletion action
   */
  public EntityDeletionPO discover(String catalogName, TableIdentifier identifier, long serverNow) {
    requireAvailable();
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    EntityDeletionPO deletion =
        EntityDeletionService.getInstance()
            .getByActiveName(activeNameKey(metalake, catalogName, identifier));
    if (deletion == null || deletion.getRetentionExpiresAt() == null) {
      throw failure(Outcome.NOT_FOUND, "No recoverable deletion exists for this table");
    }
    validateRoute(deletion, metalake, catalogName, identifier);
    validateRecoverable(deletion, serverNow);
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

    SessionUtils.doMultipleWithCommit(
        () -> {
          EntityDeletionPO deletion = EntityDeletionService.getInstance().getForUpdate(deletionId);
          if (deletion == null) {
            throw failure(Outcome.NOT_FOUND, "Deletion action does not exist");
          }
          validateRoute(deletion, metalake, context.catalogName(), identifier);

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

          try {
            TableDeletionService.getInstance().restore(deletion, serverNow);
          } catch (IllegalStateException e) {
            throw failure(
                Outcome.CONFLICT,
                "Table cannot be restored because its parent, name, or deletion generation changed");
          }
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
        });

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
        .purgeJobId(deletion.getPurgeJobId())
        .deletedBy(deletion.getDeletedBy())
        .recoverable(isRecoverable(deletion, serverNow))
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
        .namespaceSnapshot(identifier.namespace().toString())
        .entityNameSnapshot(identifier.name())
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
      EntityDeletionPO deletion, String metalake, String catalogName, TableIdentifier identifier) {
    long expectedParentId;
    try {
      expectedParentId = schemaId(metalake, catalogName, identifier.namespace());
    } catch (NoSuchEntityException e) {
      throw failure(Outcome.CONFLICT, "Deletion action parent namespace is no longer live");
    }
    if (!Entity.EntityType.TABLE.name().equals(deletion.getEntityType())
        || !Objects.equals(expectedParentId, deletion.getParentId())
        || !Objects.equals(identifier.namespace().toString(), deletion.getNamespaceSnapshot())
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

  static boolean isRecoverable(EntityDeletionPO deletion, long serverNow) {
    return DELETED.equals(deletion.getState())
        && deletion.getRetentionExpiresAt() != null
        && deletion.getRetentionExpiresAt() > serverNow
        && deletion.getPurgeJobId() == null;
  }

  private static String activeNameKey(
      String metalake, String catalogName, TableIdentifier identifier) {
    String canonical =
        String.join(
            "\u001f",
            Entity.EntityType.TABLE.name(),
            metalake,
            catalogName,
            identifier.namespace().toString(),
            identifier.name());
    return IcebergDeletionETags.sha256(canonical);
  }

  private static long schemaId(String metalake, String catalogName, Namespace namespace) {
    String schemaName =
        IcebergIdentifierUtils.icebergNamespaceToSchemaName(
            namespace, HierarchicalSchemaUtil.schemaSeparator());
    return EntityIdService.getEntityId(
        NameIdentifier.of(metalake, catalogName, schemaName), Entity.EntityType.SCHEMA);
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
}
