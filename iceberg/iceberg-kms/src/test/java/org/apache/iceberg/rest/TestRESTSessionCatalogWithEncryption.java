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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.encryption.KeystoreKeyManagementClient;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestRESTSessionCatalogWithEncryption {

  @TempDir Path tempDir;

  @Test
  void testAcceptsRestServedImplementationWithClientLocalKeystoreSettings() throws Exception {
    Map<String, String> localProperties = localKeystoreProperties();
    Map<String, String> mergedProperties = new HashMap<>(localProperties);
    mergedProperties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, KeystoreKeyManagementClient.class.getName());

    Map<String, String> validated =
        RESTSessionCatalogWithEncryption.validatedKmsProperties(localProperties, mergedProperties);

    Assertions.assertEquals(
        localProperties.get(KeystoreKeyManagementClient.PATH_PROPERTY),
        validated.get(KeystoreKeyManagementClient.PATH_PROPERTY));
    Assertions.assertEquals(
        localProperties.get(KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY),
        validated.get(KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY));
  }

  @Test
  void testRejectsRestOverrideOfClientLocalKeystorePath() throws Exception {
    Map<String, String> localProperties = localKeystoreProperties();
    Map<String, String> mergedProperties = new HashMap<>(localProperties);
    mergedProperties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, KeystoreKeyManagementClient.class.getName());
    mergedProperties.put(KeystoreKeyManagementClient.PATH_PROPERTY, "/tmp/attacker.p12");

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                RESTSessionCatalogWithEncryption.validatedKmsProperties(
                    localProperties, mergedProperties));
    Assertions.assertTrue(exception.getMessage().contains("cannot override"));
  }

  @Test
  void testRejectsMissingClientLocalKmsSelector() {
    Map<String, String> localProperties = Map.of();
    Map<String, String> mergedProperties =
        Map.of(CatalogProperties.ENCRYPTION_KMS_IMPL, KeystoreKeyManagementClient.class.getName());

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                RESTSessionCatalogWithEncryption.validatedKmsProperties(
                    localProperties, mergedProperties));
    Assertions.assertTrue(exception.getMessage().contains("Client must set"));
  }

  @Test
  void testCreatesDistinctOwnedFileIoInstances() {
    Map<String, String> properties =
        Map.of(CatalogProperties.FILE_IO_IMPL, CountingFileIO.class.getName());

    CountingFileIO first =
        (CountingFileIO) RESTSessionCatalogWithEncryption.newOwnedFileIO(properties, null);
    CountingFileIO second =
        (CountingFileIO) RESTSessionCatalogWithEncryption.newOwnedFileIO(properties, null);

    Assertions.assertNotSame(first, second);
    first.close();
    Assertions.assertTrue(first.closed);
    Assertions.assertFalse(second.closed);
    second.close();
    Assertions.assertTrue(second.closed);
  }

  private Map<String, String> localKeystoreProperties() throws Exception {
    Path keystore = tempDir.resolve("demo.p12");
    Path passwordFile = tempDir.resolve("password");
    Files.write(passwordFile, "changeit".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.write(keystore, new byte[] {1, 2, 3});

    Map<String, String> properties = new HashMap<>();
    properties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, KeystoreKeyManagementClient.class.getName());
    properties.put(KeystoreKeyManagementClient.PATH_PROPERTY, keystore.toString());
    properties.put(KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY, passwordFile.toString());
    return properties;
  }

  /** Minimal file IO used to verify that each REST table operation receives an owned instance. */
  public static class CountingFileIO implements FileIO {
    private boolean closed;

    @Override
    public InputFile newInputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void initialize(Map<String, String> properties) {}

    @Override
    public void close() {
      closed = true;
    }
  }
}
