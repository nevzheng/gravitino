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
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.encryption.EncryptionUtil;
import org.apache.iceberg.encryption.KeyManagementClient;
import org.apache.iceberg.encryption.KeystoreKeyManagementClient;
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

  /**
   * Builds KMS properties that the engine may use.
   *
   * <p>The catalog may advertise {@code encryption.kms-impl} / {@code encryption.kms-type}. Secret
   * material and client-local coordinates must come from the engine configuration; IRC overrides of
   * those values are rejected.
   */
  static Map<String, String> validatedKmsProperties(
      Map<String, String> localProperties, Map<String, String> mergedProperties) {
    String localType = localProperties.get(CatalogProperties.ENCRYPTION_KMS_TYPE);
    String localImplementation = localProperties.get(CatalogProperties.ENCRYPTION_KMS_IMPL);
    if (localType == null && localImplementation == null) {
      throw new IllegalArgumentException(
          "Client must set "
              + CatalogProperties.ENCRYPTION_KMS_TYPE
              + " or "
              + CatalogProperties.ENCRYPTION_KMS_IMPL);
    }
    rejectBlankSelector(CatalogProperties.ENCRYPTION_KMS_TYPE, localType);
    rejectBlankSelector(CatalogProperties.ENCRYPTION_KMS_IMPL, localImplementation);
    if (localType != null && localImplementation != null) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot set both KMS type (%s) and KMS impl (%s)", localType, localImplementation));
    }

    rejectServerOverride(mergedProperties, CatalogProperties.ENCRYPTION_KMS_TYPE, localType);
    rejectServerOverride(
        mergedProperties, CatalogProperties.ENCRYPTION_KMS_IMPL, localImplementation);

    Map<String, String> validated = new HashMap<>(mergedProperties);
    if (localType != null) {
      validated.put(CatalogProperties.ENCRYPTION_KMS_TYPE, localType);
      validated.remove(CatalogProperties.ENCRYPTION_KMS_IMPL);
    } else {
      validated.put(CatalogProperties.ENCRYPTION_KMS_IMPL, localImplementation);
      validated.remove(CatalogProperties.ENCRYPTION_KMS_TYPE);
    }

    if (KeystoreKeyManagementClient.class.getName().equals(localImplementation)) {
      copyRequiredLocalProperty(
          localProperties, validated, mergedProperties, KeystoreKeyManagementClient.PATH_PROPERTY);
      copyRequiredLocalProperty(
          localProperties,
          validated,
          mergedProperties,
          KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY);
      copyOptionalLocalProperty(
          localProperties, validated, mergedProperties, KeystoreKeyManagementClient.TYPE_PROPERTY);
    }

    return validated;
  }

  static FileIO newOwnedFileIO(Map<String, String> properties, @Nullable Object hadoopConf) {
    String fileIOImplementation =
        properties.getOrDefault(CatalogProperties.FILE_IO_IMPL, ResolvingFileIO.class.getName());
    return CatalogUtil.loadFileIO(fileIOImplementation, properties, hadoopConf);
  }

  private static void copyRequiredLocalProperty(
      Map<String, String> localProperties,
      Map<String, String> validated,
      Map<String, String> mergedProperties,
      String property) {
    String localValue = requireLocalProperty(localProperties, property);
    rejectServerOverride(mergedProperties, property, localValue);
    validated.put(property, localValue);
  }

  private static void copyOptionalLocalProperty(
      Map<String, String> localProperties,
      Map<String, String> validated,
      Map<String, String> mergedProperties,
      String property) {
    String localValue = localProperties.get(property);
    if (localValue == null || localValue.trim().isEmpty()) {
      return;
    }
    rejectServerOverride(mergedProperties, property, localValue);
    validated.put(property, localValue);
  }

  private static String requireLocalProperty(Map<String, String> properties, String property) {
    String value = properties.get(property);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "KMS property must be configured by the client: " + property);
    }
    return value;
  }

  private static void rejectBlankSelector(String property, @Nullable String value) {
    if (value != null && value.trim().isEmpty()) {
      throw new IllegalArgumentException("KMS property cannot be blank: " + property);
    }
  }

  private static void rejectServerOverride(
      Map<String, String> mergedProperties, String property, @Nullable String localValue) {
    if (localValue == null) {
      return;
    }
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
