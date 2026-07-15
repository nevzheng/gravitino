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
package org.apache.gravitino.server.web.rest.v1.support;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.v1.model.MetalakeResource;
import org.apache.gravitino.server.web.rest.v1.error.V1PreconditionFailedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests for V1 resource validators and mutation preconditions. */
public class TestV1ResourceSupport {

  @BeforeAll
  public static void setUpLockManager() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Test
  public void testEntityTagIsStableStrongAndDerivedFromPublicRepresentation() {
    EntityTag first = V1ResourceSupport.entityTag(resource("comment", Map.of("key", "value")));
    EntityTag second = V1ResourceSupport.entityTag(resource("comment", Map.of("key", "value")));
    EntityTag changed = V1ResourceSupport.entityTag(resource("changed", Map.of("key", "value")));

    assertFalse(first.isWeak());
    assertEquals(first, second);
    assertNotEquals(first, changed);
    assertEquals(64, first.getValue().length());
  }

  @Test
  public void testRequireIfMatchAcceptsTheCurrentStrongValidator() {
    EntityTag current = V1ResourceSupport.entityTag(resource("comment", Map.of()));

    assertDoesNotThrow(
        () -> V1ResourceSupport.requireIfMatch(headersWithIfMatch(current.toString()), current));
  }

  @Test
  public void testRequireIfMatchRejectsMissingWeakWildcardRepeatedAndStaleValidators() {
    EntityTag current = V1ResourceSupport.entityTag(resource("comment", Map.of()));

    assertPreconditionFailure(headersWithIfMatch());
    assertPreconditionFailure(headersWithIfMatch("W/" + current));
    assertPreconditionFailure(headersWithIfMatch("*"));
    assertPreconditionFailure(headersWithIfMatch(current.toString(), current.toString()));
    assertPreconditionFailure(headersWithIfMatch("\"different\""));
  }

  @Test
  public void testConditionalMutationUsesTheValidatedCurrentResource() {
    MetalakeResource current = resource("comment", Map.of("key", "value"));
    EntityTag entityTag = V1ResourceSupport.entityTag(current);
    AtomicReference<MetalakeResource> observedResource = new AtomicReference<>();

    String result =
        V1ConditionalMutation.execute(
            headersWithIfMatch(entityTag.toString()),
            () -> current,
            resource -> resource,
            resource -> {
              observedResource.set(resource);
              return "updated";
            });

    assertEquals("updated", result);
    assertSame(current, observedResource.get());
  }

  @Test
  public void testConditionalMutationDoesNotRunMutationAfterFailedPrecondition() {
    MetalakeResource current = resource("comment", Map.of("key", "value"));
    AtomicBoolean mutationInvoked = new AtomicBoolean();

    assertThrows(
        V1PreconditionFailedException.class,
        () ->
            V1ConditionalMutation.execute(
                headersWithIfMatch("\"stale\""),
                () -> current,
                resource -> resource,
                () -> {
                  mutationInvoked.set(true);
                  return "updated";
                }));

    assertFalse(mutationInvoked.get());
  }

  private static void assertPreconditionFailure(HttpHeaders headers) {
    EntityTag current = V1ResourceSupport.entityTag(resource("comment", Map.of()));
    V1PreconditionFailedException exception =
        assertThrows(
            V1PreconditionFailedException.class,
            () -> V1ResourceSupport.requireIfMatch(headers, current));
    assertEquals(V1PreconditionFailedException.SAFE_DESCRIPTION, exception.safeDescription());
  }

  private static HttpHeaders headersWithIfMatch(String... values) {
    HttpHeaders headers = mock(HttpHeaders.class);
    List<String> headerValues = values.length == 0 ? null : List.of(values);
    when(headers.getRequestHeader(HttpHeaders.IF_MATCH)).thenReturn(headerValues);
    return headers;
  }

  private static MetalakeResource resource(String comment, Map<String, String> properties) {
    return new MetalakeResource("metalakes/demo", "demo", comment, properties, null);
  }
}
