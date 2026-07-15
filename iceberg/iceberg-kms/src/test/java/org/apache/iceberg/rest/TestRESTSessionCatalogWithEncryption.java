/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.rest;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.iceberg.kms.OpenBaoKeyManagementClient;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestRESTSessionCatalogWithEncryption {

  private static final String TRUSTED_ENDPOINT = "http://openbao:8200";
  private static final String TRUSTED_TOKEN_FILE = "/run/secrets/kms/token";

  @Test
  void testAcceptsRestServedImplementationWithClientLocalTrustSettings() {
    Map<String, String> localProperties = localProperties();
    Map<String, String> mergedProperties = new HashMap<>(localProperties);
    mergedProperties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, OpenBaoKeyManagementClient.class.getName());

    Map<String, String> validated =
        RESTSessionCatalogWithEncryption.validatedKmsProperties(localProperties, mergedProperties);

    Assertions.assertEquals(
        TRUSTED_ENDPOINT, validated.get(OpenBaoKeyManagementClient.ENDPOINT_PROPERTY));
    Assertions.assertEquals(
        TRUSTED_TOKEN_FILE, validated.get(OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY));
  }

  @Test
  void testRejectsRestOverrideOfClientLocalEndpoint() {
    Map<String, String> localProperties = localProperties();
    Map<String, String> mergedProperties = new HashMap<>(localProperties);
    mergedProperties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, OpenBaoKeyManagementClient.class.getName());
    mergedProperties.put(
        OpenBaoKeyManagementClient.ENDPOINT_PROPERTY, "http://attacker.example:8200");

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                RESTSessionCatalogWithEncryption.validatedKmsProperties(
                    localProperties, mergedProperties));
    Assertions.assertTrue(exception.getMessage().contains("cannot override"));
  }

  @Test
  void testRejectsRestOverrideOfClientLocalTokenFile() {
    Map<String, String> localProperties = localProperties();
    Map<String, String> mergedProperties = new HashMap<>(localProperties);
    mergedProperties.put(
        CatalogProperties.ENCRYPTION_KMS_IMPL, OpenBaoKeyManagementClient.class.getName());
    mergedProperties.put(OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY, "/etc/passwd");

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                RESTSessionCatalogWithEncryption.validatedKmsProperties(
                    localProperties, mergedProperties));
    Assertions.assertTrue(exception.getMessage().contains("cannot override"));
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

  private static Map<String, String> localProperties() {
    Map<String, String> properties = new HashMap<>();
    properties.put(OpenBaoKeyManagementClient.ENDPOINT_PROPERTY, TRUSTED_ENDPOINT);
    properties.put(OpenBaoKeyManagementClient.TOKEN_FILE_PROPERTY, TRUSTED_TOKEN_FILE);
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
