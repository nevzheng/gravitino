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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;

/** Strong validators for safe, non-secret deletion-action representations. */
public final class IcebergDeletionETags {

  private IcebergDeletionETags() {}

  /**
   * Computes a strong validator token without HTTP quotes.
   *
   * @param deletion deletion action
   * @return strong validator token
   */
  public static String strongTag(EntityDeletionPO deletion) {
    String canonical =
        String.join(
            "\n",
            deletion.getDeletionId(),
            String.valueOf(deletion.getEntityId()),
            deletion.getState(),
            String.valueOf(deletion.getRevision()),
            String.valueOf(deletion.getRetentionExpiresAt()),
            String.valueOf(deletion.getCleanupStatus()),
            String.valueOf(deletion.getPurgeJobId()));
    return "iceberg-deletion-"
        + deletion.getDeletionId()
        + "-r"
        + deletion.getRevision()
        + "-"
        + sha256(canonical).substring(0, 16);
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        result.append(String.format("%02x", b & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
