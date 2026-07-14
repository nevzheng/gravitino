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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.apache.iceberg.encryption.KeyManagementClient;

/** Keystore-backed KMS client used by the Iceberg encryption compatibility audit. */
public class KeystoreKeyManagementClient implements KeyManagementClient {

  static final String KEYSTORE_PATH_PROPERTY = "audit.kms.keystore.path";
  static final String KEYSTORE_PASSWORD_PROPERTY = "audit.kms.keystore.password";

  private static final String KEYSTORE_TYPE = "JCEKS";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final byte WRAPPED_KEY_VERSION = 1;
  private static final int IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final AtomicInteger INITIALIZATION_COUNT = new AtomicInteger();

  private byte[] keyStoreBytes;
  private char[] keyStorePassword;

  /** {@inheritDoc} */
  @Override
  public ByteBuffer wrapKey(ByteBuffer key, String wrappingKeyId) {
    try {
      byte[] iv = new byte[IV_LENGTH_BYTES];
      RANDOM.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          loadSecretKey(wrappingKeyId),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      cipher.updateAAD(wrappingKeyId.getBytes(StandardCharsets.UTF_8));

      ByteBuffer keyCopy = key.duplicate();
      byte[] keyBytes = new byte[keyCopy.remaining()];
      keyCopy.get(keyBytes);
      byte[] encryptedKey = cipher.doFinal(keyBytes);

      return ByteBuffer.allocate(1 + IV_LENGTH_BYTES + encryptedKey.length)
          .put(WRAPPED_KEY_VERSION)
          .put(iv)
          .put(encryptedKey)
          .flip();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Unable to wrap key with alias " + wrappingKeyId, e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public ByteBuffer unwrapKey(ByteBuffer wrappedKey, String wrappingKeyId) {
    try {
      ByteBuffer wrappedKeyCopy = wrappedKey.duplicate();
      if (wrappedKeyCopy.remaining() <= 1 + IV_LENGTH_BYTES) {
        throw new IllegalArgumentException("Wrapped key is too short");
      }

      byte version = wrappedKeyCopy.get();
      if (version != WRAPPED_KEY_VERSION) {
        throw new IllegalArgumentException("Unsupported wrapped key version: " + version);
      }

      byte[] iv = new byte[IV_LENGTH_BYTES];
      wrappedKeyCopy.get(iv);
      byte[] encryptedKey = new byte[wrappedKeyCopy.remaining()];
      wrappedKeyCopy.get(encryptedKey);

      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          loadSecretKey(wrappingKeyId),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      cipher.updateAAD(wrappingKeyId.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(cipher.doFinal(encryptedKey));
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Unable to unwrap key with alias " + wrappingKeyId, e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void initialize(Map<String, String> properties) {
    String keyStorePath = requiredProperty(properties, KEYSTORE_PATH_PROPERTY);
    String password = requiredProperty(properties, KEYSTORE_PASSWORD_PROPERTY);
    try {
      this.keyStoreBytes = Files.readAllBytes(Path.of(keyStorePath));
      this.keyStorePassword = password.toCharArray();
      loadKeyStore();
      INITIALIZATION_COUNT.incrementAndGet();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Unable to initialize audit keystore", e);
    }
  }

  static int initializationCount() {
    return INITIALIZATION_COUNT.get();
  }

  static void resetInitializationCount() {
    INITIALIZATION_COUNT.set(0);
  }

  private SecretKey loadSecretKey(String alias) throws GeneralSecurityException, IOException {
    KeyStore.Entry entry =
        loadKeyStore().getEntry(alias, new KeyStore.PasswordProtection(keyStorePassword));
    if (!(entry instanceof KeyStore.SecretKeyEntry)) {
      throw new IllegalArgumentException("No secret key found for alias " + alias);
    }

    return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
  }

  private KeyStore loadKeyStore() throws GeneralSecurityException, IOException {
    if (keyStoreBytes == null || keyStorePassword == null) {
      throw new IllegalStateException("KMS client is not initialized");
    }

    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (ByteArrayInputStream input = new ByteArrayInputStream(keyStoreBytes)) {
      keyStore.load(input, keyStorePassword);
    }
    return keyStore;
  }

  private static String requiredProperty(Map<String, String> properties, String key) {
    String value = properties.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + key);
    }
    return value;
  }
}
