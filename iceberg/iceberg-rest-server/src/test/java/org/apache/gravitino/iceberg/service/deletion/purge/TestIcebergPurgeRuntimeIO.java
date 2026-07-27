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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper.RegistrationRemoval;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionNamespaceCodec;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.iceberg.service.deletion.purge.po.IcebergPurgeTargetPO;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests concrete target planning, verified deletion, and registration generation fencing. */
class TestIcebergPurgeRuntimeIO {

  private static final String DELETION_ID = "runtime-io";
  private static final String DATA_FILE = "memory://db/t/data/00000-0.parquet";

  @Test
  void testPlansCompleteGraphAndDeletesEveryExactTargetRootLast() throws Exception {
    BaseTable table = tableWithDataFile();
    IcebergDeletionContextPO context = context(table);
    InMemoryFileIO io = (InMemoryFileIO) table.io();
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    when(resources.fileIO(context)).thenReturn(io);
    IcebergMetadataGraphPlanner planner = new IcebergMetadataGraphPlanner(resources);
    List<IcebergPurgeTarget> targets = new ArrayList<>();

    String rootId =
        planner.snapshot(
            context,
            target -> {
              targets.add(target);
              return target.targetId(DELETION_ID);
            });

    Assertions.assertFalse(targets.isEmpty());
    Assertions.assertTrue(
        targets.stream().anyMatch(target -> target.type() == IcebergPurgeTarget.Type.DATA));
    IcebergPurgeTarget root = targets.get(targets.size() - 1);
    Assertions.assertEquals(IcebergPurgeTarget.Type.ROOT_METADATA, root.type());
    Assertions.assertEquals(root.targetId(DELETION_ID), rootId);
    Assertions.assertEquals(
        root.deleteOrder(),
        targets.stream().mapToInt(IcebergPurgeTarget::deleteOrder).max().getAsInt());

    IcebergFileIOPurgeTargetDeleter deleter = new IcebergFileIOPurgeTargetDeleter(resources);
    targets.stream()
        .sorted(Comparator.comparingInt(IcebergPurgeTarget::deleteOrder))
        .forEach(
            target -> {
              IcebergPurgeTargetPO po = po(target);
              Assertions.assertDoesNotThrow(() -> deleter.delete(context, po));
              Assertions.assertFalse(io.fileExists(target.uri()), target.uri());
              Assertions.assertDoesNotThrow(() -> deleter.delete(context, po));
            });
  }

  @Test
  void testGcDisabledRetainsContentFilesButStillPlansMetadata() throws Exception {
    BaseTable table = tableWithDataFile();
    table.updateProperties().set(TableProperties.GC_ENABLED, Boolean.FALSE.toString()).commit();
    IcebergDeletionContextPO context = context(table);
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    when(resources.fileIO(context)).thenReturn(table.io());
    List<IcebergPurgeTarget> targets = new ArrayList<>();

    new IcebergMetadataGraphPlanner(resources)
        .snapshot(
            context,
            target -> {
              targets.add(target);
              return target.targetId(DELETION_ID);
            });

    Assertions.assertTrue(
        targets.stream().noneMatch(target -> target.type() == IcebergPurgeTarget.Type.DATA));
    Assertions.assertTrue(
        targets.stream().anyMatch(target -> target.type() == IcebergPurgeTarget.Type.MANIFEST));
    Assertions.assertTrue(
        targets.stream()
            .anyMatch(target -> target.type() == IcebergPurgeTarget.Type.ROOT_METADATA));
  }

  @Test
  void testPlansContentPathPresentOnlyInDeletedManifestEntry() throws Exception {
    BaseTable table = tableWithDeletedEntry();
    IcebergDeletionContextPO context = context(table);
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    when(resources.fileIO(context)).thenReturn(table.io());
    List<IcebergPurgeTarget> targets = new ArrayList<>();

    new IcebergMetadataGraphPlanner(resources)
        .snapshot(
            context,
            target -> {
              targets.add(target);
              return target.targetId(DELETION_ID);
            });

    Assertions.assertTrue(
        targets.stream()
            .anyMatch(
                target ->
                    target.type() == IcebergPurgeTarget.Type.DATA
                        && DATA_FILE.equals(target.uri())),
        "hard cleanup must include deleted manifest entries just like CatalogUtil.dropTableData");
  }

  @Test
  void testMissingRootCannotBeMistakenForCompletedCleanup() throws Exception {
    IcebergDeletionContextPO context = missingContext();
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    when(resources.fileIO(context)).thenReturn(new InMemoryFileIO());

    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class,
            () -> new IcebergMetadataGraphPlanner(resources).snapshot(context, target -> "unused"));
    Assertions.assertEquals("ROOT_METADATA_MISSING", failure.reasonCode());
    Assertions.assertFalse(failure.retryable());
  }

  @Test
  void testDifferentSameNameGenerationIsNeverUnregistered() throws Exception {
    IcebergDeletionContextPO context = missingContext("db.with.dot", "nested");
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    CatalogWrapperForREST wrapper = mock(CatalogWrapperForREST.class);
    when(resources.wrapper(context)).thenReturn(wrapper);
    when(wrapper.removeRegistrationIfMatches(
            TableIdentifier.of(Namespace.of("db.with.dot", "nested"), "t"),
            context.getIcebergTableUuid(),
            context.getMetadataLocation()))
        .thenReturn(RegistrationRemoval.GENERATION_MISMATCH);

    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class,
            () -> new IcebergExactRegistrationRemover(resources).remove(context));
    Assertions.assertEquals("REGISTRATION_GENERATION_MISMATCH", failure.reasonCode());
    Assertions.assertFalse(failure.retryable());
  }

  @Test
  void testRetryInvalidatesStaleFileIOAndRebuildsFromCurrentProperties() throws Exception {
    IcebergDeletionContextPO context = missingContext();
    CatalogWrapperForREST wrapper = mock(CatalogWrapperForREST.class);
    when(wrapper.fileIOProperties())
        .thenReturn(ImmutableMap.of("credential-generation", "old"))
        .thenReturn(ImmutableMap.of("credential-generation", "new"));
    IcebergPurgeResources resources =
        spy(
            new IcebergPurgeResources(
                mock(IcebergPurgeJobStore.class), mock(IcebergCatalogWrapperManager.class)));
    doReturn(wrapper).when(resources).wrapper(context);

    FileIO first = resources.fileIO(context);
    resources.invalidate(context);
    FileIO refreshed = resources.fileIO(context);

    Assertions.assertNotSame(first, refreshed);
    verify(wrapper, times(2)).fileIOProperties();
    resources.close();
  }

  @Test
  void testProviderFailureInvalidatesFileIOWithoutPersistableDetails() throws Exception {
    IcebergDeletionContextPO context = missingContext();
    IcebergPurgeResources resources = mock(IcebergPurgeResources.class);
    FileIO io = mock(FileIO.class);
    InputFile input = mock(InputFile.class);
    when(resources.fileIO(context)).thenReturn(io);
    when(io.newInputFile("s3://private/path")).thenReturn(input);
    when(input.exists()).thenThrow(new RuntimeException("token=do-not-persist"));
    IcebergPurgeTargetPO target =
        IcebergPurgeTargetPO.builder()
            .withDeletionId(DELETION_ID)
            .withTargetId("target")
            .withPurgeJobId("job")
            .withTargetType("DATA")
            .withTargetUri("s3://private/path")
            .withDeleteOrder(10)
            .withState("RUNNING")
            .withLeaseEpoch(1L)
            .withAttemptCount(1)
            .withCreatedAt(1L)
            .withUpdatedAt(1L)
            .build();

    IcebergPurgeException failure =
        Assertions.assertThrows(
            IcebergPurgeException.class,
            () -> new IcebergFileIOPurgeTargetDeleter(resources).delete(context, target));

    Assertions.assertEquals("FileIO target deletion failed", failure.getMessage());
    Assertions.assertFalse(failure.getMessage().contains("do-not-persist"));
    verify(resources).invalidate(context);
  }

  private static BaseTable tableWithDataFile() {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("test", ImmutableMap.of());
    catalog.createNamespace(Namespace.of("db"));
    TableIdentifier id = TableIdentifier.of(Namespace.of("db"), "t");
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
    Table table = catalog.createTable(id, schema);
    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(DATA_FILE)
            .withFileSizeInBytes(10L)
            .withRecordCount(1L)
            .build();
    table.newAppend().appendFile(dataFile).commit();
    BaseTable base = (BaseTable) catalog.loadTable(id);
    ((InMemoryFileIO) base.io()).addFile(DATA_FILE, new byte[] {1});
    return base;
  }

  private static BaseTable tableWithDeletedEntry() {
    BaseTable table = tableWithDataFile();
    long appendSnapshotId = table.currentSnapshot().snapshotId();
    table.newDelete().deleteFile(DATA_FILE).commit();
    table.expireSnapshots().expireSnapshotId(appendSnapshotId).commit();
    return table;
  }

  private static IcebergDeletionContextPO context(BaseTable table) {
    return IcebergDeletionContextPO.builder()
        .withDeletionId(DELETION_ID)
        .withIcebergNamespace(IcebergDeletionNamespaceCodec.encode(new String[] {"db"}))
        .withIcebergTableName("t")
        .withIcebergTableUuid(table.operations().current().uuid().toString())
        .withMetadataLocation(table.operations().current().metadataFileLocation())
        .withFileIoImpl(InMemoryFileIO.class.getName())
        .withProtectedFileIoRef("catalog-id:1")
        .withContextDigest(String.format("%064d", 1))
        .withCreatedAt(1L)
        .withUpdatedAt(1L)
        .build();
  }

  private static IcebergDeletionContextPO missingContext() {
    return missingContext("db");
  }

  private static IcebergDeletionContextPO missingContext(String... namespace) {
    return IcebergDeletionContextPO.builder()
        .withDeletionId(DELETION_ID)
        .withIcebergNamespace(IcebergDeletionNamespaceCodec.encode(namespace))
        .withIcebergTableName("t")
        .withIcebergTableUuid("00000000-0000-0000-0000-000000000001")
        .withMetadataLocation("memory://db/t/metadata/missing.json")
        .withFileIoImpl(InMemoryFileIO.class.getName())
        .withProtectedFileIoRef("catalog-id:1")
        .withContextDigest(String.format("%064d", 1))
        .withCreatedAt(1L)
        .withUpdatedAt(1L)
        .build();
  }

  private static IcebergPurgeTargetPO po(IcebergPurgeTarget target) {
    return IcebergPurgeTargetPO.builder()
        .withDeletionId(DELETION_ID)
        .withTargetId(target.targetId(DELETION_ID))
        .withPurgeJobId("job")
        .withTargetType(target.type().name())
        .withTargetUri(target.uri())
        .withObjectVersion(target.objectVersion())
        .withDeleteOrder(target.deleteOrder())
        .withState("RUNNING")
        .withLeaseEpoch(1L)
        .withAttemptCount(1)
        .withCreatedAt(1L)
        .withUpdatedAt(1L)
        .build();
  }
}
