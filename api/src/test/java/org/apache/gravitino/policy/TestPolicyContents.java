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

package org.apache.gravitino.policy;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.MetadataObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPolicyContents {

  @Test
  void testIcebergCompactionContentUsesDefaults() {
    IcebergDataCompactionContent content =
        (IcebergDataCompactionContent) PolicyContents.icebergDataCompaction();

    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DATA_FILE_MSE,
        content.rules().get("minDataFileMse"));
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DELETE_FILE_NUMBER,
        content.rules().get("minDeleteFileNumber"));
    Assertions.assertEquals(1L, content.rules().get("dataFileMseWeight"));
    Assertions.assertEquals(100L, content.rules().get("deleteFileNumberWeight"));
    Assertions.assertEquals(50L, content.rules().get("max-partition-num"));
    Assertions.assertNull(content.rules().get("job.options.target-file-size-bytes"));
    Assertions.assertNull(content.rules().get("job.options.min-input-files"));
    Assertions.assertNull(content.rules().get("job.options.delete-file-threshold"));
  }

  @Test
  void testIcebergCompactionContentGeneratesOptimizerFields() {
    IcebergDataCompactionContent content =
        (IcebergDataCompactionContent)
            PolicyContents.icebergDataCompaction(
                1000L, 1L, mapOf("target-file-size-bytes", "1048576", "min-input-files", "1"));

    Assertions.assertEquals("iceberg-data-compaction", content.properties().get("strategy.type"));
    Assertions.assertEquals(
        "builtin-iceberg-rewrite-data-files", content.properties().get("job.template-name"));
    Assertions.assertEquals(1000L, content.rules().get("minDataFileMse"));
    Assertions.assertEquals(1L, content.rules().get("minDeleteFileNumber"));
    Assertions.assertEquals(1L, content.rules().get("dataFileMseWeight"));
    Assertions.assertEquals(100L, content.rules().get("deleteFileNumberWeight"));
    Assertions.assertEquals(50L, content.rules().get("max-partition-num"));
    Assertions.assertEquals(
        "custom-data-file-mse >= minDataFileMse || custom-delete-file-number >= minDeleteFileNumber",
        content.rules().get("trigger-expr"));
    Assertions.assertEquals(
        "custom-data-file-mse * dataFileMseWeight"
            + " + custom-delete-file-number * deleteFileNumberWeight",
        content.rules().get("score-expr"));
    Assertions.assertEquals("1048576", content.rules().get("job.options.target-file-size-bytes"));
    Assertions.assertEquals("1", content.rules().get("job.options.min-input-files"));
    Assertions.assertEquals(
        ImmutableSet.of(
            MetadataObject.Type.CATALOG, MetadataObject.Type.SCHEMA, MetadataObject.Type.TABLE),
        content.supportedObjectTypes());
  }

  @Test
  void testIcebergCompactionContentSupportsCustomWeights() {
    IcebergDataCompactionContent content =
        (IcebergDataCompactionContent)
            PolicyContents.icebergDataCompaction(
                1000L,
                1L,
                3L,
                200L,
                88L,
                mapOf("target-file-size-bytes", "1048576", "min-input-files", "1"));

    Assertions.assertEquals(3L, content.rules().get("dataFileMseWeight"));
    Assertions.assertEquals(200L, content.rules().get("deleteFileNumberWeight"));
    Assertions.assertEquals(88L, content.rules().get("max-partition-num"));
    Assertions.assertDoesNotThrow(content::validate);
  }

  @Test
  void testIcebergCompactionContentRejectsInvalidRewriteOptionKey() {
    IcebergDataCompactionContent content =
        (IcebergDataCompactionContent)
            PolicyContents.icebergDataCompaction(
                1000L, 1L, mapOf("job.options.target-file-size-bytes", "1048576"));

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, content::validate);
    Assertions.assertTrue(exception.getMessage().contains("must not start with"));
  }

  @Test
  void testIcebergCompactionContentRejectsInvalidMaxPartitionNum() {
    IcebergDataCompactionContent content =
        (IcebergDataCompactionContent)
            PolicyContents.icebergDataCompaction(
                1000L, 1L, 2L, 10L, 0L, mapOf("target-file-size-bytes", "1048576"));

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, content::validate);
    Assertions.assertTrue(exception.getMessage().contains("maxPartitionNum"));
  }

  @Test
  void testIcebergEncryptionContentUsesDefaultsAndPreservesExactValues() {
    List<String> allowedKeyIds = new ArrayList<>(Arrays.asList("key-A", "key-a"));
    IcebergEncryptionContent content =
        (IcebergEncryptionContent) PolicyContents.icebergEncryption(1, "PII", allowedKeyIds);

    allowedKeyIds.add("mutated-after-construction");

    Assertions.assertEquals(1, content.schemaVersion());
    Assertions.assertEquals("PII", content.tag());
    Assertions.assertTrue(content.required());
    Assertions.assertEquals(Arrays.asList("key-A", "key-a"), content.allowedKeyIds());
    Assertions.assertEquals(IcebergEncryptionContent.Enforcement.REPORT, content.enforcement());
    Assertions.assertEquals("report", content.rules().get("enforcement"));
    Assertions.assertEquals(
        ImmutableSet.of(MetadataObject.Type.TABLE), content.supportedObjectTypes());
    Assertions.assertDoesNotThrow(content::validate);
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> content.allowedKeyIds().add("another-key"));
  }

  @Test
  void testIcebergEncryptionContentSupportsDenyAndOptionalEncryption() {
    IcebergEncryptionContent content =
        (IcebergEncryptionContent)
            PolicyContents.icebergEncryption(
                1,
                "PII",
                false,
                Collections.emptyList(),
                IcebergEncryptionContent.Enforcement.DENY_CREATE);

    Assertions.assertFalse(content.required());
    Assertions.assertTrue(content.allowedKeyIds().isEmpty());
    Assertions.assertEquals(
        IcebergEncryptionContent.Enforcement.DENY_CREATE, content.enforcement());
    Assertions.assertEquals("deny-create", content.rules().get("enforcement"));
    Assertions.assertDoesNotThrow(content::validate);
  }

  @Test
  void testIcebergEncryptionContentRejectsInvalidContent() {
    assertInvalidIcebergEncryptionContent(
        PolicyContents.icebergEncryption(2, "PII", Collections.singletonList("key-a")),
        "schemaVersion");
    assertInvalidIcebergEncryptionContent(
        PolicyContents.icebergEncryption(1, " ", Collections.singletonList("key-a")), "tag");
    assertInvalidIcebergEncryptionContent(
        PolicyContents.icebergEncryption(1, "PII", Collections.emptyList()), "allowedKeyIds");
    assertInvalidIcebergEncryptionContent(
        PolicyContents.icebergEncryption(1, "PII", Arrays.asList("key-a", " ")), "blanks");
    assertInvalidIcebergEncryptionContent(
        PolicyContents.icebergEncryption(1, "PII", Arrays.asList("key-a", "key-a")), "duplicate");
  }

  @Test
  void testIcebergEncryptionEnforcementWireValuesAreCaseSensitive() {
    Assertions.assertEquals(
        IcebergEncryptionContent.Enforcement.REPORT,
        IcebergEncryptionContent.Enforcement.fromValue("report"));
    Assertions.assertEquals(
        IcebergEncryptionContent.Enforcement.DENY_CREATE,
        IcebergEncryptionContent.Enforcement.fromValue("deny-create"));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IcebergEncryptionContent.Enforcement.fromValue("REPORT"));
  }

  private static void assertInvalidIcebergEncryptionContent(
      PolicyContent content, String expectedMessage) {
    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, content::validate);
    Assertions.assertTrue(exception.getMessage().contains(expectedMessage));
  }

  private static Map<String, String> mapOf(String key, String value) {
    Map<String, String> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  private static Map<String, String> mapOf(String key1, String value1, String key2, String value2) {
    Map<String, String> map = new HashMap<>();
    map.put(key1, value1);
    map.put(key2, value2);
    return map;
  }
}
