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
package org.apache.gravitino.server.web.rest;

import org.apache.gravitino.exceptions.PreconditionRequiredException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestRecoveryRequestUtils {

  @Test
  void testParsePositiveEntityId() {
    Assertions.assertNull(RecoveryRequestUtils.parsePositiveEntityId(null));
    Assertions.assertEquals(1L, RecoveryRequestUtils.parsePositiveEntityId("1"));
    Assertions.assertEquals(
        Long.MAX_VALUE, RecoveryRequestUtils.parsePositiveEntityId("9223372036854775807"));

    assertInvalidId("0", null);
    assertInvalidId("-1", null);
    assertInvalidId("not-a-number", NumberFormatException.class);
    assertInvalidId("9223372036854775808", NumberFormatException.class);
  }

  @Test
  void testParseStrongIfMatch() {
    Assertions.assertEquals(
        "generation-etag",
        RecoveryRequestUtils.parseStrongIfMatch("  \"generation-etag\"  ", "table"));

    PreconditionRequiredException missing =
        Assertions.assertThrows(
            PreconditionRequiredException.class,
            () -> RecoveryRequestUtils.parseStrongIfMatch(null, "job template"));
    Assertions.assertEquals(
        "If-Match is required when restoring a deleted job template", missing.getMessage());

    assertInvalidIfMatch("");
    assertInvalidIfMatch("generation-etag");
    assertInvalidIfMatch("W/\"generation-etag\"");
    assertInvalidIfMatch("\"one\", \"two\"");
    assertInvalidIfMatch("\"embedded\"quote\"");
  }

  private static void assertInvalidId(String id, Class<? extends Throwable> expectedCauseType) {
    IllegalArgumentException failure =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> RecoveryRequestUtils.parsePositiveEntityId(id));
    Assertions.assertEquals("id must be a positive decimal string", failure.getMessage());
    if (expectedCauseType == null) {
      Assertions.assertNull(failure.getCause());
    } else {
      Assertions.assertInstanceOf(expectedCauseType, failure.getCause());
    }
  }

  private static void assertInvalidIfMatch(String ifMatch) {
    IllegalArgumentException failure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryRequestUtils.parseStrongIfMatch(ifMatch, "table"));
    Assertions.assertEquals(
        "If-Match must contain exactly one strong entity tag", failure.getMessage());
  }
}
