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

import java.util.Locale;
import java.util.Objects;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;

/** Deletes and verifies one exact target at a time through the protected catalog FileIO. */
public class IcebergFileIOPurgeTargetDeleter implements IcebergPurgeTargetDeleter {

  private final IcebergPurgeResources resources;

  /**
   * Creates a hard-delete target executor.
   *
   * @param resources protected FileIO resolver
   */
  public IcebergFileIOPurgeTargetDeleter(IcebergPurgeResources resources) {
    this.resources = Objects.requireNonNull(resources, "resources must not be null");
  }

  @Override
  public void delete(IcebergDeletionContextPO context, IcebergPurgeTargetPO target)
      throws IcebergPurgeException {
    if (target.getObjectVersion() != null) {
      throw new IcebergPurgeException(
          false,
          "VERSION_DELETE_UNSUPPORTED",
          "Configured FileIO cannot prove deletion of an exact provider object version");
    }

    FileIO io = resources.fileIO(context);
    try {
      if (!io.newInputFile(target.getTargetUri()).exists()) {
        return;
      }
      io.deleteFile(target.getTargetUri());
      if (io.newInputFile(target.getTargetUri()).exists()) {
        throw new IcebergPurgeException(
            true, "DELETE_NOT_VISIBLE", "Target deletion is not yet verifiably visible");
      }
    } catch (NotFoundException e) {
      // Already absent is success for this exact durable ledger target only.
    } catch (IcebergPurgeException e) {
      if (e.retryable()) {
        resources.invalidate(context);
      }
      throw e;
    } catch (RuntimeException e) {
      resources.invalidate(context);
      String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
      boolean permanent =
          message.contains("access denied")
              || message.contains("permission")
              || message.contains("forbidden")
              || message.contains("unauthorized");
      throw new IcebergPurgeException(
          !permanent,
          permanent ? "ACCESS_DENIED" : "TARGET_DELETE_FAILED",
          permanent ? "FileIO denied target deletion" : "FileIO target deletion failed");
    }
  }
}
