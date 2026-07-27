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

package org.apache.gravitino.iceberg.service.deletion;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

/** Verifies Iceberg deletion-context storage across supported relational backends. */
public class TestIcebergDeletionContextStore extends TestJDBCBackend {

  private IcebergDeletionContextStore store;
  private Object originalConfig;
  private Object originalIdGenerator;

  @BeforeAll
  public void snapshotGravitinoEnv() throws IllegalAccessException {
    originalConfig = FieldUtils.readField(GravitinoEnv.getInstance(), "config", true);
    originalIdGenerator = FieldUtils.readField(GravitinoEnv.getInstance(), "idGenerator", true);
  }

  @AfterAll
  public void restoreGravitinoEnv() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", originalConfig, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "idGenerator", originalIdGenerator, true);
  }

  @BeforeEach
  public void prepareStore() {
    store = new IcebergDeletionContextStore();
  }

  @TestTemplate
  void testRoundTripByDeletionGeneration() {
    IcebergDeletionContextPO expected = context("D1");
    store.insert(expected);

    IcebergDeletionContextPO actual = store.get("D1");
    Assertions.assertNotNull(actual);
    Assertions.assertEquals(expected.getDeletionId(), actual.getDeletionId());
    Assertions.assertEquals(expected.getIcebergNamespace(), actual.getIcebergNamespace());
    Assertions.assertEquals(expected.getIcebergTableName(), actual.getIcebergTableName());
    Assertions.assertEquals(expected.getIcebergTableUuid(), actual.getIcebergTableUuid());
    Assertions.assertEquals(expected.getMetadataLocation(), actual.getMetadataLocation());
    Assertions.assertEquals(expected.getFileIoImpl(), actual.getFileIoImpl());
    Assertions.assertEquals(expected.getProtectedFileIoRef(), actual.getProtectedFileIoRef());
    Assertions.assertEquals(expected.getContextDigest(), actual.getContextDigest());
    Assertions.assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    Assertions.assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
    Assertions.assertNull(store.get("D2"));
  }

  @TestTemplate
  void testInsertJoinsOuterTransaction() {
    Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            SessionUtils.doMultipleWithCommit(
                () -> store.insert(context("rollback")),
                () -> {
                  throw new IllegalStateException("force rollback");
                }));

    Assertions.assertNull(store.get("rollback"));
  }

  private static IcebergDeletionContextPO context(String deletionId) {
    return IcebergDeletionContextPO.builder()
        .withDeletionId(deletionId)
        .withIcebergNamespace("db")
        .withIcebergTableName("table")
        .withIcebergTableUuid("11111111-2222-3333-4444-555555555555")
        .withMetadataLocation("s3://bucket/db/table/metadata/00001.json")
        .withFileIoImpl("org.apache.iceberg.aws.s3.S3FileIO")
        .withProtectedFileIoRef("credential-provider://catalog/100")
        .withContextDigest("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        .withCreatedAt(1000L)
        .withUpdatedAt(1000L)
        .build();
  }
}
