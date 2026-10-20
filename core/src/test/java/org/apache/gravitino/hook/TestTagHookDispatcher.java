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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.Entity;
import org.apache.gravitino.authorization.FakeAuthorizationServices;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagChange;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestTagHookDispatcher {

  private TagHookDispatcher hookDispatcher;
  private TagDispatcher mockDispatcher;
  private OwnerDispatcher mockOwnerDispatcher;
  private GravitinoAuthorizer mockAuthorizer;

  @BeforeEach
  public void setUp() {
    mockDispatcher = mock(TagDispatcher.class);
    mockOwnerDispatcher = mock(OwnerDispatcher.class);
    mockAuthorizer = mock(GravitinoAuthorizer.class);
    hookDispatcher =
        new TagHookDispatcher(
            mockDispatcher,
            new FakeAuthorizationServices()
                .withOwnerDispatcher(mockOwnerDispatcher)
                .withAuthorizer(mockAuthorizer));
  }

  @Test
  public void testCreateTagThrowsWhenSetOwnerFails() {
    Tag mockTag = mock(Tag.class);
    when(mockDispatcher.createTag(any(), any(), any(), any(), any())).thenReturn(mockTag);

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
    verify(mockDispatcher).createTag(any(), any(), any(), any(), any());
  }

  @Test
  public void testDeleteTagInvalidatesNameIdMapping() {
    when(mockDispatcher.deleteTag("test_metalake", "test_tag")).thenReturn(true);

    Assertions.assertTrue(hookDispatcher.deleteTag("test_metalake", "test_tag"));
    verify(mockAuthorizer)
        .handleEntityNameIdMappingChange(
            "test_metalake",
            NameIdentifierUtil.ofTag("test_metalake", "test_tag"),
            Entity.EntityType.TAG);
  }

  @Test
  public void testDeleteMissingTagDoesNotInvalidateNameIdMapping() {
    when(mockDispatcher.deleteTag("test_metalake", "test_tag")).thenReturn(false);

    Assertions.assertFalse(hookDispatcher.deleteTag("test_metalake", "test_tag"));
    verify(mockAuthorizer, never()).handleEntityNameIdMappingChange(any(), any(), any());
  }

  @Test
  public void testRenameTagInvalidatesOldNameIdMapping() {
    TagChange[] changes = {TagChange.updateComment("new comment"), TagChange.rename("new_tag")};
    Tag mockTag = mock(Tag.class);
    when(mockDispatcher.alterTag("test_metalake", "test_tag", changes)).thenReturn(mockTag);

    Assertions.assertSame(mockTag, hookDispatcher.alterTag("test_metalake", "test_tag", changes));
    verify(mockAuthorizer)
        .handleEntityNameIdMappingChange(
            "test_metalake",
            NameIdentifierUtil.ofTag("test_metalake", "test_tag"),
            Entity.EntityType.TAG);
  }

  @Test
  public void testAlterTagWithoutRenameDoesNotInvalidateNameIdMapping() {
    TagChange[] changes = {TagChange.updateComment("new comment")};
    when(mockDispatcher.alterTag("test_metalake", "test_tag", changes)).thenReturn(mock(Tag.class));

    hookDispatcher.alterTag("test_metalake", "test_tag", changes);
    verify(mockAuthorizer, never()).handleEntityNameIdMappingChange(any(), any(), any());
  }

  @Test
  public void testFailedRenameTagDoesNotInvalidateNameIdMapping() {
    TagChange[] changes = {TagChange.rename("new_tag")};
    when(mockDispatcher.alterTag("test_metalake", "test_tag", changes))
        .thenThrow(new RuntimeException("Alter failed"));

    Assertions.assertThrows(
        RuntimeException.class,
        () -> hookDispatcher.alterTag("test_metalake", "test_tag", changes));
    verify(mockAuthorizer, never()).handleEntityNameIdMappingChange(any(), any(), any());
  }
}
