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
package org.apache.gravitino.iceberg.integration.test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.apache.iceberg.util.ByteBuffers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link KeystoreKeyManagementClient}. */
public class TestKeystoreKeyManagementClient {

  private static final String KEY_ALIAS = "audit-master-key";
  private static final String KEYSTORE_PASSWORD = "audit-password";

  @TempDir Path tempDir;

  @BeforeEach
  void resetInitializationCount() {
    KeystoreKeyManagementClient.resetInitializationCount();
  }

  @Test
  void testWrapAndUnwrapKey() throws Exception {
    Path keyStorePath = createKeyStore(tempDir.resolve("audit.jceks"));
    KeystoreKeyManagementClient client = new KeystoreKeyManagementClient();
    client.initialize(
        Map.of(
            KeystoreKeyManagementClient.KEYSTORE_PATH_PROPERTY,
            keyStorePath.toString(),
            KeystoreKeyManagementClient.KEYSTORE_PASSWORD_PROPERTY,
            KEYSTORE_PASSWORD));

    byte[] rawKey = "recognizable-data-key-material".getBytes(StandardCharsets.UTF_8);
    ByteBuffer wrapped = client.wrapKey(ByteBuffer.wrap(rawKey), KEY_ALIAS);
    ByteBuffer unwrapped = client.unwrapKey(wrapped, KEY_ALIAS);

    Assertions.assertFalse(Arrays.equals(rawKey, ByteBuffers.toByteArray(wrapped)));
    Assertions.assertArrayEquals(rawKey, ByteBuffers.toByteArray(unwrapped));
    Assertions.assertEquals(1, KeystoreKeyManagementClient.initializationCount());
  }

  @Test
  void testRejectsInvalidWrappedKeyVersion() throws Exception {
    Path keyStorePath = createKeyStore(tempDir.resolve("audit.jceks"));
    KeystoreKeyManagementClient client = new KeystoreKeyManagementClient();
    client.initialize(
        Map.of(
            KeystoreKeyManagementClient.KEYSTORE_PATH_PROPERTY,
            keyStorePath.toString(),
            KeystoreKeyManagementClient.KEYSTORE_PASSWORD_PROPERTY,
            KEYSTORE_PASSWORD));

    ByteBuffer wrapped = client.wrapKey(ByteBuffer.wrap(new byte[16]), KEY_ALIAS);
    wrapped.put(0, (byte) 2);

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> client.unwrapKey(wrapped, KEY_ALIAS));
  }

  @Test
  void testRequiresKeystoreConfiguration() {
    KeystoreKeyManagementClient client = new KeystoreKeyManagementClient();
    Assertions.assertThrows(IllegalArgumentException.class, () -> client.initialize(Map.of()));
  }

  private Path createKeyStore(Path path) throws GeneralSecurityException, IOException {
    byte[] masterKey = new byte[32];
    Arrays.fill(masterKey, (byte) 7);

    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    keyStore.load(null, KEYSTORE_PASSWORD.toCharArray());
    keyStore.setEntry(
        KEY_ALIAS,
        new KeyStore.SecretKeyEntry(new SecretKeySpec(masterKey, "AES")),
        new KeyStore.PasswordProtection(KEYSTORE_PASSWORD.toCharArray()));
    try (OutputStream output = Files.newOutputStream(path)) {
      keyStore.store(output, KEYSTORE_PASSWORD.toCharArray());
    }
    return path;
  }
}
