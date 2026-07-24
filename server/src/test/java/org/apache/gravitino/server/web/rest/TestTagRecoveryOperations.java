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

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.responses.TagListResponse;
import org.apache.gravitino.dto.responses.TagResponse;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NamespaceUtil;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the deleted-resource protocol on tag REST resources. */
public class TestTagRecoveryOperations extends JerseyTest {

  private static final String METALAKE = "test_metalake";
  private static final String TAG = "sensitive";
  private static final long ENTITY_ID = 829L;
  private static final String ETAG = "tag-etag";
  private static final String RESPONSE_MEDIA_TYPE = "application/vnd.gravitino.v1+json";
  private static final MediaType MERGE_PATCH = MediaType.valueOf("application/merge-patch+json");

  private static final TagDispatcher DISPATCHER = mock(TagDispatcher.class);
  private static final RecoverableDeletionManager RECOVERY_MANAGER =
      mock(RecoverableDeletionManager.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /** Disables authorization so REST protocol behavior can be tested independently. */
  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
  }

  /** Resets the shared Jersey-bound mocks before each test. */
  @BeforeEach
  public void resetMocks() {
    reset(DISPATCHER, RECOVERY_MANAGER);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(TagOperations.class);
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(DISPATCHER).to(TagDispatcher.class).ranked(2);
            bind(RECOVERY_MANAGER).to(RecoverableDeletionManager.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Verifies deleted discovery, exact ETags, restore, replay, and no dispatcher reload. */
  @Test
  public void testDeletedTagReadRestoreAndReplayWithoutDispatcherReload() {
    DeletedEntityDTO deleted = deletedTag();
    TagEntity restored = tagEntity(TAG, "restored");
    when(RECOVERY_MANAGER.listDeletedTags(NamespaceUtil.ofTag(METALAKE), TAG, ENTITY_ID))
        .thenReturn(List.of(deleted));
    when(RECOVERY_MANAGER.getDeletedTag(NamespaceUtil.ofTag(METALAKE), TAG, ENTITY_ID))
        .thenReturn(deleted);
    when(RECOVERY_MANAGER.restoreDeletedTag(NamespaceUtil.ofTag(METALAKE), TAG, ENTITY_ID, ETAG))
        .thenReturn(restored);

    Response list =
        request(
                tags()
                    .queryParam("include", "deleted")
                    .queryParam("name", TAG)
                    .queryParam("id", ENTITY_ID))
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), list.getStatus());
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {deleted},
        list.readEntity(DeletedEntityListResponse.class).getDeletedEntities());

    Response exact =
        request(tag(TAG).queryParam("include", "deleted").queryParam("id", ENTITY_ID)).get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), exact.getStatus());
    Assertions.assertEquals('"' + ETAG + '"', exact.getHeaderString("ETag"));
    Assertions.assertEquals(
        deleted, exact.readEntity(DeletedEntityResponse.class).getDeletedEntity());

    Response restoredResponse = patchRestore(String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), restoredResponse.getStatus());
    Tag restoredTag = restoredResponse.readEntity(TagResponse.class).getTag();
    Assertions.assertEquals(TAG, restoredTag.name());
    Assertions.assertEquals("restored", restoredTag.comment());

    Response replay = patchRestore(String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), replay.getStatus());
    Assertions.assertEquals(TAG, replay.readEntity(TagResponse.class).getTag().name());

    verify(RECOVERY_MANAGER, times(2))
        .restoreDeletedTag(NamespaceUtil.ofTag(METALAKE), TAG, ENTITY_ID, ETAG);
    verifyNoInteractions(DISPATCHER);
  }

  /** Verifies the new selector preserves exact-name filtering on both live list shapes. */
  @Test
  public void testTagRecoveryFiltersPreserveLiveListBehavior() {
    TagEntity selected = tagEntity(TAG, "selected");
    TagEntity other = tagEntity("public", "other");
    when(DISPATCHER.listTags(METALAKE)).thenReturn(new String[] {TAG, other.name()});
    when(DISPATCHER.listTagsInfo(METALAKE)).thenReturn(new Tag[] {selected, other});

    Response names = request(tags().queryParam("name", TAG)).get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), names.getStatus());
    Assertions.assertArrayEquals(
        new String[] {TAG}, names.readEntity(NameListResponse.class).getNames());

    Response details = request(tags().queryParam("details", true).queryParam("name", TAG)).get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), details.getStatus());
    Tag[] selectedTags = details.readEntity(TagListResponse.class).getTags();
    Assertions.assertEquals(1, selectedTags.length);
    Assertions.assertEquals(TAG, selectedTags[0].name());
    verifyNoInteractions(RECOVERY_MANAGER);
  }

  /** Verifies strict selectors, merge bodies, entity tags, and PATCH media types. */
  @Test
  public void testTagRecoverySelectorsBodiesAndMediaTypes() {
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tags().queryParam("include", "deleted").queryParam("details", true))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tags().queryParam("include", "deleted").queryParam("id", "0")).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tags().queryParam("include", "deleted").queryParam("id", "not-a-number"))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tags().queryParam("include", "other")).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tags().queryParam("id", ENTITY_ID)).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tag(TAG).queryParam("include", "deleted")).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tag(TAG).queryParam("include", "other")).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(tag(TAG).queryParam("id", ENTITY_ID)).get().getStatus());

    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(String.valueOf(ENTITY_ID), '"' + ETAG + '"', null).getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(null, '"' + ETAG + '"').getStatus());

    Response missingIfMatch = patchRestore(String.valueOf(ENTITY_ID), null);
    Assertions.assertEquals(428, missingIfMatch.getStatus());
    Assertions.assertEquals(
        ErrorConstants.PRECONDITION_REQUIRED_CODE,
        missingIfMatch.readEntity(ErrorResponse.class).getCode());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(String.valueOf(ENTITY_ID), "W/\"weak\"").getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(String.valueOf(ENTITY_ID), ETAG).getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(String.valueOf(ENTITY_ID), "\"one\", \"two\"").getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore(
                String.valueOf(ENTITY_ID),
                '"' + ETAG + '"',
                "deleted",
                new EntityRestoreRequest(true))
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRaw("{\"deleted\":false,\"unexpected\":true}", MERGE_PATCH).getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), patchRaw("", MERGE_PATCH).getStatus());
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(),
        patchRaw("{\"deleted\":false}", MediaType.APPLICATION_JSON_TYPE).getStatus());
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(),
        patchRaw("deleted=false", MediaType.TEXT_PLAIN_TYPE).getStatus());
    verifyNoInteractions(RECOVERY_MANAGER, DISPATCHER);
  }

  /** Verifies stable HTTP mappings for typed recovery failures. */
  @Test
  public void testTagRecoveryTypedFailures() {
    doThrow(new TombstoneChangedException("changed"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedTag(any(), eq(TAG), eq(ENTITY_ID), eq("stale"));
    doThrow(new TombstoneExpiredException("expired"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedTag(any(), eq(TAG), eq(ENTITY_ID), eq("expired"));
    doThrow(new RecoveryConflictException(RecoveryConflictReason.NAME_OCCUPIED, "name occupied"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedTag(any(), eq(TAG), eq(ENTITY_ID), eq("conflict"));
    doThrow(new TombstoneNotFoundException("missing"))
        .when(RECOVERY_MANAGER)
        .getDeletedTag(any(), eq("missing"), eq(ENTITY_ID));

    Response stale = patchRestore(String.valueOf(ENTITY_ID), "\"stale\"");
    Assertions.assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), stale.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_CHANGED_CODE, stale.readEntity(ErrorResponse.class).getCode());

    Response expired = patchRestore(String.valueOf(ENTITY_ID), "\"expired\"");
    Assertions.assertEquals(Response.Status.GONE.getStatusCode(), expired.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_EXPIRED_CODE, expired.readEntity(ErrorResponse.class).getCode());

    Response conflict = patchRestore(String.valueOf(ENTITY_ID), "\"conflict\"");
    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), conflict.getStatus());
    ErrorResponse conflictBody = conflict.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.RECOVERY_CONFLICT_CODE, conflictBody.getCode());
    Assertions.assertEquals(RecoveryConflictReason.NAME_OCCUPIED, conflictBody.getReason());

    Response missing =
        request(tag("missing").queryParam("include", "deleted").queryParam("id", ENTITY_ID)).get();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missing.getStatus());
    verify(DISPATCHER, never()).getTag(any(), any());
  }

  private WebTarget tags() {
    return target("/metalakes/" + METALAKE + "/tags");
  }

  private WebTarget tag(String name) {
    return tags().path(name);
  }

  private Invocation.Builder request(WebTarget target) {
    return target.request(MediaType.APPLICATION_JSON_TYPE).accept(RESPONSE_MEDIA_TYPE);
  }

  private Response patchRestore(String id, String ifMatch) {
    return patchRestore(id, ifMatch, "deleted");
  }

  private Response patchRestore(String id, String ifMatch, String include) {
    return patchRestore(id, ifMatch, include, new EntityRestoreRequest(false));
  }

  private Response patchRestore(
      String id, String ifMatch, String include, EntityRestoreRequest request) {
    WebTarget target = tag(TAG);
    if (include != null) {
      target = target.queryParam("include", include);
    }
    if (id != null) {
      target = target.queryParam("id", id);
    }
    Invocation.Builder builder =
        target
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept(RESPONSE_MEDIA_TYPE);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return builder.method("PATCH", Entity.entity(request, MERGE_PATCH));
  }

  private Response patchRaw(String body, MediaType mediaType) {
    return tag(TAG)
        .queryParam("include", "deleted")
        .queryParam("id", ENTITY_ID)
        .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept(RESPONSE_MEDIA_TYPE)
        .header("If-Match", '"' + ETAG + '"')
        .method("PATCH", Entity.entity(body, mediaType));
  }

  private static DeletedEntityDTO deletedTag() {
    return DeletedEntityDTO.builder()
        .withId(String.valueOf(ENTITY_ID))
        .withDeletionId("tag-deletion")
        .withName(TAG)
        .withType(RecoveryEntityType.TAG)
        .withDeletedAt(1_784_800_000_000L)
        .withExpiresAt(1_785_404_800_000L)
        .withDeletedBy("alice")
        .withVersion(3L)
        .withEtag(ETAG)
        .withLatestForName(true)
        .withRestorable(true)
        .build();
  }

  private static TagEntity tagEntity(String name, String comment) {
    return TagEntity.builder()
        .withId(ENTITY_ID)
        .withName(name)
        .withNamespace(NamespaceUtil.ofTag(METALAKE))
        .withComment(comment)
        .withProperties(Map.of("classification", "restricted"))
        .withAuditInfo(
            AuditInfo.builder()
                .withCreator("alice")
                .withCreateTime(Instant.ofEpochMilli(1_700_000_000_000L))
                .build())
        .build();
  }
}
