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
package org.apache.gravitino.messaging;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTopicException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.exceptions.TopicAlreadyExistsException;

/**
 * The {@link TopicCatalog} interface defines the public API for managing topic objects in a schema.
 * If the catalog implementation supports topic objects, it should implement this interface.
 */
@Evolving
public interface TopicCatalog {

  /**
   * List the topics in a schema namespace from the catalog.
   *
   * @param namespace A schema namespace.
   * @return An array of topic identifiers in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  NameIdentifier[] listTopics(Namespace namespace) throws NoSuchSchemaException;

  /**
   * Lists retained topic deletion generations in a schema namespace.
   *
   * <p>The optional filters select an exact topic name, immutable topic ID, or both. Returned
   * generations describe Gravitino metadata only and do not assert that a downstream Kafka topic
   * still exists.
   *
   * @param namespace A schema namespace.
   * @param name An exact topic-name filter, or {@code null} for every name.
   * @param id An exact immutable topic-ID filter, or {@code null} for every ID.
   * @return The retained topic deletion generations matching the filters.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws IllegalArgumentException If the namespace or a supplied filter is invalid.
   * @throws UnsupportedOperationException If recoverable topic deletion is not supported.
   */
  default DeletedEntity[] listDeletedTopics(
      Namespace namespace, @Nullable String name, @Nullable String id)
      throws NoSuchSchemaException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedTopics not supported.");
  }

  /**
   * Load topic metadata by {@link NameIdentifier} from the catalog.
   *
   * @param ident A topic identifier.
   * @return The topic metadata.
   * @throws NoSuchTopicException If the topic does not exist.
   */
  Topic loadTopic(NameIdentifier ident) throws NoSuchTopicException;

  /**
   * Loads one exact retained deletion generation for a topic.
   *
   * <p>The topic name and immutable ID must identify the same deleted topic. The returned
   * generation supplies the strong optimistic precondition required by {@link
   * #restoreTopic(NameIdentifier, DeletedEntity)}.
   *
   * @param ident A topic identifier.
   * @param id The immutable topic ID.
   * @return The exact retained topic deletion generation.
   * @throws NoSuchSchemaException If the parent schema does not exist.
   * @throws IllegalArgumentException If the identifier or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws UnsupportedOperationException If recoverable topic deletion is not supported.
   */
  default DeletedEntity loadDeletedTopic(NameIdentifier ident, String id)
      throws NoSuchSchemaException, TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedTopic not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino topic metadata.
   *
   * <p>This operation is metadata-only. It neither validates nor recreates a downstream Kafka
   * topic, which may already have been irreversibly deleted. Replaying an accepted generation is
   * idempotent while the server can still prove that exact restore.
   *
   * <p>After {@link TombstoneChangedException}, callers must reread the same topic path and
   * immutable ID before deciding whether to retry; clients must never substitute a different ID or
   * generation. An unknown transport outcome may replay the same generation. A recovery conflict or
   * expired generation is not retryable as-is.
   *
   * @param ident A topic identifier.
   * @param generation The exact retained deletion generation and optimistic precondition.
   * @return The restored topic metadata.
   * @throws IllegalArgumentException If the identifier, generation type, name, ID, or ETag is
   *     invalid or inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist at this path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If recoverable topic deletion is not supported.
   */
  default Topic restoreTopic(NameIdentifier ident, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restoreTopic not supported.");
  }

  /**
   * Check if a topic exists using an {@link NameIdentifier} from the catalog.
   *
   * @param ident A topic identifier.
   * @return true If the topic exists, false otherwise.
   */
  default boolean topicExists(NameIdentifier ident) {
    try {
      loadTopic(ident);
      return true;
    } catch (NoSuchTopicException e) {
      return false;
    }
  }

  /**
   * Create a topic in the catalog based on the provided details.
   *
   * <p>This method returns the topic as defined by the user without applying all defaults. If you
   * need the topic with default values applied, use the {@link #loadTopic(NameIdentifier)} method
   * after creation.
   *
   * @param ident A topic identifier.
   * @param comment The comment of the topic object. Null is set if no comment is specified.
   * @param dataLayout The message schema of the topic object. Always null because it's not
   *     supported yet.
   * @param properties The properties of the topic object. Empty map is set if no properties are
   *     specified.
   * @return The topic as defined by the caller, without all default values.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws TopicAlreadyExistsException If the topic already exists.
   */
  Topic createTopic(
      NameIdentifier ident, String comment, DataLayout dataLayout, Map<String, String> properties)
      throws NoSuchSchemaException, TopicAlreadyExistsException;

  /**
   * Apply the {@link TopicChange changes} to a topic in the catalog.
   *
   * @param ident A topic identifier.
   * @param changes The changes to apply to the topic.
   * @return The altered topic metadata.
   * @throws NoSuchTopicException If the topic does not exist.
   * @throws IllegalArgumentException If the changes is rejected by the implementation.
   */
  Topic alterTopic(NameIdentifier ident, TopicChange... changes)
      throws NoSuchTopicException, IllegalArgumentException;

  /**
   * Drop a topic from the catalog.
   *
   * @param ident A topic identifier.
   * @return true If the topic is dropped, false if the topic does not exist.
   */
  boolean dropTopic(NameIdentifier ident);
}
