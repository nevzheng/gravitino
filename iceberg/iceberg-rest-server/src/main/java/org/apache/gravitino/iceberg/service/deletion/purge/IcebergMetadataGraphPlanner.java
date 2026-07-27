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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.GravitinoManifestFiles;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ReachableFileUtil;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StaticTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.util.PropertyUtil;

/** Streams a complete, child-before-parent target snapshot from saved Iceberg metadata. */
public class IcebergMetadataGraphPlanner implements IcebergPurgePlanner {

  private static final int DATA_ORDER = 10;
  private static final int MANIFEST_ORDER = 20;
  private static final int MANIFEST_LIST_ORDER = 30;
  private static final int STATISTICS_ORDER = 40;
  private static final int METADATA_ORDER = 50;
  private static final int ROOT_METADATA_ORDER = 100;

  private final IcebergPurgeResources resources;

  /**
   * Creates an exact metadata graph planner.
   *
   * @param resources protected FileIO resolver
   */
  public IcebergMetadataGraphPlanner(IcebergPurgeResources resources) {
    this.resources = Objects.requireNonNull(resources, "resources must not be null");
  }

  @Override
  public String snapshot(IcebergDeletionContextPO context, TargetSink sink)
      throws IcebergPurgeException {
    FileIO io = resources.fileIO(context);
    TableMetadata metadata;
    try {
      metadata = TableMetadataParser.read(io, context.getMetadataLocation());
    } catch (NotFoundException e) {
      throw permanent(
          "ROOT_METADATA_MISSING",
          "Saved root metadata is absent; complete physical cleanup cannot be proven");
    } catch (RuntimeException e) {
      resources.invalidate(context);
      throw retryable("ROOT_METADATA_READ_FAILED", "Saved root metadata could not be read");
    }
    if (metadata.uuid() == null
        || !context.getIcebergTableUuid().equals(metadata.uuid().toString())) {
      throw permanent("ICEBERG_UUID_MISMATCH", "Saved root metadata has a different table UUID");
    }

    Table table = new BaseTable(new StaticTableOperations(metadata, io), "durable-purge");
    boolean deleteContentFiles =
        PropertyUtil.propertyAsBoolean(
            metadata.properties(), TableProperties.GC_ENABLED, TableProperties.GC_ENABLED_DEFAULT);
    Set<String> manifests = new LinkedHashSet<>();
    try {
      for (Snapshot snapshot : metadata.snapshots()) {
        for (ManifestFile manifest : snapshot.allManifests(io)) {
          if (!manifests.add(manifest.path())) {
            continue;
          }
          if (deleteContentFiles) {
            try (CloseableIterable<String> paths =
                GravitinoManifestFiles.readAllPaths(manifest, io, metadata.specsById())) {
              for (String path : paths) {
                sink.add(target(IcebergPurgeTarget.Type.DATA, path, DATA_ORDER));
              }
            }
          }
        }
      }
    } catch (NotFoundException e) {
      throw permanent(
          "INCOMPLETE_METADATA_GRAPH",
          "A manifest required to prove the complete physical target set is absent");
    } catch (Exception e) {
      resources.invalidate(context);
      throw retryable("METADATA_GRAPH_READ_FAILED", "The Iceberg metadata graph could not be read");
    }

    manifests.forEach(
        path -> sink.add(target(IcebergPurgeTarget.Type.MANIFEST, path, MANIFEST_ORDER)));
    new LinkedHashSet<>(ReachableFileUtil.manifestListLocations(table))
        .forEach(
            path ->
                sink.add(target(IcebergPurgeTarget.Type.MANIFEST_LIST, path, MANIFEST_LIST_ORDER)));
    new LinkedHashSet<>(ReachableFileUtil.statisticsFilesLocations(table))
        .forEach(
            path -> sink.add(target(IcebergPurgeTarget.Type.STATISTICS, path, STATISTICS_ORDER)));

    Set<String> metadataFiles =
        new LinkedHashSet<>(ReachableFileUtil.metadataFileLocations(table, true));
    metadataFiles.remove(context.getMetadataLocation());
    metadataFiles.forEach(
        path -> sink.add(target(IcebergPurgeTarget.Type.METADATA, path, METADATA_ORDER)));
    return sink.add(
        target(
            IcebergPurgeTarget.Type.ROOT_METADATA,
            context.getMetadataLocation(),
            ROOT_METADATA_ORDER));
  }

  private static IcebergPurgeTarget target(
      IcebergPurgeTarget.Type type, String location, int order) {
    return new IcebergPurgeTarget(type, location, null, order);
  }

  private static IcebergPurgeException permanent(String code, String message) {
    return new IcebergPurgeException(false, code, message);
  }

  private static IcebergPurgeException retryable(String code, String message) {
    return new IcebergPurgeException(true, code, message);
  }
}
