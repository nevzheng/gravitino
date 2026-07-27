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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.io.FileIO;

/** Resolves current server-side catalog configuration and bounded reusable FileIO instances. */
public class IcebergPurgeResources implements AutoCloseable {

  private static final int MAX_OPEN_FILE_IOS = 16;

  private final IcebergPurgeJobStore store;
  private final IcebergCatalogWrapperManager wrapperManager;
  private final LinkedHashMap<String, FileIO> fileIOs =
      new LinkedHashMap<>(MAX_OPEN_FILE_IOS, 0.75f, true);

  /**
   * Creates the resource resolver.
   *
   * @param store deletion action store
   * @param wrapperManager current catalog configuration and credentials
   */
  public IcebergPurgeResources(
      IcebergPurgeJobStore store, IcebergCatalogWrapperManager wrapperManager) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.wrapperManager = Objects.requireNonNull(wrapperManager, "wrapperManager must not be null");
  }

  CatalogWrapperForREST wrapper(IcebergDeletionContextPO context) throws IcebergPurgeException {
    EntityDeletionPO action = store.getAction(context.getDeletionId());
    if (action == null) {
      throw permanent("MISSING_ACTION", "Deletion action is missing");
    }
    String expectedReference = "catalog-id:" + action.getCatalogId();
    if (!expectedReference.equals(context.getProtectedFileIoRef())) {
      throw permanent("FILE_IO_REFERENCE_MISMATCH", "Protected FileIO reference does not match");
    }
    CatalogPO catalog =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class, mapper -> mapper.selectCatalogMetaById(action.getCatalogId()));
    if (catalog == null) {
      throw permanent("CATALOG_UNAVAILABLE", "The saved catalog identity is unavailable");
    }
    try {
      return wrapperManager.getCatalogWrapper(catalog.getCatalogName());
    } catch (RuntimeException e) {
      throw retryable(
          "CATALOG_CONFIGURATION_UNAVAILABLE",
          "Current protected catalog configuration is unavailable");
    }
  }

  synchronized FileIO fileIO(IcebergDeletionContextPO context) throws IcebergPurgeException {
    FileIO existing = fileIOs.get(context.getDeletionId());
    if (existing != null) {
      return existing;
    }

    CatalogWrapperForREST wrapper = wrapper(context);
    FileIO created;
    try {
      created = CatalogUtil.loadFileIO(context.getFileIoImpl(), wrapper.fileIOProperties(), null);
    } catch (RuntimeException e) {
      throw retryable("FILE_IO_UNAVAILABLE", "Protected FileIO could not be initialized");
    }
    fileIOs.put(context.getDeletionId(), created);
    evictOldest();
    return created;
  }

  synchronized void invalidate(IcebergDeletionContextPO context) {
    FileIO stale = fileIOs.remove(context.getDeletionId());
    closeFileIO(stale);
  }

  @Override
  public synchronized void close() {
    for (FileIO fileIO : fileIOs.values()) {
      closeFileIO(fileIO);
    }
    fileIOs.clear();
  }

  private void evictOldest() {
    while (fileIOs.size() > MAX_OPEN_FILE_IOS) {
      Map.Entry<String, FileIO> eldest = fileIOs.entrySet().iterator().next();
      fileIOs.remove(eldest.getKey());
      closeFileIO(eldest.getValue());
    }
  }

  private static void closeFileIO(FileIO fileIO) {
    if (fileIO == null) {
      return;
    }
    try {
      fileIO.close();
    } catch (RuntimeException ignored) {
      // Best-effort close; a later attempt must still rebuild from current protected properties.
    }
  }

  private static IcebergPurgeException permanent(String code, String message) {
    return new IcebergPurgeException(false, code, message);
  }

  private static IcebergPurgeException retryable(String code, String message) {
    return new IcebergPurgeException(true, code, message);
  }
}
