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

package org.apache.iceberg;

import java.util.Collections;
import java.util.Map;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;

/** Iceberg manifest access needed to snapshot the same physical files as table-drop cleanup. */
public final class GravitinoManifestFiles {

  private GravitinoManifestFiles() {}

  /**
   * Returns paths from every manifest entry, including entries whose status is {@code DELETED}.
   *
   * <p>Iceberg's public {@link ManifestFiles#readPaths} helper intentionally exposes only live
   * entries. Hard table cleanup instead follows {@link CatalogUtil#dropTableData} and must include
   * every entry that remains in a reachable manifest.
   *
   * @param manifest reachable data or delete manifest
   * @param io FileIO used to read the manifest
   * @param specsById table partition specs
   * @return closeable iterable of all referenced content-file paths
   */
  public static CloseableIterable<String> readAllPaths(
      ManifestFile manifest, FileIO io, Map<Integer, PartitionSpec> specsById) {
    ManifestReader<?> reader =
        ManifestFiles.open(manifest, io, specsById).select(Collections.singletonList("file_path"));
    return CloseableIterable.transform(reader.entries(), entry -> entry.file().location());
  }
}
