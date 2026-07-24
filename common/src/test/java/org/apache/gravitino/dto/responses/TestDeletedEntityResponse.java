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
package org.apache.gravitino.dto.responses;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

public class TestDeletedEntityResponse {

  @Test
  void testSerDe() throws JsonProcessingException {
    DeletedEntityDTO deletedEntity =
        DeletedEntityDTO.builder()
            .withId("984273")
            .withDeletionId("77192")
            .withName("orders")
            .withType(RecoveryEntityType.TABLE)
            .withDeletedAt(1_784_800_000_000L)
            .withExpiresAt(1_785_404_800_000L)
            .withVersion(17L)
            .withEtag(
                "deletion-77192-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .withLatestForName(true)
            .withRestorable(true)
            .build();
    DeletedEntityResponse response = new DeletedEntityResponse(deletedEntity);

    String json = JsonUtils.objectMapper().writeValueAsString(response);
    DeletedEntityResponse roundTrip =
        JsonUtils.objectMapper().readValue(json, DeletedEntityResponse.class);

    assertEquals(response, roundTrip);
    assertDoesNotThrow(roundTrip::validate);
  }
}
