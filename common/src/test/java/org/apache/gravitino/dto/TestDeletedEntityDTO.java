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
package org.apache.gravitino.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Instant;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

public class TestDeletedEntityDTO {

  @Test
  void testSerDe() throws JsonProcessingException {
    DeletedEntityDTO deletedEntity = newDeletedEntity().build();

    String json = JsonUtils.objectMapper().writeValueAsString(deletedEntity);
    String expected =
        "{\"deleted\":true,\"id\":\"984273\",\"deletionId\":\"77192\",\"name\":\"orders\","
            + "\"type\":\"table\",\"deletedAt\":1784800000000,"
            + "\"expiresAt\":1785404800000,\"deletedBy\":\"alice\",\"version\":17,"
            + "\"etag\":\"deletion-77192-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"latestForName\":true,"
            + "\"restorable\":true}";
    assertEquals(
        JsonUtils.objectMapper().readTree(expected), JsonUtils.objectMapper().readTree(json));

    DeletedEntityDTO deserialized =
        JsonUtils.objectMapper().readValue(json, DeletedEntityDTO.class);
    assertEquals(deletedEntity, deserialized);
    assertDoesNotThrow(deserialized::validate);

    DeletedEntity publicEntity = deserialized;
    assertTrue(publicEntity.deleted());
    assertEquals("984273", publicEntity.id());
    assertEquals("77192", publicEntity.deletionId());
    assertEquals("orders", publicEntity.name());
    assertEquals(RecoveryEntityType.TABLE, publicEntity.type());
    assertEquals(Instant.ofEpochMilli(1784800000000L), publicEntity.deletedAt());
    assertEquals(Instant.ofEpochMilli(1785404800000L), publicEntity.expiresAt());
    assertEquals("alice", publicEntity.deletedBy());
    assertEquals(17L, publicEntity.version());
    assertEquals(deletedEntity.getEtag(), publicEntity.etag());
    assertTrue(publicEntity.latestForName());
    assertTrue(publicEntity.restorable());
  }

  @Test
  void testNonRestorableReason() {
    DeletedEntityDTO deletedEntity =
        newDeletedEntity().withRestorable(false).withReason("LEGACY_TOMBSTONE").build();

    assertDoesNotThrow(deletedEntity::validate);
  }

  @Test
  void testUnversionedEntityOmitsVersion() throws JsonProcessingException {
    DeletedEntityDTO deletedEntity = newDeletedEntity().withVersion(null).build();

    assertDoesNotThrow(deletedEntity::validate);
    String json = JsonUtils.objectMapper().writeValueAsString(deletedEntity);
    assertFalse(JsonUtils.objectMapper().readTree(json).has("version"));
  }

  @Test
  void testValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> newDeletedEntity().withDeleted(false).build().validate());
    assertThrows(
        IllegalArgumentException.class, () -> newDeletedEntity().withId(" ").build().validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> newDeletedEntity().withExpiresAt(1784800000000L).build().validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> newDeletedEntity().withRestorable(false).build().validate());
  }

  private static DeletedEntityDTO.DeletedEntityDTOBuilder newDeletedEntity() {
    return DeletedEntityDTO.builder()
        .withId("984273")
        .withDeletionId("77192")
        .withName("orders")
        .withType(RecoveryEntityType.TABLE)
        .withDeletedAt(1784800000000L)
        .withExpiresAt(1785404800000L)
        .withDeletedBy("alice")
        .withVersion(17L)
        .withEtag(
            "deletion-77192-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withLatestForName(true)
        .withRestorable(true);
  }
}
