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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests the durable Iceberg deletion namespace encoding. */
public class TestIcebergDeletionNamespaceCodec {

  @Test
  public void testRoundTripSeparatorCharacters() {
    String[] levels = {"a:b", "c.d", "comma,value", ""};
    String encoded = IcebergDeletionNamespaceCodec.encode(levels);

    assertArrayEquals(levels, IcebergDeletionNamespaceCodec.decode(encoded));
    assertNotEquals(
        IcebergDeletionNamespaceCodec.encode(new String[] {"a", "b:c"}),
        IcebergDeletionNamespaceCodec.encode(new String[] {"a:b", "c"}));
  }

  @Test
  public void testMalformedSnapshotsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> IcebergDeletionNamespaceCodec.decode("2:1:a"));
    assertThrows(
        IllegalArgumentException.class, () -> IcebergDeletionNamespaceCodec.decode("1:x:a"));
    assertThrows(
        IllegalArgumentException.class, () -> IcebergDeletionNamespaceCodec.decode("1:1:ab"));
  }
}
