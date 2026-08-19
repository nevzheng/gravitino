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
package org.apache.iceberg.encryption;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestKeystoreKeyManagementClient {

  private static final String ALIAS = "customer-pii-v1";
  private static final String PASSWORD = "changeit";

  @TempDir Path tempDir;

  @Test
  void testWrapUnwrapRoundTrip() throws Exception {
    Path keystore = tempDir.resolve("demo.p12");
    Path passwordFile = tempDir.resolve("password");
    Files.write(passwordFile, PASSWORD.getBytes(StandardCharsets.UTF_8));
    writePkcs12(keystore, ALIAS, PASSWORD);

    KeystoreKeyManagementClient client = new KeystoreKeyManagementClient();
    Map<String, String> properties = new HashMap<>();
    properties.put(KeystoreKeyManagementClient.PATH_PROPERTY, keystore.toString());
    properties.put(KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY, passwordFile.toString());
    client.initialize(properties);

    byte[] plaintext = "dek-material-16b!".getBytes(StandardCharsets.UTF_8);
    ByteBuffer wrapped = client.wrapKey(ByteBuffer.wrap(plaintext), ALIAS);
    ByteBuffer unwrapped = client.unwrapKey(wrapped, ALIAS);

    byte[] recovered = new byte[unwrapped.remaining()];
    unwrapped.get(recovered);
    Assertions.assertArrayEquals(plaintext, recovered);
  }

  @Test
  void testMissingAliasFails() throws Exception {
    Path keystore = tempDir.resolve("demo.p12");
    Path passwordFile = tempDir.resolve("password");
    Files.write(passwordFile, PASSWORD.getBytes(StandardCharsets.UTF_8));
    writePkcs12(keystore, ALIAS, PASSWORD);

    KeystoreKeyManagementClient client = new KeystoreKeyManagementClient();
    Map<String, String> properties = new HashMap<>();
    properties.put(KeystoreKeyManagementClient.PATH_PROPERTY, keystore.toString());
    properties.put(KeystoreKeyManagementClient.PASSWORD_FILE_PROPERTY, passwordFile.toString());
    client.initialize(properties);

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> client.wrapKey(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), "missing-alias"));
  }

  private static void writePkcs12(Path keystore, String alias, String password) throws Exception {
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(256);
    SecretKey secretKey = keyGen.generateKey();

    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setEntry(
        alias,
        new KeyStore.SecretKeyEntry(secretKey),
        new KeyStore.PasswordProtection(password.toCharArray()));
    try (OutputStream out = Files.newOutputStream(keystore)) {
      store.store(out, password.toCharArray());
    }
  }
}
