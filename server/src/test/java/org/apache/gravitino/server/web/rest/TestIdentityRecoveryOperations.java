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
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.GroupResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.responses.RoleResponse;
import org.apache.gravitino.dto.responses.UserResponse;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.exceptions.TombstoneNotFoundException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the shared deleted-resource protocol on users, groups, and roles. */
public class TestIdentityRecoveryOperations extends BaseOperationsTest {

  private static final String METALAKE = "metalake1";
  private static final long ENTITY_ID = 984273L;
  private static final String ETAG =
      "identity-representation-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static final AccessControlManager ACCESS_CONTROL_MANAGER =
      mock(AccessControlManager.class);
  private static final OwnerDispatcher OWNER_DISPATCHER = mock(OwnerDispatcher.class);
  private static final EntityStore ENTITY_STORE = mock(EntityStore.class);
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

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    when(config.get(ENABLE_AUTHORIZATION)).thenReturn(false);
    when(config.get(TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100000L);
    when(config.get(TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1000L);
    when(config.get(TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "accessControlDispatcher", ACCESS_CONTROL_MANAGER, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "ownerDispatcher", OWNER_DISPATCHER, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", ENTITY_STORE, true);
  }

  @BeforeEach
  public void resetMocks() throws IOException {
    reset(ACCESS_CONTROL_MANAGER, RECOVERY_MANAGER, ENTITY_STORE);
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(ENTITY_STORE.get(any(), any(), any())).thenReturn(metalake);
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
    resourceConfig.register(UserOperations.class);
    resourceConfig.register(GroupOperations.class);
    resourceConfig.register(RoleOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(RECOVERY_MANAGER).to(RecoverableDeletionManager.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testDeletedIdentityReadRestoreAndReplay() {
    DeletedEntityDTO deletedUser = deletedEntity("alice", RecoveryEntityType.USER, "user-1");
    DeletedEntityDTO deletedGroup =
        deletedEntity("engineering", RecoveryEntityType.GROUP, "group-1");
    DeletedEntityDTO deletedRole = deletedEntity("analyst", RecoveryEntityType.ROLE, "role-1");
    UserEntity user = userEntity();
    GroupEntity group = groupEntity();
    RoleEntity role = roleEntity();

    when(RECOVERY_MANAGER.listDeletedUsers(NamespaceUtil.ofUser(METALAKE), "alice", ENTITY_ID))
        .thenReturn(List.of(deletedUser));
    when(RECOVERY_MANAGER.getDeletedUser(NamespaceUtil.ofUser(METALAKE), "alice", ENTITY_ID))
        .thenReturn(deletedUser);
    when(RECOVERY_MANAGER.restoreDeletedUser(
            NamespaceUtil.ofUser(METALAKE), "alice", ENTITY_ID, ETAG))
        .thenReturn(user);
    when(RECOVERY_MANAGER.listDeletedGroups(
            NamespaceUtil.ofGroup(METALAKE), "engineering", ENTITY_ID))
        .thenReturn(List.of(deletedGroup));
    when(RECOVERY_MANAGER.getDeletedGroup(
            NamespaceUtil.ofGroup(METALAKE), "engineering", ENTITY_ID))
        .thenReturn(deletedGroup);
    when(RECOVERY_MANAGER.restoreDeletedGroup(
            NamespaceUtil.ofGroup(METALAKE), "engineering", ENTITY_ID, ETAG))
        .thenReturn(group);
    when(RECOVERY_MANAGER.listDeletedRoles(NamespaceUtil.ofRole(METALAKE), "analyst", ENTITY_ID))
        .thenReturn(List.of(deletedRole));
    when(RECOVERY_MANAGER.getDeletedRole(NamespaceUtil.ofRole(METALAKE), "analyst", ENTITY_ID))
        .thenReturn(deletedRole);
    when(RECOVERY_MANAGER.restoreDeletedRole(
            NamespaceUtil.ofRole(METALAKE), "analyst", ENTITY_ID, ETAG))
        .thenReturn(role);

    assertDeletedReadAndList("users", "alice", deletedUser);
    assertDeletedReadAndList("groups", "engineering", deletedGroup);
    assertDeletedReadAndList("roles", "analyst", deletedRole);

    Response userRestore =
        patchRestore("users", "alice", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), userRestore.getStatus());
    Assertions.assertEquals("alice", userRestore.readEntity(UserResponse.class).getUser().name());
    Response userReplay =
        patchRestore("users", "alice", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), userReplay.getStatus());

    Response groupRestore =
        patchRestore("groups", "engineering", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), groupRestore.getStatus());
    Assertions.assertEquals(
        "engineering", groupRestore.readEntity(GroupResponse.class).getGroup().name());
    Response groupReplay =
        patchRestore("groups", "engineering", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), groupReplay.getStatus());

    Response roleRestore =
        patchRestore("roles", "analyst", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), roleRestore.getStatus());
    Assertions.assertEquals("analyst", roleRestore.readEntity(RoleResponse.class).getRole().name());
    Response roleReplay =
        patchRestore("roles", "analyst", String.valueOf(ENTITY_ID), '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), roleReplay.getStatus());

    verify(RECOVERY_MANAGER, times(2))
        .restoreDeletedUser(NamespaceUtil.ofUser(METALAKE), "alice", ENTITY_ID, ETAG);
    verify(RECOVERY_MANAGER, times(2))
        .restoreDeletedGroup(NamespaceUtil.ofGroup(METALAKE), "engineering", ENTITY_ID, ETAG);
    verify(RECOVERY_MANAGER, times(2))
        .restoreDeletedRole(NamespaceUtil.ofRole(METALAKE), "analyst", ENTITY_ID, ETAG);
    verify(ACCESS_CONTROL_MANAGER, never()).getUser(any(), any());
    verify(ACCESS_CONTROL_MANAGER, never()).getGroup(any(), any());
    verify(ACCESS_CONTROL_MANAGER, never()).getRole(any(), any());
  }

  @Test
  public void testLiveIdentityListsAcceptExactNameFilters() {
    when(ACCESS_CONTROL_MANAGER.listUserNames(METALAKE)).thenReturn(new String[] {"alice", "bob"});
    when(ACCESS_CONTROL_MANAGER.listGroupNames(METALAKE))
        .thenReturn(new String[] {"engineering", "finance"});
    when(ACCESS_CONTROL_MANAGER.listRoleNames(METALAKE))
        .thenReturn(new String[] {"analyst", "admin"});

    Assertions.assertArrayEquals(
        new String[] {"alice"},
        request(collectionTarget("users").queryParam("name", "alice"))
            .get()
            .readEntity(NameListResponse.class)
            .getNames());
    Assertions.assertArrayEquals(
        new String[] {"engineering"},
        request(collectionTarget("groups").queryParam("name", "engineering"))
            .get()
            .readEntity(NameListResponse.class)
            .getNames());
    Assertions.assertArrayEquals(
        new String[] {"analyst"},
        request(collectionTarget("roles").queryParam("name", "analyst"))
            .get()
            .readEntity(NameListResponse.class)
            .getNames());
    verifyNoInteractions(RECOVERY_MANAGER);
  }

  @Test
  public void testIdentityRecoverySelectorsBodiesAndMediaTypes() {
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(
                collectionTarget("users")
                    .queryParam("include", "deleted")
                    .queryParam("details", true))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(collectionTarget("users").queryParam("include", "deleted").queryParam("id", "0"))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(
                collectionTarget("groups")
                    .queryParam("include", "deleted")
                    .queryParam("details", true))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(
                collectionTarget("roles")
                    .queryParam("include", "deleted")
                    .queryParam("id", "invalid"))
            .get()
            .getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(itemTarget("users", "alice").queryParam("include", "deleted")).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(collectionTarget("groups").queryParam("id", ENTITY_ID)).get().getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        request(itemTarget("roles", "analyst").queryParam("include", "other")).get().getStatus());

    Response missingInclude =
        patchRestore(
            "users",
            "alice",
            String.valueOf(ENTITY_ID),
            '"' + ETAG + '"',
            new EntityRestoreRequest(false),
            null);
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), missingInclude.getStatus());
    Response missingId = patchRestore("groups", "engineering", null, '"' + ETAG + '"');
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), missingId.getStatus());
    Response missingIfMatch = patchRestore("roles", "analyst", String.valueOf(ENTITY_ID), null);
    Assertions.assertEquals(428, missingIfMatch.getStatus());
    Assertions.assertEquals(
        ErrorConstants.PRECONDITION_REQUIRED_CODE,
        missingIfMatch.readEntity(ErrorResponse.class).getCode());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore("users", "alice", String.valueOf(ENTITY_ID), "W/\"weak\"").getStatus());
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(),
        patchRestore("groups", "engineering", String.valueOf(ENTITY_ID), "\"one\", \"two\"")
            .getStatus());

    Response invalidBody =
        patchRestore(
            "roles",
            "analyst",
            String.valueOf(ENTITY_ID),
            '"' + ETAG + '"',
            new EntityRestoreRequest(true));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), invalidBody.getStatus());

    Response unknownBodyField =
        patchRaw(
            "roles",
            "analyst",
            String.valueOf(ENTITY_ID),
            '"' + ETAG + '"',
            "{\"deleted\":false,\"unexpected\":true}");
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), unknownBodyField.getStatus());

    Response emptyBody =
        patchRaw("roles", "analyst", String.valueOf(ENTITY_ID), '"' + ETAG + '"', "");
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), emptyBody.getStatus());

    Response unsupportedMediaType =
        itemTarget("users", "alice")
            .queryParam("include", "deleted")
            .queryParam("id", ENTITY_ID)
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", '"' + ETAG + '"')
            .method("PATCH", Entity.entity("deleted=false", MediaType.TEXT_PLAIN_TYPE));
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), unsupportedMediaType.getStatus());

    Response ordinaryJson =
        itemTarget("groups", "engineering")
            .queryParam("include", "deleted")
            .queryParam("id", ENTITY_ID)
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", '"' + ETAG + '"')
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(false), MediaType.APPLICATION_JSON_TYPE));
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), ordinaryJson.getStatus());
    verifyNoInteractions(RECOVERY_MANAGER);
  }

  @Test
  public void testIdentityRecoveryTypedFailures() {
    doThrow(new TombstoneChangedException("changed"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedUser(any(), eq("alice"), eq(ENTITY_ID), eq("stale"));
    doThrow(new TombstoneExpiredException("expired"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedGroup(any(), eq("engineering"), eq(ENTITY_ID), eq("expired"));
    doThrow(
            new RecoveryConflictException(
                RecoveryConflictReason.EXTERNAL_ID_OCCUPIED, "external id occupied"))
        .when(RECOVERY_MANAGER)
        .restoreDeletedUser(any(), eq("alice"), eq(ENTITY_ID), eq("conflict"));
    doThrow(new TombstoneNotFoundException("missing"))
        .when(RECOVERY_MANAGER)
        .getDeletedUser(any(), eq("missing"), eq(ENTITY_ID));

    Response stale = patchRestore("users", "alice", String.valueOf(ENTITY_ID), "\"stale\"");
    Assertions.assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), stale.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_CHANGED_CODE, stale.readEntity(ErrorResponse.class).getCode());

    Response expired =
        patchRestore("groups", "engineering", String.valueOf(ENTITY_ID), "\"expired\"");
    Assertions.assertEquals(Response.Status.GONE.getStatusCode(), expired.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_EXPIRED_CODE, expired.readEntity(ErrorResponse.class).getCode());

    Response conflict = patchRestore("users", "alice", String.valueOf(ENTITY_ID), "\"conflict\"");
    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), conflict.getStatus());
    ErrorResponse conflictBody = conflict.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.RECOVERY_CONFLICT_CODE, conflictBody.getCode());
    Assertions.assertEquals(RecoveryConflictReason.EXTERNAL_ID_OCCUPIED, conflictBody.getReason());

    Response missing =
        request(
                itemTarget("users", "missing")
                    .queryParam("include", "deleted")
                    .queryParam("id", ENTITY_ID))
            .get();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missing.getStatus());
    verify(ACCESS_CONTROL_MANAGER, never()).getUser(any(), any());
    verify(ACCESS_CONTROL_MANAGER, never()).getGroup(any(), any());
    verify(ACCESS_CONTROL_MANAGER, never()).getRole(any(), any());
  }

  private void assertDeletedReadAndList(
      String collection, String name, DeletedEntityDTO deletedEntity) {
    Response listResponse =
        request(
                collectionTarget(collection)
                    .queryParam("include", "deleted")
                    .queryParam("name", name)
                    .queryParam("id", ENTITY_ID))
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), listResponse.getStatus());
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {deletedEntity},
        listResponse.readEntity(DeletedEntityListResponse.class).getDeletedEntities());

    Response itemResponse =
        request(
                itemTarget(collection, name)
                    .queryParam("include", "deleted")
                    .queryParam("id", ENTITY_ID))
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), itemResponse.getStatus());
    Assertions.assertEquals('"' + ETAG + '"', itemResponse.getHeaderString("ETag"));
    Assertions.assertEquals(
        deletedEntity, itemResponse.readEntity(DeletedEntityResponse.class).getDeletedEntity());
  }

  private WebTarget collectionTarget(String collection) {
    return target("/metalakes/" + METALAKE + "/" + collection);
  }

  private WebTarget itemTarget(String collection, String name) {
    return target("/metalakes/" + METALAKE + "/" + collection + "/" + name);
  }

  private Invocation.Builder request(WebTarget target) {
    return target
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json");
  }

  private Response patchRestore(String collection, String name, String id, String ifMatch) {
    return patchRestore(collection, name, id, ifMatch, new EntityRestoreRequest(false));
  }

  private Response patchRestore(
      String collection, String name, String id, String ifMatch, EntityRestoreRequest request) {
    return patchRestore(collection, name, id, ifMatch, request, "deleted");
  }

  private Response patchRestore(
      String collection,
      String name,
      String id,
      String ifMatch,
      EntityRestoreRequest request,
      String include) {
    WebTarget target = target("/metalakes/" + METALAKE + "/" + collection + "/" + name);
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
            .accept("application/vnd.gravitino.v1+json");
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return builder.method(
        "PATCH", Entity.entity(request, MediaType.valueOf("application/merge-patch+json")));
  }

  private Response patchRaw(
      String collection, String name, String id, String ifMatch, String request) {
    return itemTarget(collection, name)
        .queryParam("include", "deleted")
        .queryParam("id", id)
        .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .header("If-Match", ifMatch)
        .method("PATCH", Entity.entity(request, MediaType.valueOf("application/merge-patch+json")));
  }

  private static DeletedEntityDTO deletedEntity(
      String name, RecoveryEntityType type, String deletionId) {
    return DeletedEntityDTO.builder()
        .withId(String.valueOf(ENTITY_ID))
        .withDeletionId(deletionId)
        .withName(name)
        .withType(type)
        .withDeletedAt(1_784_800_000_000L)
        .withExpiresAt(1_785_404_800_000L)
        .withDeletedBy("alice")
        .withVersion(2L)
        .withEtag(ETAG)
        .withLatestForName(true)
        .withRestorable(true)
        .build();
  }

  private static UserEntity userEntity() {
    return UserEntity.builder()
        .withId(ENTITY_ID)
        .withName("alice")
        .withNamespace(AuthorizationUtils.ofUserNamespace(METALAKE))
        .withExternalId("idp-alice")
        .withEnabled(true)
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(auditInfo())
        .build();
  }

  private static GroupEntity groupEntity() {
    return GroupEntity.builder()
        .withId(ENTITY_ID)
        .withName("engineering")
        .withNamespace(AuthorizationUtils.ofGroupNamespace(METALAKE))
        .withExternalId("idp-engineering")
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(auditInfo())
        .build();
  }

  private static RoleEntity roleEntity() {
    return RoleEntity.builder()
        .withId(ENTITY_ID)
        .withName("analyst")
        .withNamespace(AuthorizationUtils.ofRoleNamespace(METALAKE))
        .withProperties(Collections.emptyMap())
        .withSecurableObjects(Collections.emptyList())
        .withAuditInfo(auditInfo())
        .build();
  }

  private static AuditInfo auditInfo() {
    return AuditInfo.builder().withCreator("alice").withCreateTime(Instant.now()).build();
  }
}
