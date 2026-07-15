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
package org.apache.gravitino.dto.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Optional;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.policy.IcebergDataCompactionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPolicyDTO {

  @Test
  public void testPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.CustomContentDTO customContent =
        PolicyContentDTO.CustomContentDTO.builder()
            .withCustomRules(ImmutableMap.of("key1", "value1"))
            .withSupportedObjectTypes(
                ImmutableSet.of(MetadataObject.Type.CATALOG, MetadataObject.Type.TABLE))
            .withProperties(ImmutableMap.of("prop1", "value1"))
            .build();

    PolicyDTO policyDTO =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withEnabled(true)
            .withContent(customContent)
            .withAudit(audit)
            .build();

    String serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO);
    PolicyDTO deserPolicyDTO = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(policyDTO, deserPolicyDTO);

    Assertions.assertEquals("policy_test", deserPolicyDTO.name());
    Assertions.assertEquals("policy comment", deserPolicyDTO.comment());
    Assertions.assertEquals("my_compaction", deserPolicyDTO.policyType());
    Assertions.assertTrue(deserPolicyDTO.enabled());
    Assertions.assertEquals(customContent, deserPolicyDTO.content());
    Assertions.assertEquals(audit, deserPolicyDTO.auditInfo());

    // Test policy with inherited
    PolicyDTO policyDTO2 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.empty())
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO2);
    PolicyDTO deserPolicyDTO2 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(policyDTO2, deserPolicyDTO2);
    Assertions.assertEquals(Optional.empty(), deserPolicyDTO2.inherited());

    PolicyDTO policyDTO3 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.of(false))
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO3);
    PolicyDTO deserPolicyDTO3 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(Optional.of(false), deserPolicyDTO3.inherited());

    PolicyDTO policyDTO4 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.of(true))
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO4);
    PolicyDTO deserPolicyDTO4 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(Optional.of(true), deserPolicyDTO4.inherited());
  }

  @Test
  public void testIcebergCompactionPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.IcebergCompactionContentDTO typedContent =
        PolicyContentDTO.IcebergCompactionContentDTO.builder()
            .withMinDataFileMse(1000L)
            .withMinDeleteFileNumber(1L)
            .withDataFileMseWeight(2L)
            .withDeleteFileNumberWeight(150L)
            .withMaxPartitionNum(99L)
            .withRewriteOptions(
                ImmutableMap.of("target-file-size-bytes", "1048576", "min-input-files", "1"))
            .build();

    PolicyDTO policyDTO =
        PolicyDTO.builder()
            .withName("iceberg-compaction")
            .withComment("typed policy")
            .withPolicyType("system_iceberg_compaction")
            .withEnabled(true)
            .withContent(typedContent)
            .withAudit(audit)
            .build();

    String serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO);
    PolicyDTO deserPolicyDTO = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);

    Assertions.assertEquals(policyDTO, deserPolicyDTO);
    Assertions.assertInstanceOf(
        PolicyContentDTO.IcebergCompactionContentDTO.class, deserPolicyDTO.content());
  }

  @Test
  public void testIcebergCompactionPolicyDefaultValues() throws JsonProcessingException {
    String json =
        "{"
            + "\"name\":\"iceberg-compaction-default\","
            + "\"comment\":\"typed policy\","
            + "\"policyType\":\"system_iceberg_compaction\","
            + "\"enabled\":true,"
            + "\"content\":{}"
            + "}";

    PolicyDTO policyDTO = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);
    PolicyContentDTO.IcebergCompactionContentDTO contentDTO =
        (PolicyContentDTO.IcebergCompactionContentDTO) policyDTO.content();

    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DATA_FILE_MSE, contentDTO.minDataFileMse());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DELETE_FILE_NUMBER,
        contentDTO.minDeleteFileNumber());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_DATA_FILE_MSE_WEIGHT, contentDTO.dataFileMseWeight());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_DELETE_FILE_NUMBER_WEIGHT,
        contentDTO.deleteFileNumberWeight());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MAX_PARTITION_NUM, contentDTO.maxPartitionNum());
    Assertions.assertTrue(contentDTO.rewriteOptions().isEmpty());
    Assertions.assertDoesNotThrow(contentDTO::validate);
  }

  @Test
  public void testIcebergEncryptionPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.IcebergEncryptionContentDTO typedContent =
        PolicyContentDTO.IcebergEncryptionContentDTO.builder()
            .withSchemaVersion(1)
            .withTag("PII")
            .withRequired(true)
            .withAllowedKeyIds(ImmutableList.of("key-A", "key-a"))
            .withEnforcement(IcebergEncryptionContent.Enforcement.DENY_CREATE)
            .build();
    PolicyDTO policyDTO =
        PolicyDTO.builder()
            .withName("iceberg-encryption")
            .withComment("typed policy")
            .withPolicyType("system_iceberg_encryption")
            .withEnabled(true)
            .withContent(typedContent)
            .withAudit(audit)
            .build();

    String serialized = JsonUtils.objectMapper().writeValueAsString(policyDTO);
    PolicyDTO deserialized = JsonUtils.objectMapper().readValue(serialized, PolicyDTO.class);

    Assertions.assertTrue(serialized.contains("\"enforcement\":\"deny-create\""));
    Assertions.assertEquals(policyDTO, deserialized);
    Assertions.assertInstanceOf(
        PolicyContentDTO.IcebergEncryptionContentDTO.class, deserialized.content());
    PolicyContentDTO.IcebergEncryptionContentDTO deserializedContent =
        (PolicyContentDTO.IcebergEncryptionContentDTO) deserialized.content();
    Assertions.assertEquals("PII", deserializedContent.tag());
    Assertions.assertEquals(
        ImmutableList.of("key-A", "key-a"), deserializedContent.allowedKeyIds());
    Assertions.assertDoesNotThrow(deserializedContent::validate);
  }

  @Test
  public void testIcebergEncryptionPolicyDefaults() throws JsonProcessingException {
    String json =
        "{"
            + "\"name\":\"iceberg-encryption-default\","
            + "\"policyType\":\"system_iceberg_encryption\","
            + "\"enabled\":true,"
            + "\"content\":{"
            + "\"schemaVersion\":1,"
            + "\"tag\":\"PII\","
            + "\"allowedKeyIds\":[\"key-a\"]"
            + "}"
            + "}";

    PolicyDTO policyDTO = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);
    PolicyContentDTO.IcebergEncryptionContentDTO contentDTO =
        (PolicyContentDTO.IcebergEncryptionContentDTO) policyDTO.content();

    Assertions.assertTrue(contentDTO.required());
    Assertions.assertEquals(IcebergEncryptionContent.Enforcement.REPORT, contentDTO.enforcement());
    Assertions.assertDoesNotThrow(contentDTO::validate);
  }

  @Test
  public void testIcebergEncryptionPolicyRejectsNonCanonicalEnforcement() {
    String json =
        "{"
            + "\"name\":\"iceberg-encryption\","
            + "\"policyType\":\"system_iceberg_encryption\","
            + "\"enabled\":true,"
            + "\"content\":{"
            + "\"schemaVersion\":1,"
            + "\"tag\":\"PII\","
            + "\"allowedKeyIds\":[\"key-a\"],"
            + "\"enforcement\":\"REPORT\""
            + "}"
            + "}";

    Assertions.assertThrows(
        JsonProcessingException.class,
        () -> JsonUtils.objectMapper().readValue(json, PolicyDTO.class));
  }
}
