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
package org.apache.gravitino.recovery;

import com.google.common.io.BaseEncoding;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletionState;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.cache.EntityCache;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.JobTemplateEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.service.EntityDeletionService;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;

/** Coordinates the shared recovery protocol and entity-specific deletion adapters. */
public class RecoverableDeletionManager {

  /** Stable reason returned for tombstones created before deletion records were introduced. */
  public static final String LEGACY_TOMBSTONE = "LEGACY_TOMBSTONE";

  private final long retentionMs;
  private final Clock clock;
  private final RecoverableEntityAdapter<BaseMetalake> metalakeAdapter;
  private final RecoverableEntityAdapter<CatalogEntity> catalogAdapter;
  private final RecoverableEntityAdapter<SchemaEntity> schemaAdapter;
  private final RecoverableEntityAdapter<TableEntity> tableAdapter;
  private final RecoverableEntityAdapter<ViewEntity> viewAdapter;
  private final RecoverableEntityAdapter<FilesetEntity> filesetAdapter;
  private final RecoverableEntityAdapter<TopicEntity> topicAdapter;
  private final RecoverableEntityAdapter<FunctionEntity> functionAdapter;
  private final RecoverableEntityAdapter<ModelEntity> modelAdapter;
  private final RecoverableEntityAdapter<PolicyEntity> policyAdapter;
  private final RecoverableEntityAdapter<UserEntity> userAdapter;
  private final RecoverableEntityAdapter<GroupEntity> groupAdapter;
  private final RecoverableEntityAdapter<RoleEntity> roleAdapter;
  private final RecoverableEntityAdapter<JobTemplateEntity> jobTemplateAdapter;

  /**
   * Creates a recoverable-deletion manager using the system clock.
   *
   * @param retentionMs default retention window in milliseconds
   */
  public RecoverableDeletionManager(long retentionMs) {
    this(retentionMs, Clock.systemUTC(), null);
  }

  /**
   * Creates a recoverable-deletion manager with a local relational entity cache.
   *
   * @param retentionMs default retention window in milliseconds
   * @param entityCache local entity cache to invalidate after a successful restore
   */
  public RecoverableDeletionManager(long retentionMs, @Nullable EntityCache entityCache) {
    this(retentionMs, Clock.systemUTC(), entityCache);
  }

  RecoverableDeletionManager(long retentionMs, Clock clock) {
    this(retentionMs, clock, null);
  }

  private RecoverableDeletionManager(
      long retentionMs, Clock clock, @Nullable EntityCache entityCache) {
    this.retentionMs = retentionMs;
    this.clock = clock;
    this.metalakeAdapter = new MetalakeRecoveryAdapter(entityCache);
    this.catalogAdapter = new CatalogRecoveryAdapter(entityCache);
    this.schemaAdapter = new SchemaRecoveryAdapter(entityCache);
    this.tableAdapter = new TableRecoveryAdapter(entityCache);
    this.viewAdapter = new ViewRecoveryAdapter(entityCache);
    this.filesetAdapter = new FilesetRecoveryAdapter(entityCache);
    this.topicAdapter = new TopicRecoveryAdapter(entityCache);
    this.functionAdapter = new FunctionRecoveryAdapter(entityCache);
    this.modelAdapter = new ModelRecoveryAdapter(entityCache);
    this.policyAdapter = new PolicyRecoveryAdapter(entityCache);
    this.userAdapter = new UserRecoveryAdapter(entityCache);
    this.groupAdapter = new GroupRecoveryAdapter(entityCache);
    this.roleAdapter = new RoleRecoveryAdapter(entityCache);
    this.jobTemplateAdapter = new JobTemplateRecoveryAdapter(entityCache);
  }

  /**
   * Lists independently deleted metalake roots.
   *
   * @param name optional exact metalake name
   * @param id optional exact immutable metalake identifier
   * @return matching deleted metalake generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedMetalakes(@Nullable String name, @Nullable Long id) {
    return listDeleted(metalakeAdapter, Namespace.empty(), name, id);
  }

  /**
   * Loads one exact deleted metalake root representation.
   *
   * @param name original metalake name
   * @param id immutable metalake identifier
   * @return selected metalake deletion generation
   */
  public DeletedEntityDTO getDeletedMetalake(String name, long id) {
    return getDeleted(metalakeAdapter, Namespace.empty(), name, id);
  }

  /**
   * Restores one exact metalake metadata-tree deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores Gravitino metadata only. All descendants carrying the exact root
   * deletion generation are restored atomically; no connector or external authorization system is
   * invoked.
   *
   * @param name original metalake name
   * @param id immutable metalake identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-metalake read
   * @return restored root metalake, or the already-restored root for an idempotent replay
   */
  public BaseMetalake restoreDeletedMetalake(String name, long id, String etag) {
    return restoreDeleted(metalakeAdapter, Namespace.empty(), name, id, etag);
  }

  /**
   * Lists independently deleted catalog roots under one live metalake.
   *
   * @param namespace metalake namespace
   * @param name optional exact catalog name
   * @param id optional exact immutable catalog identifier
   * @return matching deleted catalog generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedCatalogs(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(catalogAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted catalog root representation.
   *
   * @param namespace metalake namespace
   * @param name original catalog name
   * @param id immutable catalog identifier
   * @return selected catalog deletion generation
   */
  public DeletedEntityDTO getDeletedCatalog(Namespace namespace, String name, long id) {
    return getDeleted(catalogAdapter, namespace, name, id);
  }

  /**
   * Restores one exact catalog metadata-tree deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores Gravitino metadata only. All descendants carrying the exact root
   * deletion generation are restored atomically; no connector or external authorization system is
   * invoked.
   *
   * @param namespace metalake namespace
   * @param name original catalog name
   * @param id immutable catalog identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-catalog read
   * @return restored root catalog, or the already-restored root for an idempotent replay
   */
  public CatalogEntity restoreDeletedCatalog(
      Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(catalogAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted schema roots under one live catalog or parent schema.
   *
   * @param namespace catalog namespace
   * @param parentSchema optional full logical parent schema name; {@code null} selects top-level
   *     schemas
   * @param name optional exact full logical schema name
   * @param id optional exact immutable schema identifier
   * @return matching deleted schema generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedSchemas(
      Namespace namespace,
      @Nullable String parentSchema,
      @Nullable String name,
      @Nullable Long id) {
    return listDeleted(schemaAdapter, namespace, parentSchema, name, id);
  }

  /**
   * Loads one exact deleted schema root representation.
   *
   * @param namespace catalog namespace
   * @param name original full logical schema name
   * @param id immutable schema identifier
   * @return selected schema deletion generation
   */
  public DeletedEntityDTO getDeletedSchema(Namespace namespace, String name, long id) {
    return getDeleted(schemaAdapter, namespace, immediateSchemaParent(name), name, id);
  }

  /**
   * Restores one exact schema metadata-tree deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores Gravitino metadata only. When the original deletion cascaded, all
   * descendants carrying that exact deletion generation are restored atomically.
   *
   * @param namespace catalog namespace
   * @param name original full logical schema name
   * @param id immutable schema identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-schema read
   * @return restored root schema, or the already-restored root for an idempotent replay
   */
  public SchemaEntity restoreDeletedSchema(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(schemaAdapter, namespace, immediateSchemaParent(name), name, id, etag);
  }

  /**
   * Lists deleted table generations under a live schema.
   *
   * @param namespace table namespace
   * @param name optional exact table name
   * @param id optional exact immutable table identifier
   * @return matching deleted table generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedTables(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(tableAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted table representation.
   *
   * @param namespace table namespace
   * @param name original table name
   * @param id immutable table identifier
   * @return the selected deletion generation
   * @throws TombstoneNotFoundException if the exact tombstone does not exist under this path
   */
  public DeletedEntityDTO getDeletedTable(Namespace namespace, String name, long id) {
    return getDeleted(tableAdapter, namespace, name, id);
  }

  /**
   * Restores one exact table deletion generation using an optimistic entity tag.
   *
   * @param namespace table namespace
   * @param name original table name
   * @param id immutable table identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-table read
   * @return restored table, or the already-restored table for an idempotent replay
   * @throws TombstoneNotFoundException if no exact deletion was recorded under the path
   * @throws TombstoneChangedException if the optimistic entity tag or deletion state changed
   * @throws TombstoneExpiredException if the selected deletion is no longer recoverable
   * @throws RecoveryConflictException if restoring would change the deletion's meaning
   */
  public TableEntity restoreDeletedTable(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(tableAdapter, namespace, name, id, etag);
  }

  /**
   * Lists deleted view generations under a live schema.
   *
   * @param namespace view namespace
   * @param name optional exact view name
   * @param id optional exact immutable view identifier
   * @return matching deleted view generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedViews(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(viewAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted view representation.
   *
   * @param namespace view namespace
   * @param name original view name
   * @param id immutable view identifier
   * @return selected view deletion generation
   */
  public DeletedEntityDTO getDeletedView(Namespace namespace, String name, long id) {
    return getDeleted(viewAdapter, namespace, name, id);
  }

  /**
   * Restores one exact view metadata deletion generation using an optimistic entity tag.
   *
   * <p>This operation restores Gravitino metadata only. It never invokes or validates a catalog
   * connector or downstream view.
   *
   * @param namespace view namespace
   * @param name original view name
   * @param id immutable view identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-view read
   * @return restored view, or the already-restored view for an idempotent replay
   */
  public ViewEntity restoreDeletedView(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(viewAdapter, namespace, name, id, etag);
  }

  /**
   * Lists deleted fileset generations under a live schema.
   *
   * @param namespace fileset namespace
   * @param name optional exact fileset name
   * @param id optional exact immutable fileset identifier
   * @return matching deleted fileset generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedFilesets(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(filesetAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted fileset representation.
   *
   * @param namespace fileset namespace
   * @param name original fileset name
   * @param id immutable fileset identifier
   * @return selected fileset deletion generation
   */
  public DeletedEntityDTO getDeletedFileset(Namespace namespace, String name, long id) {
    return getDeleted(filesetAdapter, namespace, name, id);
  }

  /**
   * Restores one exact fileset metadata deletion generation using an optimistic entity tag.
   *
   * <p>This operation restores Gravitino metadata only. It never invokes a catalog connector or
   * filesystem, including for managed filesets.
   *
   * @param namespace fileset namespace
   * @param name original fileset name
   * @param id immutable fileset identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-fileset read
   * @return restored fileset, or the already-restored fileset for an idempotent replay
   */
  public FilesetEntity restoreDeletedFileset(
      Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(filesetAdapter, namespace, name, id, etag);
  }

  /**
   * Lists deleted topic generations under a live schema.
   *
   * @param namespace topic namespace
   * @param name optional exact topic name
   * @param id optional exact immutable topic identifier
   * @return matching deleted topic generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedTopics(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(topicAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted topic representation.
   *
   * @param namespace topic namespace
   * @param name original topic name
   * @param id immutable topic identifier
   * @return selected topic deletion generation
   */
  public DeletedEntityDTO getDeletedTopic(Namespace namespace, String name, long id) {
    return getDeleted(topicAdapter, namespace, name, id);
  }

  /**
   * Restores one exact topic metadata deletion generation using an optimistic entity tag.
   *
   * <p>This operation restores Gravitino metadata only. It never invokes or validates a catalog
   * connector or downstream Kafka topic.
   *
   * @param namespace topic namespace
   * @param name original topic name
   * @param id immutable topic identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-topic read
   * @return restored topic, or the already-restored topic for an idempotent replay
   */
  public TopicEntity restoreDeletedTopic(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(topicAdapter, namespace, name, id, etag);
  }

  /**
   * Lists deleted function generations under a live schema.
   *
   * @param namespace function namespace
   * @param name optional exact function name
   * @param id optional exact immutable function identifier
   * @return matching deleted function generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedFunctions(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(functionAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted function representation.
   *
   * @param namespace function namespace
   * @param name original function name
   * @param id immutable function identifier
   * @return selected function deletion generation
   * @throws TombstoneNotFoundException if the exact tombstone does not exist under this path
   */
  public DeletedEntityDTO getDeletedFunction(Namespace namespace, String name, long id) {
    return getDeleted(functionAdapter, namespace, name, id);
  }

  /**
   * Restores one exact function deletion generation using an optimistic entity tag.
   *
   * @param namespace function namespace
   * @param name original function name
   * @param id immutable function identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-function read
   * @return restored function, or the already-restored function for an idempotent replay
   */
  public FunctionEntity restoreDeletedFunction(
      Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(functionAdapter, namespace, name, id, etag);
  }

  /**
   * Lists deleted model generations under a live schema.
   *
   * @param namespace model namespace
   * @param name optional exact model name
   * @param id optional exact immutable model identifier
   * @return matching deleted model generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedModels(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(modelAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted model representation.
   *
   * @param namespace model namespace
   * @param name original model name
   * @param id immutable model identifier
   * @return selected model deletion generation
   * @throws TombstoneNotFoundException if the exact tombstone does not exist under this path
   */
  public DeletedEntityDTO getDeletedModel(Namespace namespace, String name, long id) {
    return getDeleted(modelAdapter, namespace, name, id);
  }

  /**
   * Restores one exact model deletion generation using an optimistic entity tag.
   *
   * @param namespace model namespace
   * @param name original model name
   * @param id immutable model identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-model read
   * @return restored model, or the already-restored model for an idempotent replay
   */
  public ModelEntity restoreDeletedModel(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(modelAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted policy generations under one live metalake.
   *
   * @param namespace policy namespace
   * @param name optional exact policy name
   * @param id optional exact immutable policy identifier
   * @return matching deleted policy generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedPolicies(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(policyAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted policy representation.
   *
   * @param namespace policy namespace
   * @param name original policy name
   * @param id immutable policy identifier
   * @return selected policy deletion generation
   * @throws TombstoneNotFoundException if the exact tombstone does not exist under this path
   */
  public DeletedEntityDTO getDeletedPolicy(Namespace namespace, String name, long id) {
    return getDeleted(policyAdapter, namespace, name, id);
  }

  /**
   * Restores one exact policy deletion generation using an optimistic entity tag.
   *
   * <p>Only the policy base row and its live versions at delete time are restored. Policy
   * associations, ownership, grants, connectors, and external authorization systems are not
   * mutated.
   *
   * @param namespace policy namespace
   * @param name original policy name
   * @param id immutable policy identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-policy read
   * @return restored policy, or the already-restored policy for an idempotent replay
   */
  public PolicyEntity restoreDeletedPolicy(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(policyAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted user generations under one live metalake.
   *
   * @param namespace user namespace
   * @param name optional exact user name
   * @param id optional exact immutable user identifier
   * @return matching deleted user generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedUsers(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(userAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted user representation.
   *
   * @param namespace user namespace
   * @param name original user name
   * @param id immutable user identifier
   * @return selected user deletion generation
   */
  public DeletedEntityDTO getDeletedUser(Namespace namespace, String name, long id) {
    return getDeleted(userAdapter, namespace, name, id);
  }

  /**
   * Restores one exact user metadata deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores only Gravitino's user row and the role-assignment and ownership
   * rows that the same standalone user deletion marked.
   *
   * @param namespace user namespace
   * @param name original user name
   * @param id immutable user identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-user read
   * @return restored user, or the already-restored user for an idempotent replay
   */
  public UserEntity restoreDeletedUser(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(userAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted group generations under one live metalake.
   *
   * @param namespace group namespace
   * @param name optional exact group name
   * @param id optional exact immutable group identifier
   * @return matching deleted group generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedGroups(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(groupAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted group representation.
   *
   * @param namespace group namespace
   * @param name original group name
   * @param id immutable group identifier
   * @return selected group deletion generation
   */
  public DeletedEntityDTO getDeletedGroup(Namespace namespace, String name, long id) {
    return getDeleted(groupAdapter, namespace, name, id);
  }

  /**
   * Restores one exact group metadata deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores only Gravitino's group row and the role-assignment and ownership
   * rows that the same standalone group deletion marked.
   *
   * @param namespace group namespace
   * @param name original group name
   * @param id immutable group identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-group read
   * @return restored group, or the already-restored group for an idempotent replay
   */
  public GroupEntity restoreDeletedGroup(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(groupAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted role generations under one live metalake.
   *
   * @param namespace role namespace
   * @param name optional exact role name
   * @param id optional exact immutable role identifier
   * @return matching deleted role generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedRoles(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(roleAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted role representation.
   *
   * @param namespace role namespace
   * @param name original role name
   * @param id immutable role identifier
   * @return selected role deletion generation
   */
  public DeletedEntityDTO getDeletedRole(Namespace namespace, String name, long id) {
    return getDeleted(roleAdapter, namespace, name, id);
  }

  /**
   * Restores one exact role metadata deletion generation using an optimistic entity tag.
   *
   * <p>The transaction restores only Gravitino's role row and the memberships, grants, and
   * ownership rows that the same standalone role deletion marked. External authorization systems
   * are not invoked.
   *
   * @param namespace role namespace
   * @param name original role name
   * @param id immutable role identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-role read
   * @return restored role, or the already-restored role for an idempotent replay
   */
  public RoleEntity restoreDeletedRole(Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(roleAdapter, namespace, name, id, etag);
  }

  /**
   * Lists independently deleted job-template generations under one live metalake.
   *
   * @param namespace job-template namespace
   * @param name optional exact job-template name
   * @param id optional exact immutable job-template identifier
   * @return matching deleted job-template generations, newest first
   */
  public List<DeletedEntityDTO> listDeletedJobTemplates(
      Namespace namespace, @Nullable String name, @Nullable Long id) {
    return listDeleted(jobTemplateAdapter, namespace, name, id);
  }

  /**
   * Loads one exact deleted job-template representation.
   *
   * @param namespace job-template namespace
   * @param name original job-template name
   * @param id immutable job-template identifier
   * @return selected job-template deletion generation
   */
  public DeletedEntityDTO getDeletedJobTemplate(Namespace namespace, String name, long id) {
    return getDeleted(jobTemplateAdapter, namespace, name, id);
  }

  /**
   * Restores one exact job-template metadata generation using an optimistic entity tag.
   *
   * <p>The transaction restores only the template and terminal job-run rows captured by that
   * template deletion. Executors, staging data, metrics, and external systems are not invoked.
   *
   * @param namespace job-template namespace
   * @param name original job-template name
   * @param id immutable job-template identifier
   * @param etag unquoted strong entity-tag value observed from the exact deleted-template read
   * @return restored job template, or the already-restored template for an idempotent replay
   */
  public JobTemplateEntity restoreDeletedJobTemplate(
      Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(jobTemplateAdapter, namespace, name, id, etag);
  }

  private <E> List<DeletedEntityDTO> listDeleted(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String name,
      @Nullable Long id) {
    return listDeleted(adapter, namespace, null, name, id);
  }

  private <E> List<DeletedEntityDTO> listDeleted(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      @Nullable String name,
      @Nullable Long id) {
    List<RecoveryMetadata.DeletedSnapshot> allTombstones =
        adapter.listDeleted(namespace, parentScope);
    Map<String, GenerationKey> latestLegacyDeletionByName = new HashMap<>();
    allTombstones.forEach(
        tombstone ->
            latestLegacyDeletionByName.putIfAbsent(
                tombstone.name(), new GenerationKey(tombstone.id(), tombstone.deletedAt())));

    List<RecoveryMetadata.DeletedSnapshot> tombstones = allTombstones;
    if (name != null) {
      tombstones =
          tombstones.stream()
              .filter(tombstone -> name.equals(tombstone.name()))
              .collect(Collectors.toList());
    }
    if (id != null) {
      tombstones =
          tombstones.stream()
              .filter(tombstone -> id.equals(tombstone.id()))
              .collect(Collectors.toList());
    }
    if (tombstones.isEmpty()) {
      return List.of();
    }

    Long parentId = allTombstones.get(0).parent().parentId();
    List<EntityDeletionPO> deletionRecords =
        EntityDeletionService.getInstance().list(adapter.entityType(), parentId, null, null, null);
    Map<GenerationKey, EntityDeletionPO> deletionRecordByGeneration = new HashMap<>();
    Map<String, EntityDeletionPO> latestDeletionRecordByName = new HashMap<>();
    deletionRecords.forEach(
        deletionRecord -> {
          deletionRecordByGeneration.putIfAbsent(
              new GenerationKey(deletionRecord.getEntityId(), deletionRecord.getDeletedAt()),
              deletionRecord);
          latestDeletionRecordByName.putIfAbsent(deletionRecord.getEntityName(), deletionRecord);
        });

    List<RecoveryMetadata.LiveIdentity> liveInParent =
        adapter.listLiveInParent(namespace, parentId, parentScope);
    List<Long> tombstoneIds =
        tombstones.stream()
            .map(RecoveryMetadata.DeletedSnapshot::id)
            .distinct()
            .collect(Collectors.toList());
    Map<Long, RecoveryMetadata.LiveIdentity> liveById = new HashMap<>();
    adapter.listLiveByIds(tombstoneIds).forEach(live -> liveById.put(live.id(), live));

    List<DeletedEntityDTO> result = new ArrayList<>(tombstones.size());
    for (RecoveryMetadata.DeletedSnapshot tombstone : tombstones) {
      EntityDeletionPO deletionRecord =
          deletionRecordByGeneration.get(new GenerationKey(tombstone.id(), tombstone.deletedAt()));
      EntityDeletionPO latestDeletionRecord = latestDeletionRecordByName.get(tombstone.name());
      boolean latestForName;
      if (deletionRecord != null) {
        latestForName =
            latestDeletionRecord != null
                && deletionRecord.getDeletionId().equals(latestDeletionRecord.getDeletionId());
      } else {
        GenerationKey generation = new GenerationKey(tombstone.id(), tombstone.deletedAt());
        latestForName =
            generation.equals(latestLegacyDeletionByName.get(tombstone.name()))
                && (latestDeletionRecord == null
                    || tombstone.deletedAt() > latestDeletionRecord.getDeletedAt());
      }
      result.add(
          toDTO(
              adapter,
              tombstone,
              deletionRecord,
              latestForName,
              liveInParent,
              liveById.get(tombstone.id())));
    }
    return result;
  }

  private <E> DeletedEntityDTO getDeleted(
      RecoverableEntityAdapter<E> adapter, Namespace namespace, String name, long id) {
    return getDeleted(adapter, namespace, null, name, id);
  }

  private <E> DeletedEntityDTO getDeleted(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id) {
    List<DeletedEntityDTO> deleted = listDeleted(adapter, namespace, parentScope, name, id);
    if (deleted.size() != 1) {
      throw new TombstoneNotFoundException(
          "No deleted %s named %s with ID %d exists under %s",
          adapter.recoveryType().value(), name, id, namespace);
    }
    return deleted.get(0);
  }

  private <E> E restoreDeleted(
      RecoverableEntityAdapter<E> adapter, Namespace namespace, String name, long id, String etag) {
    return restoreDeleted(adapter, namespace, null, name, id, etag);
  }

  private <E> E restoreDeleted(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id,
      String etag) {
    NameIdentifier identifier = NameIdentifier.of(namespace, name);
    if (adapter.rootScoped()) {
      return TreeLockUtils.doWithRootTreeLock(
          LockType.WRITE,
          () -> restoreAndInvalidate(adapter, namespace, parentScope, name, id, etag, identifier));
    }
    return TreeLockUtils.doWithTreeLock(
        identifier,
        LockType.WRITE,
        () -> restoreAndInvalidate(adapter, namespace, parentScope, name, id, etag, identifier));
  }

  private <E> E restoreAndInvalidate(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id,
      String etag,
      NameIdentifier identifier) {
    E restored = restoreDeletedLocked(adapter, namespace, parentScope, name, id, etag, identifier);
    adapter.invalidate(identifier);
    return restored;
  }

  private <E> E restoreDeletedLocked(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id,
      String etag,
      NameIdentifier identifier) {
    EntityDeletionService deletionService = EntityDeletionService.getInstance();
    EntityDeletionPO selected = loadDeletionRecordFromEtag(deletionService, etag);
    if (selected == null) {
      handleUnmatchedEtag(adapter, namespace, parentScope, name, id, etag);
    }
    validateDeletionRecordTarget(adapter, selected, name, id, namespace);

    try {
      RecoveryMetadata.ParentIdentity parent =
          resolveCurrentParent(adapter, namespace, parentScope, selected);
      RecoveryMetadata.LiveIdentity globallyLive = findLiveById(adapter, id);

      if (selected.getState() == DeletionState.RESTORED) {
        E replay =
            tryLoadCompletedRestoreReplay(
                adapter, namespace, parentScope, name, id, etag, deletionService, selected);
        if (replay == null) {
          throw tombstoneChanged(selected);
        }
        return replay;
      }

      List<DeletedEntityDTO> tombstones = listDeleted(adapter, namespace, parentScope, name, id);
      if (tombstones.isEmpty()) {
        validateGloballyLiveIdentity(adapter, globallyLive, parent.parentId(), name, id, false);
        if (selected.getState() == DeletionState.PURGED
            || effectiveExpiresAt(selected) <= clock.millis()) {
          throw new TombstoneExpiredException(
              "Deletion generation %s is no longer recoverable", selected.getDeletionId());
        }
        throw tombstoneChanged(selected);
      }
      if (tombstones.size() != 1) {
        throw tombstoneChanged(selected);
      }
      DeletedEntityDTO tombstone = tombstones.get(0);
      if (!selected.getDeletionId().equals(tombstone.getDeletionId())) {
        throw tombstoneChanged(selected);
      }
      if (!tombstone.getLatestForName()) {
        throw recoveryConflict(
            RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
            "A newer deletion generation exists for %s %s",
            adapter.recoveryType().value(),
            name);
      }
      if (!tombstone.getRestorable()) {
        throw restoreBlocked(tombstone, selected);
      }
      if (!etag.equals(tombstone.getEtag())) {
        throw tombstoneChanged(selected);
      }

      return adapter.restoreAtomically(
          identifier, selected, clock.millis(), etag, effectiveExpiresAt(selected));
    } catch (TombstoneChangedException
        | TombstoneExpiredException
        | RecoveryConflictException failure) {
      EntityDeletionPO completed = deletionService.get(selected.getDeletionId());
      E replay =
          tryLoadCompletedRestoreReplay(
              adapter, namespace, parentScope, name, id, etag, deletionService, completed);
      if (replay != null) {
        return replay;
      }
      throw failure;
    }
  }

  @Nullable
  private <E> E tryLoadCompletedRestoreReplay(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id,
      String etag,
      EntityDeletionService deletionService,
      @Nullable EntityDeletionPO completed) {
    if (completed == null
        || completed.getState() != DeletionState.RESTORED
        || !etag.equals(completed.getRestoreEtag())) {
      return null;
    }
    validateDeletionRecordTarget(adapter, completed, name, id, namespace);
    RecoveryMetadata.ParentIdentity parent =
        resolveCurrentParent(adapter, namespace, parentScope, completed);
    if (!isLatestForName(adapter, deletionService, parent.parentId(), name, completed)) {
      throw recoveryConflict(
          RecoveryConflictReason.NOT_LATEST_TOMBSTONE,
          "A newer deletion generation exists for %s %s",
          adapter.recoveryType().value(),
          name);
    }
    validateGloballyLiveIdentity(
        adapter, findLiveById(adapter, id), parent.parentId(), name, id, true);
    return loadIdempotentlyRestored(adapter, namespace, name, id, completed);
  }

  private <E> void handleUnmatchedEtag(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      String name,
      long id,
      String etag) {
    List<DeletedEntityDTO> tombstones;
    try {
      tombstones = listDeleted(adapter, namespace, parentScope, name, id);
    } catch (NoSuchEntityException e) {
      throw new TombstoneNotFoundException(
          e,
          "No deleted %s named %s with ID %d exists under %s",
          adapter.recoveryType().value(),
          name,
          id,
          namespace);
    }
    if (!tombstones.isEmpty()) {
      DeletedEntityDTO tombstone = tombstones.get(0);
      if (etag.equals(tombstone.getEtag()) && LEGACY_TOMBSTONE.equals(tombstone.getReason())) {
        throw new RecoveryConflictException(
            RecoveryConflictReason.LEGACY_TOMBSTONE,
            "Legacy deletion generation %s has no complete deletion record",
            tombstone.getDeletionId());
      }
      throw new TombstoneChangedException(
          "If-Match does not identify the current deletion generation for %s %s with ID %s",
          adapter.recoveryType().value(), name, id);
    }
    throw new TombstoneNotFoundException(
        "No deleted %s named %s with ID %d exists under %s",
        adapter.recoveryType().value(), name, id, namespace);
  }

  @Nullable
  private EntityDeletionPO loadDeletionRecordFromEtag(
      EntityDeletionService deletionService, String etag) {
    String prefix = "deletion-";
    String representationMarker = "-representation-";
    int representationIndex = etag.lastIndexOf(representationMarker);
    if (!etag.startsWith(prefix)
        || representationIndex <= prefix.length()
        || representationIndex + representationMarker.length() >= etag.length()) {
      return null;
    }
    String deletionId = etag.substring(prefix.length(), representationIndex);
    return deletionService.get(deletionId);
  }

  private <E> void validateDeletionRecordTarget(
      RecoverableEntityAdapter<E> adapter,
      EntityDeletionPO deletion,
      String name,
      long id,
      Namespace namespace) {
    if (!adapter.entityType().name().equals(deletion.getEntityType())
        || deletion.getEntityId() != id
        || !name.equals(deletion.getEntityName())) {
      throw new TombstoneNotFoundException(
          "No deleted %s named %s with ID %d exists under %s",
          adapter.recoveryType().value(), name, id, namespace);
    }
  }

  private <E> RecoveryMetadata.ParentIdentity resolveCurrentParent(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      @Nullable String parentScope,
      EntityDeletionPO deletion) {
    RecoveryMetadata.ParentIdentity parent;
    try {
      parent = adapter.resolveCurrentParent(namespace, parentScope, deletion);
    } catch (NoSuchEntityException e) {
      throw new RecoveryConflictException(
          e,
          RecoveryConflictReason.PARENT_CHANGED,
          "The original parent for deletion generation %s no longer exists",
          deletion.getDeletionId());
    }
    if (!Objects.equals(deletion.getMetalakeId(), parent.metalakeId())
        || !Objects.equals(deletion.getCatalogId(), parent.catalogId())
        || !Objects.equals(deletion.getParentId(), parent.parentId())) {
      throw recoveryConflict(
          RecoveryConflictReason.PARENT_CHANGED,
          "The parent for deletion generation %s was replaced",
          deletion.getDeletionId());
    }
    return parent;
  }

  private <E> boolean isLatestForName(
      RecoverableEntityAdapter<E> adapter,
      EntityDeletionService deletionService,
      @Nullable Long parentId,
      String name,
      EntityDeletionPO observed) {
    List<EntityDeletionPO> generations =
        deletionService.list(adapter.entityType(), parentId, name, null, null);
    return !generations.isEmpty()
        && observed.getDeletionId().equals(generations.get(0).getDeletionId());
  }

  private <E> E loadIdempotentlyRestored(
      RecoverableEntityAdapter<E> adapter,
      Namespace namespace,
      String name,
      long id,
      EntityDeletionPO deletion) {
    E restored;
    try {
      restored = adapter.loadLive(NameIdentifier.of(namespace, name));
    } catch (NoSuchEntityException e) {
      throw tombstoneChanged(deletion);
    }
    if (adapter.id(restored) != id) {
      throw recoveryConflict(
          RecoveryConflictReason.ENTITY_ID_REUSED,
          "%s ID %s is active under a different logical entity",
          adapter.recoveryType().value(),
          id);
    }
    return restored;
  }

  @Nullable
  private <E> RecoveryMetadata.LiveIdentity findLiveById(
      RecoverableEntityAdapter<E> adapter, long id) {
    List<RecoveryMetadata.LiveIdentity> matching = adapter.listLiveByIds(List.of(id));
    return matching.isEmpty() ? null : matching.get(0);
  }

  private <E> void validateGloballyLiveIdentity(
      RecoverableEntityAdapter<E> adapter,
      @Nullable RecoveryMetadata.LiveIdentity live,
      @Nullable Long parentId,
      String name,
      long id,
      boolean allowExactMatch) {
    if (live == null) {
      return;
    }
    boolean exactMatch =
        live.id() == id && Objects.equals(live.parentId(), parentId) && live.name().equals(name);
    if (allowExactMatch && exactMatch) {
      return;
    }
    throw recoveryConflict(
        RecoveryConflictReason.ENTITY_ID_REUSED,
        "%s ID %s is already active as %s under parent ID %s",
        adapter.recoveryType().value(),
        id,
        live.name(),
        live.parentId());
  }

  private RuntimeException restoreBlocked(DeletedEntityDTO tombstone, EntityDeletionPO deletion) {
    if ("TOMBSTONE_EXPIRED".equals(tombstone.getReason())) {
      return new TombstoneExpiredException(
          "Deletion generation %s expired at %s",
          deletion.getDeletionId(), effectiveExpiresAt(deletion));
    }
    if ("STATE_CHANGED".equals(tombstone.getReason())) {
      return tombstoneChanged(deletion);
    }
    try {
      return recoveryConflict(
          RecoveryConflictReason.valueOf(tombstone.getReason()),
          "Deletion generation %s cannot be restored: %s",
          deletion.getDeletionId(),
          tombstone.getReason());
    } catch (IllegalArgumentException e) {
      return tombstoneChanged(deletion);
    }
  }

  @FormatMethod
  private static RecoveryConflictException recoveryConflict(
      RecoveryConflictReason reason, @FormatString String message, Object... args) {
    return new RecoveryConflictException(reason, message, args);
  }

  private static TombstoneChangedException tombstoneChanged(EntityDeletionPO deletion) {
    return new TombstoneChangedException(
        "Deletion generation %s changed", deletion.getDeletionId());
  }

  private <E> DeletedEntityDTO toDTO(
      RecoverableEntityAdapter<E> adapter,
      RecoveryMetadata.DeletedSnapshot tombstone,
      @Nullable EntityDeletionPO deletionRecord,
      boolean latestForName,
      List<RecoveryMetadata.LiveIdentity> liveInParent,
      @Nullable RecoveryMetadata.LiveIdentity globallyLive) {
    if (deletionRecord == null) {
      String deletionId = String.format("legacy-%d-%d", tombstone.id(), tombstone.deletedAt());
      long expiresAt = Math.addExact(tombstone.deletedAt(), retentionMs);
      String etag =
          representationEtag(
              adapter.recoveryType(),
              String.valueOf(tombstone.id()),
              deletionId,
              tombstone.name(),
              tombstone.deletedAt(),
              expiresAt,
              null,
              tombstone.version(),
              latestForName,
              false,
              LEGACY_TOMBSTONE,
              tombstone.externalId(),
              null);
      return DeletedEntityDTO.builder()
          .withId(String.valueOf(tombstone.id()))
          .withDeletionId(deletionId)
          .withName(tombstone.name())
          .withType(adapter.recoveryType())
          .withDeletedAt(tombstone.deletedAt())
          .withExpiresAt(expiresAt)
          .withVersion(tombstone.version())
          .withEtag(etag)
          .withLatestForName(latestForName)
          .withRestorable(false)
          .withReason(LEGACY_TOMBSTONE)
          .build();
    }

    String reason =
        restoreBlockReason(tombstone, deletionRecord, latestForName, liveInParent, globallyLive);
    boolean restorable = reason == null;
    String etag =
        representationEtag(
            adapter.recoveryType(),
            String.valueOf(tombstone.id()),
            deletionRecord.getDeletionId(),
            tombstone.name(),
            tombstone.deletedAt(),
            effectiveExpiresAt(deletionRecord),
            deletionRecord.getDeletedBy(),
            tombstone.version(),
            latestForName,
            restorable,
            reason,
            tombstone.externalId(),
            deletionRecord);
    return DeletedEntityDTO.builder()
        .withId(String.valueOf(tombstone.id()))
        .withDeletionId(deletionRecord.getDeletionId())
        .withName(tombstone.name())
        .withType(adapter.recoveryType())
        .withDeletedAt(tombstone.deletedAt())
        .withExpiresAt(effectiveExpiresAt(deletionRecord))
        .withDeletedBy(deletionRecord.getDeletedBy())
        .withVersion(tombstone.version())
        .withEtag(etag)
        .withLatestForName(latestForName)
        .withRestorable(restorable)
        .withReason(reason)
        .build();
  }

  @Nullable
  private String restoreBlockReason(
      RecoveryMetadata.DeletedSnapshot tombstone,
      EntityDeletionPO deletionRecord,
      boolean latestForName,
      List<RecoveryMetadata.LiveIdentity> liveInParent,
      @Nullable RecoveryMetadata.LiveIdentity globallyLive) {
    if (globallyLive != null) {
      return "ENTITY_ID_REUSED";
    }
    if (!tombstone.name().equals(deletionRecord.getEntityName())
        || !Objects.equals(tombstone.parent().metalakeId(), deletionRecord.getMetalakeId())
        || !Objects.equals(tombstone.parent().catalogId(), deletionRecord.getCatalogId())
        || !Objects.equals(tombstone.parent().parentId(), deletionRecord.getParentId())
        || !Objects.equals(tombstone.version(), deletionRecord.getEntityVersion())) {
      return "STATE_CHANGED";
    }
    if (deletionRecord.getState() != DeletionState.DELETED) {
      if (deletionRecord.getState() == DeletionState.PURGING) {
        return "PURGE_IN_PROGRESS";
      }
      if (deletionRecord.getState() == DeletionState.PURGED) {
        return "TOMBSTONE_EXPIRED";
      }
      return "STATE_CHANGED";
    }
    if (effectiveExpiresAt(deletionRecord) <= clock.millis()) {
      return "TOMBSTONE_EXPIRED";
    }
    if (!latestForName) {
      return "NOT_LATEST_TOMBSTONE";
    }
    if (liveInParent.stream().anyMatch(live -> live.name().equals(tombstone.name()))) {
      return "NAME_OCCUPIED";
    }
    if (tombstone.externalId() != null
        && liveInParent.stream()
            .anyMatch(live -> Objects.equals(live.externalId(), tombstone.externalId()))) {
      return "EXTERNAL_ID_OCCUPIED";
    }
    return null;
  }

  private static String representationEtag(
      RecoveryEntityType recoveryType,
      String id,
      String deletionId,
      String name,
      long deletedAt,
      long expiresAt,
      @Nullable String deletedBy,
      @Nullable Long version,
      boolean latestForName,
      boolean restorable,
      @Nullable String reason,
      @Nullable String externalId,
      @Nullable EntityDeletionPO deletionRecord) {
    String canonical =
        String.join(
            "|",
            canonicalField(true),
            canonicalField(id),
            canonicalField(deletionId),
            canonicalField(name),
            canonicalField(recoveryType),
            canonicalField(deletedAt),
            canonicalField(expiresAt),
            canonicalField(deletedBy),
            canonicalField(version),
            canonicalField(latestForName),
            canonicalField(restorable),
            canonicalField(reason),
            canonicalField(externalId),
            canonicalField(deletionRecord == null ? null : deletionRecord.getEntityType()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getEntityId()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getMetalakeId()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getCatalogId()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getParentId()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getEntityName()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getDeletedAt()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getExpiresAt()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getDeletedBy()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getEntityVersion()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getAffectedRowCount()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getState()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getRevision()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getRestoredAt()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getRestoreEtag()),
            canonicalField(deletionRecord == null ? null : deletionRecord.getPurgedAt()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return String.format(
          "deletion-%s-representation-%s",
          deletionId, BaseEncoding.base16().lowerCase().encode(digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static String canonicalField(@Nullable Object value) {
    if (value == null) {
      return "-1:";
    }
    String text = String.valueOf(value);
    return text.length() + ":" + text;
  }

  @Nullable
  private static String immediateSchemaParent(String schemaName) {
    String separator = HierarchicalSchemaUtil.schemaSeparator();
    int separatorIndex = schemaName.lastIndexOf(separator);
    return separatorIndex < 0 ? null : schemaName.substring(0, separatorIndex);
  }

  private long effectiveExpiresAt(EntityDeletionPO deletion) {
    return Math.addExact(deletion.getDeletedAt(), retentionMs);
  }

  private static class GenerationKey {
    private final long entityId;
    private final long deletedAt;

    private GenerationKey(long entityId, long deletedAt) {
      this.entityId = entityId;
      this.deletedAt = deletedAt;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof GenerationKey)) {
        return false;
      }
      GenerationKey that = (GenerationKey) other;
      return entityId == that.entityId && deletedAt == that.deletedAt;
    }

    @Override
    public int hashCode() {
      return 31 * Long.hashCode(entityId) + Long.hashCode(deletedAt);
    }
  }
}
