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
package org.apache.gravitino.hook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestTagHookDispatcher {

  private TagHookDispatcher hookDispatcher;
  private TagDispatcher mockDispatcher;
  private OwnerDispatcher mockOwnerDispatcher;

  @BeforeEach
  public void setUp() {
    mockDispatcher = mock(TagDispatcher.class);
    mockOwnerDispatcher = mock(OwnerDispatcher.class);
    hookDispatcher = new TagHookDispatcher(mockDispatcher, mockOwnerDispatcher);
  }

  @Test
  public void testCreateTagThrowsWhenSetOwnerFails() {
    Tag mockTag = mock(Tag.class);
    when(mockDispatcher.createTag(any(), any(), any(), any())).thenReturn(mockTag);

    doThrow(new RuntimeException("Set owner failed"))
        .when(mockOwnerDispatcher)
        .setOwner(any(), any(), any(), any());

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                hookDispatcher.createTag(
                    "test_metalake", "test_tag", "comment", Collections.emptyMap()));
    Assertions.assertEquals("Set owner failed", thrown.getMessage());
    verify(mockDispatcher).createTag(any(), any(), any(), any());
  }

  @Test
  public void testCreateTagWithoutOwnerDispatcher() {
    Tag mockTag = mock(Tag.class);
    when(mockDispatcher.createTag(any(), any(), any(), any())).thenReturn(mockTag);

    Tag tag =
        new TagHookDispatcher(mockDispatcher, null)
            .createTag("test_metalake", "test_tag", "comment", Collections.emptyMap());

    Assertions.assertSame(mockTag, tag);
    verify(mockDispatcher).createTag(any(), any(), any(), any());
  }
}
