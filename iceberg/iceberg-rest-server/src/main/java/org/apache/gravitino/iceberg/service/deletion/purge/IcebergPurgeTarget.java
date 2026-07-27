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

package org.apache.gravitino.iceberg.service.deletion.purge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.annotation.Nullable;

/** Immutable physical-object descriptor emitted while snapshotting an Iceberg purge plan. */
public final class IcebergPurgeTarget {

  /** Physical object kinds in child-before-parent cleanup order. */
  public enum Type {
    DATA,
    MANIFEST,
    MANIFEST_LIST,
    STATISTICS,
    METADATA,
    ROOT_METADATA
  }

  private final Type type;
  private final String uri;
  @Nullable private final String objectVersion;
  private final int deleteOrder;

  /**
   * Creates one exact target snapshot.
   *
   * @param type target kind
   * @param uri exact object URI
   * @param objectVersion exact provider version when available
   * @param deleteOrder child-before-parent order; root metadata must be greatest in its plan
   */
  public IcebergPurgeTarget(
      Type type, String uri, @Nullable String objectVersion, int deleteOrder) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.uri = Objects.requireNonNull(uri, "uri must not be null");
    this.objectVersion = objectVersion;
    this.deleteOrder = deleteOrder;
  }

  /** Returns the target kind. */
  public Type type() {
    return type;
  }

  /** Returns the exact object URI. */
  public String uri() {
    return uri;
  }

  /** Returns the exact provider object version when available. */
  @Nullable
  public String objectVersion() {
    return objectVersion;
  }

  /** Returns the child-before-parent delete order. */
  public int deleteOrder() {
    return deleteOrder;
  }

  /**
   * Returns a deterministic target id scoped to one immutable deletion generation.
   *
   * @param deletionId deletion generation identifier
   * @return lowercase SHA-256 digest
   */
  public String targetId(String deletionId) {
    Objects.requireNonNull(deletionId, "deletionId must not be null");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, deletionId);
      update(digest, type.name());
      update(digest, uri);
      update(digest, objectVersion == null ? "" : objectVersion);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }
}
