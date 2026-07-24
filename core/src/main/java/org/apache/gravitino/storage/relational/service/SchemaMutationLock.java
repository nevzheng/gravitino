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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.storage.relational.mapper.SchemaRecoveryMapper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Transactional schema-row fences shared by metadata aggregates owned by a schema tree. */
final class SchemaMutationLock {

  private SchemaMutationLock() {}

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

  /** Locks all live owning schema rows in deterministic identifier order. */
  static void lockSchemaIds(Collection<Long> candidateSchemaIds) {
    List<Long> schemaIds =
        candidateSchemaIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    if (schemaIds.isEmpty()) {
      return;
    }
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
