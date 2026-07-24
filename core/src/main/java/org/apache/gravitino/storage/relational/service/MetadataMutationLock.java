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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.storage.relational.mapper.CatalogRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.SchemaRecoveryMapper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Transactional metalake-then-catalog-then-schema fences for mutable metadata aggregates. */
final class MetadataMutationLock {

  private MetadataMutationLock() {}

  /** Returns the live owning metalake ID for a metadata object, when one exists. */
  @Nullable
  static Long metalakeId(NameIdentifier identifier, Entity.EntityType entityType) {
    switch (entityType) {
      case METALAKE:
        return EntityIdService.getEntityId(identifier, Entity.EntityType.METALAKE);
      case CATALOG:
      case SCHEMA:
      case TABLE:
      case FILESET:
      case TOPIC:
      case FUNCTION:
      case MODEL:
      case VIEW:
      case COLUMN:
        if (identifier.namespace().isEmpty()) {
          throw new IllegalArgumentException(
              "Metadata object does not contain a metalake path: " + identifier);
        }
        return EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0)), Entity.EntityType.METALAKE);
      default:
        return null;
    }
  }

  /** Returns the live owning catalog ID for an aggregate metadata object, when one exists. */
  @Nullable
  static Long catalogId(NameIdentifier identifier, Entity.EntityType entityType) {
    switch (entityType) {
      case CATALOG:
        return EntityIdService.getEntityId(identifier, Entity.EntityType.CATALOG);
      case SCHEMA:
      case TABLE:
      case FILESET:
      case TOPIC:
      case FUNCTION:
      case MODEL:
      case VIEW:
      case COLUMN:
        if (identifier.namespace().length() < 2) {
          throw new IllegalArgumentException(
              "Metadata object does not contain a catalog path: " + identifier);
        }
        return EntityIdService.getEntityId(
            NameIdentifier.of(identifier.namespace().level(0), identifier.namespace().level(1)),
            Entity.EntityType.CATALOG);
      default:
        return null;
    }
  }

  /** Returns the live owning schema ID for an aggregate metadata object, when one exists. */
  @Nullable
  static Long schemaId(NameIdentifier identifier, Entity.EntityType entityType) {
    NameIdentifier schemaIdentifier;
    switch (entityType) {
      case SCHEMA:
        schemaIdentifier = identifier;
        break;
      case TABLE:
      case FILESET:
      case TOPIC:
      case FUNCTION:
      case MODEL:
      case VIEW:
      case COLUMN:
        if (identifier.namespace().length() < 3) {
          throw new IllegalArgumentException(
              "Metadata object does not contain a schema path: " + identifier);
        }
        schemaIdentifier =
            NameIdentifier.of(
                identifier.namespace().level(0),
                identifier.namespace().level(1),
                identifier.namespace().level(2));
        break;
      default:
        return null;
    }
    return EntityIdService.getEntityId(schemaIdentifier, Entity.EntityType.SCHEMA);
  }

  /** Locks live metalakes, then catalogs, then schemas, in deterministic ID order. */
  static void lockMetadataIds(
      Collection<Long> candidateMetalakeIds,
      Collection<Long> candidateCatalogIds,
      Collection<Long> candidateSchemaIds) {
    List<Long> metalakeIds = normalizedIds(candidateMetalakeIds);
    List<Long> catalogIds = normalizedIds(candidateCatalogIds);
    List<Long> schemaIds = normalizedIds(candidateSchemaIds);
    if (!metalakeIds.isEmpty()) {
      SessionUtils.doWithoutCommit(
          MetalakeRecoveryMapper.class,
          mapper -> {
            for (Long metalakeId : metalakeIds) {
              Long locked = mapper.lockLiveMetalake(metalakeId);
              if (!Objects.equals(locked, metalakeId)) {
                throw new TombstoneChangedException(
                    "An owning metalake changed while metadata relations were being updated");
              }
            }
          });
    }
    if (!catalogIds.isEmpty()) {
      SessionUtils.doWithoutCommit(
          CatalogRecoveryMapper.class,
          mapper -> {
            for (Long catalogId : catalogIds) {
              Long locked = mapper.lockLiveCatalog(catalogId);
              if (!Objects.equals(locked, catalogId)) {
                throw new TombstoneChangedException(
                    "An owning catalog changed while metadata relations were being updated");
              }
            }
          });
    }
    if (!schemaIds.isEmpty()) {
      SessionUtils.doWithoutCommit(
          SchemaRecoveryMapper.class,
          mapper -> {
            List<Long> locked = mapper.lockLiveSchemas(schemaIds);
            if (!locked.equals(schemaIds)) {
              throw new TombstoneChangedException(
                  "An owning schema changed while metadata relations were being updated");
            }
          });
    }
  }

  /** Locks one live metalake as the root fence for a metalake-scoped metadata mutation. */
  static void lockMetalakeId(long metalakeId) {
    lockMetadataIds(
        Collections.singletonList(metalakeId), Collections.emptyList(), Collections.emptyList());
  }

  private static List<Long> normalizedIds(Collection<Long> candidateIds) {
    return candidateIds.stream()
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .collect(Collectors.toList());
  }
}
