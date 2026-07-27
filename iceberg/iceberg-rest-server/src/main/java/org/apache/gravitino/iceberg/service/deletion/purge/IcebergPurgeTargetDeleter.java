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
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;

/** Testable external hard-delete seam invoked once per exact READY-ledger target. */
@FunctionalInterface
public interface IcebergPurgeTargetDeleter {

  /**
   * Hard-deletes one exact URI/version target and verifies the target is absent.
   *
   * <p>An already-absent exact target is success and returns normally. A missing root metadata
   * target alone must never be interpreted as proof that any other target was deleted. When {@code
   * objectVersion} is present, implementations must delete that exact provider version rather than
   * issuing a version-agnostic delete-marker operation.
   *
   * @param context immutable FileIO reconstruction and identity context
   * @param target exact snapshotted physical target
   * @throws IcebergPurgeException classified external failure
   */
  void delete(IcebergDeletionContextPO context, IcebergPurgeTargetPO target)
      throws IcebergPurgeException;
}
