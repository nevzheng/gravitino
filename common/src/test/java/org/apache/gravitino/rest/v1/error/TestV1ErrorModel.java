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
package org.apache.gravitino.rest.v1.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class TestV1ErrorModel {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testTypedDetailsRoundTripThroughThePublicWireModel() throws Exception {
    V1ErrorResponse original =
        new V1ErrorResponse(
            new V1Error(
                400,
                "INVALID_ARGUMENT",
                "The request is invalid.",
                false,
                null,
                "request-123",
                Arrays.asList(
                    new V1FieldViolationErrorDetail("X-Request-Id", "Must be safe ASCII."),
                    new V1ResourceInfoErrorDetail(
                        "TABLE", "metalakes/demo/catalogs/c/schemas/s/tables/t"))));

    String json = objectMapper.writeValueAsString(original);
    V1ErrorResponse parsed = objectMapper.readValue(json, V1ErrorResponse.class);

    assertEquals("INVALID_ARGUMENT", parsed.getError().getType());
    assertEquals(2, parsed.getError().getDetails().size());
    V1FieldViolationErrorDetail fieldViolation =
        assertInstanceOf(V1FieldViolationErrorDetail.class, parsed.getError().getDetails().get(0));
    assertEquals("X-Request-Id", fieldViolation.getField());
    V1ResourceInfoErrorDetail resourceInfo =
        assertInstanceOf(V1ResourceInfoErrorDetail.class, parsed.getError().getDetails().get(1));
    assertEquals("TABLE", resourceInfo.getResourceType());
  }
}
