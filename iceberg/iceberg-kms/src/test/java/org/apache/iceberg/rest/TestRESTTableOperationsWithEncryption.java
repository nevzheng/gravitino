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
package org.apache.iceberg.rest;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.iceberg.Files;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.encryption.EncryptingFileIO;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.encryption.EncryptionUtil;
import org.apache.iceberg.encryption.KeyManagementClient;
import org.apache.iceberg.encryption.NativeEncryptionKeyMetadata;
import org.apache.iceberg.encryption.PlaintextEncryptionManager;
import org.apache.iceberg.encryption.StandardEncryptionManager;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class TestRESTTableOperationsWithEncryption {

  private static final String TABLE_KEY_ID = "customer-pii-v1";
  private static final String TABLE_PATH = "/v1/namespaces/customer_data/tables/customers";

  @Test
  void testPlaintextTableUsesOriginalFileIO(@TempDir Path tempDir) {
    TableMetadata metadata = tableMetadata(tempDir, false);
    FileIO fileIO = Mockito.mock(FileIO.class);
    RESTTableOperationsWithEncryption operations =
        operations(new CapturingRESTClient(metadata), fileIO, metadata, null);

    Assertions.assertSame(PlaintextEncryptionManager.instance(), operations.encryption());
    Assertions.assertSame(fileIO, operations.io());
  }

  @Test
  void testEncryptedFileRoundTripAndCommitPersistsKeys(@TempDir Path tempDir) throws Exception {
    TableMetadata metadata = tableMetadata(tempDir, true);
    CapturingRESTClient client = new CapturingRESTClient(metadata);
    FileIO fileIO = Mockito.mock(FileIO.class);
    RESTTableOperationsWithEncryption operations =
        operations(client, fileIO, metadata, new IdentityKeyManagementClient());

    EncryptionManager manager = operations.encryption();
    Assertions.assertInstanceOf(StandardEncryptionManager.class, manager);
    Assertions.assertInstanceOf(EncryptingFileIO.class, operations.io());
    Assertions.assertSame(operations.io(), operations.io());
    operations.io().close();
    Mockito.verify(fileIO).close();

    byte[] payload = "governed-encryption-round-trip".getBytes(StandardCharsets.UTF_8);
    Path encryptedPath = tempDir.resolve("encrypted.bin");
    EncryptedOutputFile encryptedOutput =
        manager.encrypt(Files.localOutput(encryptedPath.toFile()));
    try (PositionOutputStream output = encryptedOutput.encryptingOutputFile().create()) {
      output.write(payload);
    }

    byte[] stored = java.nio.file.Files.readAllBytes(encryptedPath);
    Assertions.assertArrayEquals(
        "AGS1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(stored, 4));

    ByteBuffer keyMetadata =
        EncryptionUtil.setFileLength(encryptedOutput.keyMetadata().buffer(), stored.length);
    InputFile decrypted =
        manager.decrypt(
            EncryptedFiles.encryptedInput(Files.localInput(encryptedPath.toFile()), keyMetadata));
    byte[] roundTrip = new byte[payload.length];
    try (DataInputStream input = new DataInputStream(decrypted.newStream())) {
      input.readFully(roundTrip);
      Assertions.assertEquals(-1, input.read());
    }
    Assertions.assertArrayEquals(payload, roundTrip);

    ((StandardEncryptionManager) manager)
        .addManifestListKeyMetadata((NativeEncryptionKeyMetadata) encryptedOutput.keyMetadata());

    TableMetadata updated =
        TableMetadata.buildFrom(metadata)
            .setProperties(ImmutableMap.of("test.commit", "true"))
            .build();
    operations.commit(metadata, updated);

    Assertions.assertTrue(
        client.request().updates().stream()
            .anyMatch(MetadataUpdate.AddEncryptionKey.class::isInstance));
    Assertions.assertFalse(operations.current().encryptionKeys().isEmpty());
  }

  @Test
  void testEncryptedTableRequiresKms(@TempDir Path tempDir) {
    TableMetadata metadata = tableMetadata(tempDir, true);
    RESTTableOperationsWithEncryption operations =
        operations(new CapturingRESTClient(metadata), Mockito.mock(FileIO.class), metadata, null);

    IllegalStateException exception =
        Assertions.assertThrows(IllegalStateException.class, operations::encryption);
    Assertions.assertTrue(exception.getMessage().contains("encryption.kms-impl"));
  }

  private static RESTTableOperationsWithEncryption operations(
      RESTClient client, FileIO fileIO, TableMetadata metadata, KeyManagementClient kmsClient) {
    return new RESTTableOperationsWithEncryption(
        client,
        TABLE_PATH,
        Collections::emptyMap,
        Collections::emptyMap,
        fileIO,
        metadata,
        ImmutableSet.of(Endpoint.V1_LOAD_TABLE, Endpoint.V1_UPDATE_TABLE),
        kmsClient);
  }

  private static TableMetadata tableMetadata(Path tempDir, boolean encrypted) {
    Schema schema = new Schema(Types.NestedField.optional(1, "id", Types.LongType.get()));
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
                schema,
                PartitionSpec.unpartitioned(),
                tempDir.resolve("table").toUri().toString(),
                Collections.emptyMap())
            .upgradeToFormatVersion(3);
    if (encrypted) {
      metadata =
          metadata.replaceProperties(
              ImmutableMap.of(TableProperties.ENCRYPTION_TABLE_KEY, TABLE_KEY_ID));
    }

    return TableMetadata.buildFrom(metadata)
        .discardChanges()
        .withMetadataLocation(tempDir.resolve("metadata.json").toUri().toString())
        .build();
  }

  private static final class IdentityKeyManagementClient implements KeyManagementClient {
    private static final long serialVersionUID = 1L;

    @Override
    public ByteBuffer wrapKey(ByteBuffer key, String wrappingKeyId) {
      return copy(key);
    }

    @Override
    public ByteBuffer unwrapKey(ByteBuffer wrappedKey, String wrappingKeyId) {
      return copy(wrappedKey);
    }

    @Override
    public void initialize(Map<String, String> properties) {}

    private static ByteBuffer copy(ByteBuffer source) {
      ByteBuffer duplicate = source.duplicate();
      byte[] bytes = new byte[duplicate.remaining()];
      duplicate.get(bytes);
      return ByteBuffer.wrap(bytes);
    }
  }

  private static final class CapturingRESTClient implements RESTClient {
    private final TableMetadata base;
    private UpdateTableRequest request;

    private CapturingRESTClient(TableMetadata base) {
      this.base = base;
    }

    private UpdateTableRequest request() {
      return request;
    }

    @Override
    public void head(
        String path, Map<String, String> headers, Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      Assertions.assertEquals(TABLE_PATH, path);
      this.request = (UpdateTableRequest) body;

      TableMetadata.Builder committed = TableMetadata.buildFrom(base);
      request.updates().forEach(update -> update.applyTo(committed));
      TableMetadata committedMetadata =
          committed
              .discardChanges()
              .withMetadataLocation(base.metadataFileLocation() + ".next")
              .build();
      return responseType.cast(
          LoadTableResponse.builder().withTableMetadata(committedMetadata).build());
    }

    @Override
    public <T extends RESTResponse> T postForm(
        String path,
        Map<String, String> formData,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}
