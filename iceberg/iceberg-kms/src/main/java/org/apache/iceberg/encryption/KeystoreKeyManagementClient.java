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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Iceberg {@link KeyManagementClient} backed by a local PKCS12 (or JCEKS) keystore.
 *
 * <p>This is the PRD verification vehicle: the engine wraps and unwraps DEKs by talking to the
 * keystore directly. Gravitino does not proxy these calls.
 */
public final class KeystoreKeyManagementClient implements KeyManagementClient {

  /** Absolute path to the keystore file. */
  public static final String PATH_PROPERTY = "encryption.kms.keystore.path";

  /** Absolute path to a file containing the keystore password (UTF-8, trimmed). */
  public static final String PASSWORD_FILE_PROPERTY = "encryption.kms.keystore.password-file";

  /** Keystore type; defaults to {@code PKCS12}. */
  public static final String TYPE_PROPERTY = "encryption.kms.keystore.type";

  private static final String DEFAULT_TYPE = "PKCS12";
  private static final String WRAP_TRANSFORM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_IV_BYTES = 12;
  private static final long serialVersionUID = 1L;

  @Nullable private String keystorePath;
  @Nullable private String passwordFile;
  private String keystoreType = DEFAULT_TYPE;

  @Nullable private transient volatile KeyStore keyStore;
  @Nullable private transient volatile char[] password;

  /** Creates an uninitialized keystore key-management client. */
  public KeystoreKeyManagementClient() {}

  /** {@inheritDoc} */
  @Override
  public void initialize(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties");
    this.keystorePath = required(properties, PATH_PROPERTY);
    this.passwordFile = required(properties, PASSWORD_FILE_PROPERTY);
    String configuredType = properties.get(TYPE_PROPERTY);
    this.keystoreType =
        configuredType == null || configuredType.trim().isEmpty()
            ? DEFAULT_TYPE
            : configuredType.trim();
    this.keyStore = null;
    this.password = null;
  }

  /** {@inheritDoc} */
  @Override
  public ByteBuffer wrapKey(ByteBuffer key, String wrappingKeyId) {
    Objects.requireNonNull(key, "Key to wrap must not be null");
    validateKeyId(wrappingKeyId);
    try {
      SecretKey wrappingKey = secretKey(wrappingKeyId);
      Cipher cipher = Cipher.getInstance(WRAP_TRANSFORM);
      cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
      byte[] iv = cipher.getIV();
      byte[] ciphertext = cipher.doFinal(remainingBytes(key));
      byte[] wrapped = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, wrapped, 0, iv.length);
      System.arraycopy(ciphertext, 0, wrapped, iv.length, ciphertext.length);
      return ByteBuffer.wrap(wrapped);
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Keystore KMS failed to wrap key " + wrappingKeyId, e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public ByteBuffer unwrapKey(ByteBuffer wrappedKey, String wrappingKeyId) {
    Objects.requireNonNull(wrappedKey, "Wrapped key must not be null");
    validateKeyId(wrappingKeyId);
    byte[] payload = remainingBytes(wrappedKey);
    if (payload.length <= GCM_IV_BYTES) {
      throw new IllegalArgumentException("Wrapped key is too short");
    }
    try {
      SecretKey wrappingKey = secretKey(wrappingKeyId);
      byte[] iv = new byte[GCM_IV_BYTES];
      byte[] ciphertext = new byte[payload.length - GCM_IV_BYTES];
      System.arraycopy(payload, 0, iv, 0, GCM_IV_BYTES);
      System.arraycopy(payload, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
      Cipher cipher = Cipher.getInstance(WRAP_TRANSFORM);
      cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      return ByteBuffer.wrap(cipher.doFinal(ciphertext));
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Keystore KMS failed to unwrap key " + wrappingKeyId, e);
    }
  }

  private SecretKey secretKey(String alias) throws GeneralSecurityException {
    KeyStore store = loadedKeyStore();
    if (!store.containsAlias(alias)) {
      throw new IllegalArgumentException("Keystore does not contain alias: " + alias);
    }
    KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(password());
    KeyStore.Entry entry = store.getEntry(alias, protection);
    if (!(entry instanceof KeyStore.SecretKeyEntry)) {
      throw new IllegalArgumentException("Keystore alias is not a secret key: " + alias);
    }
    return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
  }

  private KeyStore loadedKeyStore() throws GeneralSecurityException {
    KeyStore cached = keyStore;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (keyStore == null) {
        Path path = Paths.get(keystorePath);
        try (InputStream in = Files.newInputStream(path)) {
          KeyStore store = KeyStore.getInstance(keystoreType);
          store.load(in, password());
          keyStore = store;
        } catch (IOException e) {
          throw new UncheckedIOException("Failed to read keystore: " + keystorePath, e);
        }
      }
      return keyStore;
    }
  }

  private char[] password() {
    char[] cached = password;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (password == null) {
        try {
          String raw =
              new String(Files.readAllBytes(Paths.get(passwordFile)), StandardCharsets.UTF_8)
                  .trim();
          if (raw.isEmpty()) {
            throw new IllegalArgumentException("Keystore password file is empty: " + passwordFile);
          }
          password = raw.toCharArray();
        } catch (IOException e) {
          throw new UncheckedIOException(
              "Failed to read keystore password file: " + passwordFile, e);
        }
      }
      return password;
    }
  }

  private static String required(Map<String, String> properties, String property) {
    String value = properties.get(property);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing required KMS property: " + property);
    }
    return value.trim();
  }

  private static void validateKeyId(String wrappingKeyId) {
    if (wrappingKeyId == null || wrappingKeyId.trim().isEmpty()) {
      throw new IllegalArgumentException("Wrapping key ID cannot be blank");
    }
    String normalized = wrappingKeyId.trim();
    if (!normalized.equals(wrappingKeyId)
        || normalized.toLowerCase(Locale.ROOT).contains("..")
        || normalized.indexOf('/') >= 0
        || normalized.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Invalid wrapping key ID: " + wrappingKeyId);
    }
  }

  private static byte[] remainingBytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.asReadOnlyBuffer();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }
}
