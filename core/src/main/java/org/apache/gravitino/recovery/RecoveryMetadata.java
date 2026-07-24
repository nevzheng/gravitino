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

import javax.annotation.Nullable;

/** Internal, storage-neutral values used by the recoverable-deletion protocol. */
final class RecoveryMetadata {

  private RecoveryMetadata() {}

  static final class ParentIdentity {
    private final long metalakeId;
    @Nullable private final Long catalogId;
    @Nullable private final Long parentId;

    ParentIdentity(long metalakeId, @Nullable Long catalogId, @Nullable Long parentId) {
      this.metalakeId = metalakeId;
      this.catalogId = catalogId;
      this.parentId = parentId;
    }

    long metalakeId() {
      return metalakeId;
    }

    @Nullable
    Long catalogId() {
      return catalogId;
    }

    @Nullable
    Long parentId() {
      return parentId;
    }
  }

  static final class DeletedSnapshot {
    private final long id;
    private final String name;
    private final ParentIdentity parent;
    private final long deletedAt;
    @Nullable private final Long version;

    DeletedSnapshot(
        long id, String name, ParentIdentity parent, long deletedAt, @Nullable Long version) {
      this.id = id;
      this.name = name;
      this.parent = parent;
      this.deletedAt = deletedAt;
      this.version = version;
    }

    long id() {
      return id;
    }

    String name() {
      return name;
    }

    ParentIdentity parent() {
      return parent;
    }

    long deletedAt() {
      return deletedAt;
    }

    @Nullable
    Long version() {
      return version;
    }
  }

  static final class LiveIdentity {
    private final long id;
    @Nullable private final Long parentId;
    private final String name;

    LiveIdentity(long id, @Nullable Long parentId, String name) {
      this.id = id;
      this.parentId = parentId;
      this.name = name;
    }

    long id() {
      return id;
    }

    @Nullable
    Long parentId() {
      return parentId;
    }

    String name() {
      return name;
    }
  }
}
