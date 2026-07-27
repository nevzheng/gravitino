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
package org.apache.gravitino.iceberg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

public class TestRESTServiceDeletionMode {

  @Test
  public void testStandaloneRejectsSoftDelete() {
    IcebergConfig enabled =
        new IcebergConfig(ImmutableMap.of("soft-delete.enabled", Boolean.TRUE.toString()));

    assertThrows(
        IllegalArgumentException.class, () -> RESTService.validateDeletionMode(enabled, false));
    assertDoesNotThrow(() -> RESTService.validateDeletionMode(enabled, true));
  }

  @Test
  public void testStandaloneAllowsAsyncHardDelete() {
    IcebergConfig disabled =
        new IcebergConfig(ImmutableMap.of("soft-delete.enabled", Boolean.FALSE.toString()));

    assertDoesNotThrow(() -> RESTService.validateDeletionMode(disabled, false));
  }

  @Test
  public void testDeletionTestHookIsDisabledAndUnregisteredByDefault() {
    IcebergConfig config = new IcebergConfig(ImmutableMap.of());

    assertFalse(config.get(IcebergConfig.DELETION_TEST_HOOK_ENABLED));
    assertFalse(RESTService.shouldRegisterDeletionTestHook(config, true));
    assertFalse(RESTService.shouldRegisterDeletionTestHook(config, false));
  }

  @Test
  public void testDeletionTestHookRequiresAuxModeAndNonblankToken() {
    IcebergConfig missingToken =
        new IcebergConfig(ImmutableMap.of("deletion.test-hook.enabled", Boolean.TRUE.toString()));
    assertThrows(
        IllegalArgumentException.class, () -> RESTService.validateDeletionMode(missingToken, true));

    IcebergConfig enabled =
        new IcebergConfig(
            ImmutableMap.of(
                "deletion.test-hook.enabled",
                Boolean.TRUE.toString(),
                "deletion.test-hook.token",
                "integration-secret"));
    assertThrows(
        IllegalArgumentException.class, () -> RESTService.validateDeletionMode(enabled, false));
    assertDoesNotThrow(() -> RESTService.validateDeletionMode(enabled, true));
    assertTrue(RESTService.shouldRegisterDeletionTestHook(enabled, true));
  }
}
