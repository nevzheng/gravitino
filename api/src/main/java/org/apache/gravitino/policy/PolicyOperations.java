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
package org.apache.gravitino.policy;

import javax.annotation.Nullable;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchPolicyException;
import org.apache.gravitino.exceptions.PolicyAlreadyExistsException;
import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;

/**
 * The interface of the policy operations. The policy operations are used to manage policies under a
 * metalake. This interface will be mixed with GravitinoMetalake or GravitinoClient to provide
 * policy operations.
 */
@Evolving
public interface PolicyOperations {

  /**
   * List all the policy names under a metalake.
   *
   * @return The list of policy names.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  String[] listPolicies() throws NoSuchMetalakeException;

  /**
   * List all the policies with detailed information under a metalake.
   *
   * @return The list of policies.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  Policy[] listPolicyInfos() throws NoSuchMetalakeException;

  /**
   * Lists retained deletion generations for policies in this metalake.
   *
   * <p>The optional filters select an exact policy name, immutable policy ID, or both. A returned
   * generation describes Gravitino metadata only; it does not assert that policy state in any
   * external system can be recovered.
   *
   * <p>Implementations that support recoverable policy deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name An exact policy-name filter, or {@code null} to include every name.
   * @param id An exact immutable policy-ID filter, or {@code null} to include every ID.
   * @return The retained policy deletion generations matching the filters.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   * @throws IllegalArgumentException If a supplied filter is invalid.
   * @throws UnsupportedOperationException If recoverable policy deletion is not supported.
   */
  default DeletedEntity[] listDeletedPolicies(@Nullable String name, @Nullable String id)
      throws NoSuchMetalakeException, UnsupportedOperationException {
    throw new UnsupportedOperationException("listDeletedPolicies not supported.");
  }

  /**
   * Get a policy by its name under a metalake.
   *
   * @param name The name of the policy.
   * @return The policy.
   * @throws NoSuchPolicyException If the policy does not exist.
   */
  Policy getPolicy(String name) throws NoSuchPolicyException;

  /**
   * Loads one exact retained deletion generation for a policy.
   *
   * <p>The policy name and immutable {@code id} must identify the same deleted policy. The returned
   * generation supplies the strong optimistic precondition required by {@link
   * #restorePolicy(String, DeletedEntity)}.
   *
   * <p>Implementations that support recoverable policy deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name The policy name.
   * @param id The immutable policy ID.
   * @return The exact retained policy deletion generation.
   * @throws IllegalArgumentException If the name or immutable ID is invalid.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested policy path.
   * @throws UnsupportedOperationException If recoverable policy deletion is not supported.
   */
  default DeletedEntity loadDeletedPolicy(String name, String id)
      throws TombstoneNotFoundException, UnsupportedOperationException {
    throw new UnsupportedOperationException("loadDeletedPolicy not supported.");
  }

  /**
   * Restores one exact retained deletion generation as active Gravitino policy metadata.
   *
   * <p>This operation restores the retained Gravitino policy metadata aggregate selected by the
   * server. It does not validate, recreate, or reconcile policy state in external systems. The
   * generation must come from an exact deleted-policy read and must match the requested name and
   * resource type. Replaying a previously accepted generation is idempotent while the server can
   * still prove that exact restore.
   *
   * <p>If a request fails with {@link TombstoneChangedException}, callers must read the same policy
   * path and immutable ID again before deciding whether to retry; clients must never silently
   * substitute a different ID or generation. An unknown transport outcome may be replayed with the
   * same generation. A recovery conflict or expired generation is not retryable as-is.
   *
   * <p>Implementations that support recoverable policy deletion should override this method. The
   * default implementation throws an {@link UnsupportedOperationException}.
   *
   * @param name The policy name.
   * @param generation The exact retained deletion generation to restore.
   * @return The restored policy metadata.
   * @throws IllegalArgumentException If the name, generation type, ID, or ETag is invalid or
   *     inconsistent.
   * @throws TombstoneNotFoundException If the retained generation does not exist under the
   *     requested policy path.
   * @throws TombstoneExpiredException If the retained generation has expired.
   * @throws TombstoneChangedException If the generation changed after it was read.
   * @throws PreconditionRequiredException If the server requires a missing recovery precondition.
   * @throws RecoveryConflictException If current metadata prevents recovery.
   * @throws UnsupportedOperationException If recoverable policy deletion is not supported.
   */
  default Policy restorePolicy(String name, DeletedEntity generation)
      throws TombstoneNotFoundException, TombstoneExpiredException, TombstoneChangedException,
          PreconditionRequiredException, RecoveryConflictException, UnsupportedOperationException {
    throw new UnsupportedOperationException("restorePolicy not supported.");
  }

  /**
   * Create a policy under a metalake.
   *
   * @param name The name of the policy.
   * @param type The type of the policy.
   * @param comment The comment of the policy.
   * @param enabled Whether the policy is enabled or not.
   * @param content The content of the policy.
   * @return The created policy.
   * @throws PolicyAlreadyExistsException If the policy already exists.
   */
  Policy createPolicy(
      String name, String type, String comment, boolean enabled, PolicyContent content)
      throws PolicyAlreadyExistsException;

  /**
   * Enable a policy under a metalake. If the policy is already enabled, this method does nothing.
   *
   * @param name The name of the policy to enable.
   * @throws NoSuchPolicyException If the policy does not exist.
   */
  void enablePolicy(String name) throws NoSuchPolicyException;

  /**
   * Disable a policy under a metalake. If the policy is already disabled, this method does nothing.
   *
   * @param name The name of the policy to disable.
   * @throws NoSuchPolicyException If the policy does not exist.
   */
  void disablePolicy(String name) throws NoSuchPolicyException;

  /**
   * Alter a policy under a metalake.
   *
   * @param name The name of the policy.
   * @param changes The changes to apply to the policy.
   * @return The altered policy.
   * @throws NoSuchPolicyException If the policy does not exist.
   * @throws IllegalArgumentException If the changes cannot be associated with the policy.
   */
  Policy alterPolicy(String name, PolicyChange... changes)
      throws NoSuchPolicyException, IllegalArgumentException;

  /**
   * Delete a policy under a metalake.
   *
   * @param name The name of the policy.
   * @return True if the policy is deleted, false if the policy does not exist.
   */
  boolean deletePolicy(String name);
}
