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

import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;

/** External Iceberg target enumeration seam used before any physical delete call. */
@FunctionalInterface
public interface IcebergPurgePlanner {

  /** Receives one target at a time and returns its deterministic durable target id. */
  @FunctionalInterface
  interface TargetSink {
    String add(IcebergPurgeTarget target);
  }

  /**
   * Streams the exact reachable target snapshot and identifies the emitted root metadata target.
   *
   * <p>The implementation must not delete anything. It may stream arbitrarily many targets; the
   * worker bounds persistence chunks. The returned id must be the value returned by {@code sink}
   * for the one ROOT_METADATA target.
   *
   * @param context immutable Iceberg deletion context
   * @param sink streaming target sink
   * @return deterministic id of the emitted root metadata target
   * @throws IcebergPurgeException when target enumeration cannot safely complete
   */
  String snapshot(IcebergDeletionContextPO context, TargetSink sink) throws IcebergPurgeException;
}
