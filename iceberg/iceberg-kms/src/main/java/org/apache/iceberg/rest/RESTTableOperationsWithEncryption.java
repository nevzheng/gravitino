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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.encryption.EncryptedKey;
import org.apache.iceberg.encryption.EncryptingFileIO;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.encryption.EncryptionUtil;
import org.apache.iceberg.encryption.KeyManagementClient;
import org.apache.iceberg.encryption.PlaintextEncryptionManager;
import org.apache.iceberg.encryption.StandardEncryptionManager;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.util.PropertyUtil;

class RESTTableOperationsWithEncryption extends RESTTableOperations {

  private final FileIO fileIO;
  @Nullable private final TableMetadata initialMetadata;
  @Nullable private final KeyManagementClient kmsClient;

  private List<EncryptedKey> encryptedKeys = Collections.emptyList();
  @Nullable private String tableKeyId;
  private int encryptionDekLength;

  @Nullable private EncryptionManager encryptionManager;
  @Nullable private EncryptingFileIO encryptingFileIO;

  RESTTableOperationsWithEncryption(
      RESTClient client,
      String path,
      Supplier<Map<String, String>> readHeaders,
      Supplier<Map<String, String>> mutationHeaders,
      FileIO io,
      TableMetadata current,
      Set<Endpoint> endpoints,
      @Nullable KeyManagementClient kmsClient) {
    super(client, path, readHeaders, mutationHeaders, io, current, endpoints);
    this.fileIO = io;
    this.initialMetadata = current;
    this.kmsClient = kmsClient;
    resetEncryptionState(current, null);
  }

  RESTTableOperationsWithEncryption(
      RESTClient client,
      String path,
      Supplier<Map<String, String>> readHeaders,
      Supplier<Map<String, String>> mutationHeaders,
      FileIO io,
      UpdateType updateType,
      List<MetadataUpdate> createChanges,
      TableMetadata current,
      Set<Endpoint> endpoints,
      @Nullable KeyManagementClient kmsClient) {
    super(
        client,
        path,
        readHeaders,
        mutationHeaders,
        io,
        updateType,
        createChanges,
        current,
        endpoints);
    this.fileIO = io;
    this.initialMetadata = current;
    this.kmsClient = kmsClient;
    resetEncryptionState(current, null);
  }

  @Override
  public synchronized EncryptionManager encryption() {
    if (encryptionManager == null) {
      if (tableKeyId == null) {
        return PlaintextEncryptionManager.instance();
      }

      Preconditions.checkState(
          kmsClient != null,
          "Cannot create an encryption manager without a key management client. "
              + "Consider setting the '%s' catalog property",
          CatalogProperties.ENCRYPTION_KMS_IMPL);
      Map<String, String> encryptionProperties = new HashMap<>();
      encryptionProperties.put(TableProperties.ENCRYPTION_TABLE_KEY, tableKeyId);
      encryptionProperties.put(
          TableProperties.ENCRYPTION_DEK_LENGTH, String.valueOf(encryptionDekLength));
      encryptionManager =
          EncryptionUtil.createEncryptionManager(encryptedKeys, encryptionProperties, kmsClient);
    }

    return encryptionManager;
  }

  @Override
  public synchronized FileIO io() {
    if (tableKeyId == null) {
      return fileIO;
    }

    if (encryptingFileIO == null) {
      encryptingFileIO = EncryptingFileIO.combine(fileIO, encryption());
    }
    return encryptingFileIO;
  }

  @Override
  public synchronized TableMetadata refresh() {
    EncryptionManager previousManager = encryptionManager;
    TableMetadata metadata = super.refresh();
    resetEncryptionState(metadata, previousManager);
    return metadata;
  }

  @Override
  public synchronized void commit(TableMetadata base, TableMetadata metadata) {
    configureEncryptionFromMetadata(metadata);
    EncryptionManager manager = encryption();
    TableMetadata metadataWithEncryptionKeys = addEncryptionKeys(metadata, manager);
    super.commit(base, metadataWithEncryptionKeys);
    resetEncryptionState(current(), manager);
  }

  private static TableMetadata addEncryptionKeys(
      TableMetadata metadata, EncryptionManager manager) {
    if (!(manager instanceof StandardEncryptionManager)) {
      return metadata;
    }

    TableMetadata.Builder builder = TableMetadata.buildFrom(metadata);
    for (EncryptedKey key : EncryptionUtil.encryptionKeys(manager).values()) {
      builder.addEncryptionKey(key);
    }
    return builder.build();
  }

  private void resetEncryptionState(
      @Nullable TableMetadata metadata, @Nullable EncryptionManager previousManager) {
    String previousTableKeyId = tableKeyId;
    if (metadata == null) {
      metadata = initialMetadata;
    }
    if (metadata == null) {
      return;
    }

    String refreshedTableKeyId = metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    Preconditions.checkState(
        previousTableKeyId == null || Objects.equals(previousTableKeyId, refreshedTableKeyId),
        "Cannot change table encryption key ID from %s to %s",
        previousTableKeyId,
        refreshedTableKeyId);

    this.tableKeyId = refreshedTableKeyId;
    this.encryptionDekLength =
        PropertyUtil.propertyAsInt(
            metadata.properties(),
            TableProperties.ENCRYPTION_DEK_LENGTH,
            TableProperties.ENCRYPTION_DEK_LENGTH_DEFAULT);
    this.encryptedKeys =
        metadata.encryptionKeys() == null
            ? new ArrayList<>()
            : new ArrayList<>(metadata.encryptionKeys());

    if (refreshedTableKeyId != null && previousManager instanceof StandardEncryptionManager) {
      Set<String> refreshedKeyIds = new HashSet<>();
      for (EncryptedKey key : encryptedKeys) {
        refreshedKeyIds.add(key.keyId());
      }

      for (EncryptedKey key : EncryptionUtil.encryptionKeys(previousManager).values()) {
        if (refreshedKeyIds.add(key.keyId())) {
          encryptedKeys.add(key);
        }
      }
    }

    this.encryptionManager = null;
    this.encryptingFileIO = null;
  }

  private void configureEncryptionFromMetadata(TableMetadata metadata) {
    String metadataTableKeyId = metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    Preconditions.checkState(
        tableKeyId == null || Objects.equals(tableKeyId, metadataTableKeyId),
        "Cannot change table encryption key ID from %s to %s",
        tableKeyId,
        metadataTableKeyId);

    if (tableKeyId == null && metadataTableKeyId != null) {
      resetEncryptionState(metadata, encryptionManager);
    }
  }
}
