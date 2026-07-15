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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.encryption.EncryptionUtil;
import org.apache.iceberg.encryption.KeyManagementClient;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.ResolvingFileIO;
import org.apache.iceberg.util.EnvironmentUtil;

class RESTSessionCatalogWithEncryption extends RESTSessionCatalog {

  private final AtomicReference<Object> hadoopConf;
  @Nullable private KeyManagementClient kmsClient;

  RESTSessionCatalogWithEncryption(Function<Map<String, String>, RESTClient> clientBuilder) {
    this(clientBuilder, new AtomicReference<>());
  }

  private RESTSessionCatalogWithEncryption(
      Function<Map<String, String>, RESTClient> clientBuilder, AtomicReference<Object> hadoopConf) {
    super(clientBuilder, (context, properties) -> newOwnedFileIO(properties, hadoopConf.get()));
    this.hadoopConf = hadoopConf;
  }

  /** {@inheritDoc} */
  @Override
  public void setConf(Object conf) {
    hadoopConf.set(conf);
    super.setConf(conf);
  }

  /** {@inheritDoc} */
  @Override
  public void initialize(String name, Map<String, String> unresolved) {
    Map<String, String> localProperties = EnvironmentUtil.resolveAll(unresolved);
    super.initialize(name, unresolved);

    Map<String, String> mergedProperties = properties();
    if (mergedProperties.containsKey(CatalogProperties.ENCRYPTION_KMS_IMPL)
        || mergedProperties.containsKey(CatalogProperties.ENCRYPTION_KMS_TYPE)) {
      this.kmsClient =
          EncryptionUtil.createKmsClient(validatedKmsProperties(localProperties, mergedProperties));
    }
  }

  static Map<String, String> validatedKmsProperties(
      Map<String, String> localProperties, Map<String, String> mergedProperties) {
    String kmsImplementation = mergedProperties.get(CatalogProperties.ENCRYPTION_KMS_IMPL);
    if (!OpenBaoKeyManagementClient.class.getName().equals(kmsImplementation)) {
      throw new IllegalArgumentException(
          "Unsupported REST-served KMS implementation: " + kmsImplementation);
    }

    String localEndpoint =
        requireLocalProperty(localProperties, OpenBaoKeyManagementClient.ENDPOINT_PROPERTY);
    String localTokenFile =
        requireLocalProperty(localProperties, OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY);
    rejectServerOverride(
        mergedProperties, OpenBaoKeyManagementClient.ENDPOINT_PROPERTY, localEndpoint);
    rejectServerOverride(
        mergedProperties, OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY, localTokenFile);

    Map<String, String> validated = new HashMap<>(mergedProperties);
    validated.put(OpenBaoKeyManagementClient.ENDPOINT_PROPERTY, localEndpoint);
    validated.put(OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY, localTokenFile);
    return validated;
  }

  static FileIO newOwnedFileIO(Map<String, String> properties, @Nullable Object hadoopConf) {
    String fileIOImplementation =
        properties.getOrDefault(CatalogProperties.FILE_IO_IMPL, ResolvingFileIO.class.getName());
    return CatalogUtil.loadFileIO(fileIOImplementation, properties, hadoopConf);
  }

  private static String requireLocalProperty(Map<String, String> properties, String property) {
    String value = properties.get(property);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "KMS property must be configured by the client: " + property);
    }
    return value;
  }

  private static void rejectServerOverride(
      Map<String, String> mergedProperties, String property, String localValue) {
    if (!Objects.equals(localValue, mergedProperties.get(property))) {
      throw new IllegalArgumentException(
          "IRC cannot override client-local KMS property: " + property);
    }
  }

  /** {@inheritDoc} */
  @Override
  protected RESTTableOperations newTableOps(
      RESTClient restClient,
      String path,
      Supplier<Map<String, String>> readHeaders,
      Supplier<Map<String, String>> mutationHeaderSupplier,
      FileIO fileIO,
      TableMetadata current,
      Set<Endpoint> supportedEndpoints) {
    return new RESTTableOperationsWithEncryption(
        restClient,
        path,
        readHeaders,
        mutationHeaderSupplier,
        fileIO,
        current,
        supportedEndpoints,
        kmsClient);
  }

  /** {@inheritDoc} */
  @Override
  protected RESTTableOperations newTableOps(
      RESTClient restClient,
      String path,
      Supplier<Map<String, String>> readHeaders,
      Supplier<Map<String, String>> mutationHeaderSupplier,
      FileIO fileIO,
      RESTTableOperations.UpdateType updateType,
      List<MetadataUpdate> createChanges,
      TableMetadata current,
      Set<Endpoint> supportedEndpoints) {
    return new RESTTableOperationsWithEncryption(
        restClient,
        path,
        readHeaders,
        mutationHeaderSupplier,
        fileIO,
        updateType,
        createChanges,
        current,
        supportedEndpoints,
        kmsClient);
  }

  /** {@inheritDoc} */
  @Override
  public void close() throws IOException {
    IOException failure = null;
    RuntimeException kmsFailure = null;
    try {
      super.close();
    } catch (IOException e) {
      failure = e;
    }

    if (kmsClient != null) {
      try {
        kmsClient.close();
      } catch (RuntimeException e) {
        kmsFailure = e;
      }
    }

    if (failure != null) {
      if (kmsFailure != null) {
        failure.addSuppressed(kmsFailure);
      }
      throw failure;
    }
    if (kmsFailure != null) {
      throw kmsFailure;
    }
  }
}
