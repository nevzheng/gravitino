/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.deletion;

import java.util.Objects;

/** Length-prefixed codec for an immutable Iceberg namespace snapshot. */
public final class IcebergDeletionNamespaceCodec {

  private IcebergDeletionNamespaceCodec() {}

  /**
   * Encodes namespace levels without relying on a reserved separator character.
   *
   * @param levels Iceberg namespace levels
   * @return unambiguous length-prefixed representation
   */
  public static String encode(String[] levels) {
    Objects.requireNonNull(levels, "levels must not be null");
    StringBuilder encoded = new StringBuilder().append(levels.length).append(':');
    for (String level : levels) {
      Objects.requireNonNull(level, "namespace level must not be null");
      encoded.append(level.length()).append(':').append(level);
    }
    return encoded.toString();
  }

  /**
   * Decodes a length-prefixed namespace snapshot.
   *
   * @param encoded encoded namespace snapshot
   * @return exact namespace levels
   * @throws IllegalArgumentException when the snapshot is malformed
   */
  public static String[] decode(String encoded) {
    Objects.requireNonNull(encoded, "encoded namespace must not be null");
    Cursor cursor = new Cursor(encoded);
    int levelCount = cursor.readLength("level count");
    String[] levels = new String[levelCount];
    for (int index = 0; index < levelCount; index++) {
      int length = cursor.readLength("level length");
      levels[index] = cursor.readValue(length);
    }
    if (!cursor.atEnd()) {
      throw new IllegalArgumentException("Encoded namespace contains trailing data");
    }
    return levels;
  }

  private static final class Cursor {
    private final String encoded;
    private int offset;

    private Cursor(String encoded) {
      this.encoded = encoded;
    }

    private int readLength(String field) {
      int delimiter = encoded.indexOf(':', offset);
      if (delimiter < 0 || delimiter == offset) {
        throw new IllegalArgumentException("Encoded namespace has an invalid " + field);
      }
      try {
        int value = Integer.parseInt(encoded.substring(offset, delimiter));
        if (value < 0) {
          throw new IllegalArgumentException("Encoded namespace has a negative " + field);
        }
        offset = delimiter + 1;
        return value;
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Encoded namespace has an invalid " + field, e);
      }
    }

    private String readValue(int length) {
      if (length > encoded.length() - offset) {
        throw new IllegalArgumentException("Encoded namespace level exceeds available data");
      }
      String value = encoded.substring(offset, offset + length);
      offset += length;
      return value;
    }

    private boolean atEnd() {
      return offset == encoded.length();
    }
  }
}
