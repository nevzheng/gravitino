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
package org.apache.gravitino.iceberg.service.deletion.testhook;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeException;
import org.apache.gravitino.iceberg.service.deletion.purge.IcebergPurgeTargetDeleter;

/** In-memory fault rules for the explicitly enabled Iceberg deletion integration-test hook. */
public final class IcebergDeletionTestHookController {

  /** Supported deterministic provider-failure classes. */
  public enum Fault {
    /** Retryable provider throttling. */
    THROTTLING(true, "THROTTLED"),

    /** Retryable provider server error. */
    SERVER_ERROR(true, "PROVIDER_SERVER_ERROR"),

    /** Non-retryable provider authorization failure. */
    PERMISSION_DENIED(false, "PERMISSION_DENIED");

    private final boolean retryable;
    private final String reasonCode;

    Fault(boolean retryable, String reasonCode) {
      this.retryable = retryable;
      this.reasonCode = reasonCode;
    }
  }

  private final ConcurrentMap<FaultKey, FaultRule> rules = new ConcurrentHashMap<>();

  /**
   * Installs or replaces one table-scoped purge fault.
   *
   * @param catalogId immutable Gravitino catalog identifier
   * @param namespaceSnapshot encoded immutable Iceberg namespace
   * @param table table name
   * @param fault classified injected failure
   * @param afterSuccessfulTargets number of real successful deletes before injection begins
   */
  public void install(
      long catalogId,
      String namespaceSnapshot,
      String table,
      Fault fault,
      int afterSuccessfulTargets) {
    if (afterSuccessfulTargets < 0) {
      throw new IllegalArgumentException("afterSuccessfulTargets must not be negative");
    }
    FaultKey key = new FaultKey(catalogId, namespaceSnapshot, table);
    rules.put(
        key,
        new FaultRule(
            Objects.requireNonNull(fault, "fault must not be null"), afterSuccessfulTargets));
  }

  /**
   * Idempotently clears one table-scoped purge fault.
   *
   * @param catalogId immutable Gravitino catalog identifier
   * @param namespaceSnapshot encoded immutable Iceberg namespace
   * @param table table name
   */
  public void clear(long catalogId, String namespaceSnapshot, String table) {
    rules.remove(new FaultKey(catalogId, namespaceSnapshot, table));
  }

  /**
   * Decorates the real target deleter with deterministic, table-scoped injection.
   *
   * <p>A rule with {@code afterSuccessfulTargets=1} always delegates the first target to the real
   * deleter and injects the configured failure before the second target. A failed real deletion is
   * not counted as a successful target.
   *
   * @param delegate production target deleter
   * @return fault-aware target deleter
   */
  public IcebergPurgeTargetDeleter decorate(IcebergPurgeTargetDeleter delegate) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    return (context, target) -> {
      if (rules.isEmpty()) {
        delegate.delete(context, target);
        return;
      }
      FaultRule rule = rules.get(key(context));
      if (rule != null && rule.shouldFail()) {
        throw rule.failure();
      }
      delegate.delete(context, target);
      if (rule != null) {
        rule.recordSuccess();
      }
    };
  }

  private static FaultKey key(IcebergDeletionContextPO context) {
    return new FaultKey(
        catalogId(context.getProtectedFileIoRef()),
        context.getIcebergNamespace(),
        context.getIcebergTableName());
  }

  private static long catalogId(String protectedFileIoRef) {
    Objects.requireNonNull(protectedFileIoRef, "protected FileIO reference must not be null");
    int separator =
        Math.max(protectedFileIoRef.lastIndexOf(':'), protectedFileIoRef.lastIndexOf('/'));
    if (separator < 0 || separator == protectedFileIoRef.length() - 1) {
      throw new IllegalArgumentException("Protected FileIO reference has no catalog identity");
    }
    try {
      return Long.parseLong(protectedFileIoRef.substring(separator + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Protected FileIO reference has invalid catalog identity", e);
    }
  }

  private static final class FaultRule {
    private final Fault fault;
    private final int afterSuccessfulTargets;
    private final AtomicInteger successfulTargets = new AtomicInteger();

    private FaultRule(Fault fault, int afterSuccessfulTargets) {
      this.fault = fault;
      this.afterSuccessfulTargets = afterSuccessfulTargets;
    }

    private boolean shouldFail() {
      return successfulTargets.get() >= afterSuccessfulTargets;
    }

    private void recordSuccess() {
      successfulTargets.incrementAndGet();
    }

    private IcebergPurgeException failure() {
      return new IcebergPurgeException(
          fault.retryable, fault.reasonCode, "Injected Iceberg deletion integration-test fault");
    }
  }

  private static final class FaultKey {
    private final long catalogId;
    private final String namespaceSnapshot;
    private final String table;

    private FaultKey(long catalogId, String namespaceSnapshot, String table) {
      this.catalogId = catalogId;
      this.namespaceSnapshot =
          Objects.requireNonNull(namespaceSnapshot, "namespaceSnapshot must not be null");
      this.table = Objects.requireNonNull(table, "table must not be null");
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof FaultKey)) {
        return false;
      }
      FaultKey that = (FaultKey) other;
      return catalogId == that.catalogId
          && namespaceSnapshot.equals(that.namespaceSnapshot)
          && table.equals(that.table);
    }

    @Override
    public int hashCode() {
      return Objects.hash(catalogId, namespaceSnapshot, table);
    }
  }
}
