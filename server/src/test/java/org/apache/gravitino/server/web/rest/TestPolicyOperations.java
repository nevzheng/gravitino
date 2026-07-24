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

import static org.apache.gravitino.Configs.CACHE_ENABLED;
import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.dto.util.DTOConverters.toDTO;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RecoveryConflictReason;
import org.apache.gravitino.RecoveryEntityType;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.dto.policy.PolicyContentDTO;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.requests.PolicyCreateRequest;
import org.apache.gravitino.dto.requests.PolicySetRequest;
import org.apache.gravitino.dto.requests.PolicyUpdateRequest;
import org.apache.gravitino.dto.requests.PolicyUpdatesRequest;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.DropResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.MetadataObjectListResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.responses.PolicyListResponse;
import org.apache.gravitino.dto.responses.PolicyResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchPolicyException;
import org.apache.gravitino.exceptions.PolicyAlreadyExistsException;
import org.apache.gravitino.exceptions.RecoveryConflictException;
import org.apache.gravitino.exceptions.TombstoneChangedException;
import org.apache.gravitino.exceptions.TombstoneExpiredException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyChange;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.policy.PolicyManager;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.utils.NamespaceUtil;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestPolicyOperations extends BaseOperationsTest {

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final PolicyManager policyManager = mock(PolicyManager.class);
  private final RecoverableDeletionManager recoverableDeletionManager =
      mock(RecoverableDeletionManager.class);

  private final String metalake = "test_metalake";
  private final Namespace policyNamespace = NamespaceUtil.ofPolicy(metalake);

  private final AuditInfo testAuditInfo1 =
      AuditInfo.builder().withCreator("user1").withCreateTime(Instant.now()).build();

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(false).when(config).get(CACHE_ENABLED);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
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
    resourceConfig.register(PolicyOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(policyManager).to(PolicyDispatcher.class).ranked(2);
            bind(recoverableDeletionManager).to(RecoverableDeletionManager.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListPolicies() {
    String[] policies = new String[] {"policy1", "policy2"};
    when(policyManager.listPolicies(metalake)).thenReturn(policies);

    Response response =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

    NameListResponse nameListResponse = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, nameListResponse.getCode());
    Assertions.assertArrayEquals(policies, nameListResponse.getNames());

    when(policyManager.listPolicies(metalake)).thenReturn(null);
    Response resp1 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());

    NameListResponse nameListResponse1 = resp1.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, nameListResponse1.getCode());
    Assertions.assertEquals(0, nameListResponse1.getNames().length);

    when(policyManager.listPolicies(metalake)).thenReturn(new String[0]);
    Response resp2 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp2.getStatus());

    NameListResponse nameListResponse2 = resp2.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, nameListResponse2.getCode());
    Assertions.assertEquals(0, nameListResponse2.getNames().length);

    // Test throw NoSuchMetalakeException
    doThrow(new NoSuchMetalakeException("mock error")).when(policyManager).listPolicies(metalake);
    Response resp3 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp3.getStatus());

    ErrorResponse errorResp = resp3.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchMetalakeException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(policyManager).listPolicies(metalake);
    Response resp4 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp4.getStatus());

    ErrorResponse errorResp1 = resp4.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp1.getType());
  }

  @Test
  public void testListPolicyInfos() {
    ImmutableMap<String, Object> contentFields = ImmutableMap.of("target_file_size_bytes", 1000);
    PolicyContent content =
        PolicyContents.custom(contentFields, ImmutableSet.of(MetadataObject.Type.TABLE), null);
    PolicyEntity policy1 =
        PolicyEntity.builder()
            .withId(1L)
            .withName("policy1")
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withEnabled(false)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();

    PolicyEntity policy2 =
        PolicyEntity.builder()
            .withId(1L)
            .withName("policy2")
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withEnabled(false)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();

    PolicyEntity[] policies = new PolicyEntity[] {policy1, policy2};
    when(policyManager.listPolicyInfos(metalake)).thenReturn(policies);

    Response resp =
        target(policyPath(metalake))
            .queryParam("details", true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    PolicyListResponse policyListResp = resp.readEntity(PolicyListResponse.class);
    Assertions.assertEquals(0, policyListResp.getCode());
    Assertions.assertEquals(policies.length, policyListResp.getPolicies().length);

    Assertions.assertEquals(policy1.name(), policyListResp.getPolicies()[0].name());
    Assertions.assertEquals(policy1.comment(), policyListResp.getPolicies()[0].comment());
    Assertions.assertEquals(Optional.empty(), policyListResp.getPolicies()[0].inherited());

    Assertions.assertEquals(policy2.name(), policyListResp.getPolicies()[1].name());
    Assertions.assertEquals(policy2.comment(), policyListResp.getPolicies()[1].comment());
    Assertions.assertEquals(Optional.empty(), policyListResp.getPolicies()[1].inherited());

    // Test return empty array
    when(policyManager.listPolicyInfos(metalake)).thenReturn(new PolicyEntity[0]);
    Response resp2 =
        target(policyPath(metalake))
            .queryParam("details", true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp2.getStatus());

    PolicyListResponse policyListResp2 = resp2.readEntity(PolicyListResponse.class);
    Assertions.assertEquals(0, policyListResp2.getCode());
    Assertions.assertEquals(0, policyListResp2.getPolicies().length);
  }

  @Test
  public void testDeletedPolicyReadRestoreAndReplay() {
    String etag =
        "deletion-policy-1-representation-"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DeletedEntityDTO deletedPolicy =
        DeletedEntityDTO.builder()
            .withId("984273")
            .withDeletionId("policy-1")
            .withName("policy1")
            .withType(RecoveryEntityType.POLICY)
            .withDeletedAt(1_784_800_000_000L)
            .withExpiresAt(1_785_404_800_000L)
            .withDeletedBy("alice")
            .withVersion(2L)
            .withEtag(etag)
            .withLatestForName(true)
            .withRestorable(true)
            .build();
    when(recoverableDeletionManager.listDeletedPolicies(policyNamespace, "policy1", 984273L))
        .thenReturn(List.of(deletedPolicy));
    when(recoverableDeletionManager.getDeletedPolicy(policyNamespace, "policy1", 984273L))
        .thenReturn(deletedPolicy);

    Response listResponse =
        target(policyPath(metalake))
            .queryParam("include", "deleted")
            .queryParam("name", "policy1")
            .queryParam("id", "984273")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), listResponse.getStatus());
    DeletedEntityListResponse listBody = listResponse.readEntity(DeletedEntityListResponse.class);
    Assertions.assertArrayEquals(
        new DeletedEntityDTO[] {deletedPolicy}, listBody.getDeletedEntities());

    Response itemResponse =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", "984273")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), itemResponse.getStatus());
    Assertions.assertEquals('"' + etag + '"', itemResponse.getHeaderString("ETag"));
    DeletedEntityResponse itemBody = itemResponse.readEntity(DeletedEntityResponse.class);
    Assertions.assertEquals(deletedPolicy, itemBody.getDeletedEntity());

    PolicyEntity restored = policyEntity(984273L, "policy1");
    when(recoverableDeletionManager.restoreDeletedPolicy(policyNamespace, "policy1", 984273L, etag))
        .thenReturn(restored);
    when(policyManager.getPolicy(metalake, "policy1")).thenReturn(restored);

    Response firstRestore = patchRestorePolicy("984273", '"' + etag + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), firstRestore.getStatus());
    Assertions.assertEquals(
        "policy1", firstRestore.readEntity(PolicyResponse.class).getPolicy().name());

    Response replay = patchRestorePolicy("984273", '"' + etag + '"');
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), replay.getStatus());
    Assertions.assertEquals("policy1", replay.readEntity(PolicyResponse.class).getPolicy().name());
    verify(recoverableDeletionManager, times(2))
        .restoreDeletedPolicy(policyNamespace, "policy1", 984273L, etag);
    verify(policyManager, times(2)).getPolicy(metalake, "policy1");
    verifyNoMoreInteractions(policyManager);
  }

  @Test
  public void testDeletedPolicyQueryValidation() {
    Response invalidId =
        target(policyPath(metalake))
            .queryParam("include", "deleted")
            .queryParam("id", "not-a-number")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), invalidId.getStatus());

    Response missingItemId =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), missingItemId.getStatus());

    Response deletedDetails =
        target(policyPath(metalake))
            .queryParam("include", "deleted")
            .queryParam("details", true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), deletedDetails.getStatus());

    Response liveId =
        target(policyPath(metalake))
            .queryParam("id", "984273")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), liveId.getStatus());
    verifyNoInteractions(recoverableDeletionManager, policyManager);
  }

  @Test
  public void testRestoreDeletedPolicyValidatesPatchAndPreconditions() {
    Response missingInclude =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("id", "984273")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"policy-etag\"")
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(false), "application/merge-patch+json"));
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), missingInclude.getStatus());

    Response missingId = patchRestorePolicy(null, "\"policy-etag\"");
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), missingId.getStatus());

    Response missingIfMatch = patchRestorePolicy("984273", null);
    Assertions.assertEquals(428, missingIfMatch.getStatus());
    Assertions.assertEquals(
        ErrorConstants.PRECONDITION_REQUIRED_CODE,
        missingIfMatch.readEntity(ErrorResponse.class).getCode());

    Response weakIfMatch = patchRestorePolicy("984273", "W/\"policy-etag\"");
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), weakIfMatch.getStatus());

    Response multipleIfMatch = patchRestorePolicy("984273", "\"policy-etag\", \"other-etag\"");
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), multipleIfMatch.getStatus());

    Response invalidBody =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", "984273")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"policy-etag\"")
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(true), "application/merge-patch+json"));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), invalidBody.getStatus());

    Response unsupportedMediaType =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", "984273")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"policy-etag\"")
            .method("PATCH", Entity.entity("deleted=false", MediaType.TEXT_PLAIN_TYPE));
    Assertions.assertEquals(
        Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), unsupportedMediaType.getStatus());
    verifyNoInteractions(recoverableDeletionManager, policyManager);
  }

  @Test
  public void testPolicyPatchMediaTypesCannotCrossMutate() {
    PolicySetRequest setRequest = new PolicySetRequest(true);
    Response setResponse =
        target(policyPath(metalake))
            .path("policy1")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .method("PATCH", Entity.entity(setRequest, MediaType.APPLICATION_JSON_TYPE));
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), setResponse.getStatus());
    verify(policyManager).enablePolicy(metalake, "policy1");
    verifyNoInteractions(recoverableDeletionManager);

    Response restoreShapedJson =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", "984273")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"policy-etag\"")
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(false), MediaType.APPLICATION_JSON_TYPE));
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), restoreShapedJson.getStatus());
    verify(policyManager, times(1)).enablePolicy(metalake, "policy1");
    verifyNoInteractions(recoverableDeletionManager);

    Response selectorFreeRestoreShapedJson =
        target(policyPath(metalake))
            .path("policy1")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .method(
                "PATCH",
                Entity.entity(new EntityRestoreRequest(false), MediaType.APPLICATION_JSON_TYPE));
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), selectorFreeRestoreShapedJson.getStatus());
    verify(policyManager, times(1)).enablePolicy(metalake, "policy1");
    verifyNoInteractions(recoverableDeletionManager);

    Response setShapedMergePatch =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", "984273")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .header("If-Match", "\"policy-etag\"")
            .method(
                "PATCH",
                Entity.entity(new PolicySetRequest(false), "application/merge-patch+json"));
    Assertions.assertEquals(
        Response.Status.BAD_REQUEST.getStatusCode(), setShapedMergePatch.getStatus());
    verify(policyManager, times(1)).enablePolicy(metalake, "policy1");
    verifyNoMoreInteractions(policyManager);
    verifyNoInteractions(recoverableDeletionManager);
  }

  @Test
  public void testRestoreDeletedPolicyMapsRecoveryFailures() {
    doThrow(new TombstoneChangedException("changed"))
        .when(recoverableDeletionManager)
        .restoreDeletedPolicy(any(), eq("policy1"), eq(984273L), eq("stale"));
    doThrow(new TombstoneExpiredException("expired"))
        .when(recoverableDeletionManager)
        .restoreDeletedPolicy(any(), eq("policy1"), eq(984273L), eq("expired"));
    doThrow(new RecoveryConflictException(RecoveryConflictReason.NAME_OCCUPIED, "occupied"))
        .when(recoverableDeletionManager)
        .restoreDeletedPolicy(any(), eq("policy1"), eq(984273L), eq("conflict"));

    Response stale = patchRestorePolicy("984273", "\"stale\"");
    Assertions.assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), stale.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_CHANGED_CODE, stale.readEntity(ErrorResponse.class).getCode());

    Response expired = patchRestorePolicy("984273", "\"expired\"");
    Assertions.assertEquals(Response.Status.GONE.getStatusCode(), expired.getStatus());
    Assertions.assertEquals(
        ErrorConstants.TOMBSTONE_EXPIRED_CODE, expired.readEntity(ErrorResponse.class).getCode());

    Response conflict = patchRestorePolicy("984273", "\"conflict\"");
    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), conflict.getStatus());
    ErrorResponse conflictBody = conflict.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.RECOVERY_CONFLICT_CODE, conflictBody.getCode());
    Assertions.assertEquals(RecoveryConflictReason.NAME_OCCUPIED, conflictBody.getReason());
    verifyNoInteractions(policyManager);
  }

  @Test
  public void testCreatePolicy() {
    ImmutableMap<String, Object> contentFields = ImmutableMap.of("target_file_size_bytes", 1000);
    PolicyContent content =
        PolicyContents.custom(contentFields, ImmutableSet.of(MetadataObject.Type.TABLE), null);
    PolicyEntity policy1 =
        PolicyEntity.builder()
            .withId(1L)
            .withName("policy1")
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withEnabled(false)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();
    when(policyManager.createPolicy(
            metalake, "policy1", Policy.BuiltInType.CUSTOM, null, false, content))
        .thenReturn(policy1);

    PolicyCreateRequest request =
        new PolicyCreateRequest("policy1", "custom", null, false, toDTO(content));
    Response resp =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    PolicyResponse policyResp = resp.readEntity(PolicyResponse.class);
    Assertions.assertEquals(0, policyResp.getCode());

    Policy respPolicy = policyResp.getPolicy();
    Assertions.assertEquals(policy1.name(), respPolicy.name());
    Assertions.assertEquals(policy1.comment(), respPolicy.comment());
    Assertions.assertEquals("custom", respPolicy.policyType());
    Assertions.assertEquals(Optional.empty(), respPolicy.inherited());

    // Test throw PolicyAlreadyExistsException
    doThrow(new PolicyAlreadyExistsException("mock error"))
        .when(policyManager)
        .createPolicy(any(), any(), any(), any(), anyBoolean(), any());
    Response resp1 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), resp1.getStatus());

    ErrorResponse errorResp = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResp.getCode());
    Assertions.assertEquals(
        PolicyAlreadyExistsException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error"))
        .when(policyManager)
        .createPolicy(any(), any(), any(), any(), anyBoolean(), any());

    Response resp2 =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());

    ErrorResponse errorResp1 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp1.getType());
  }

  @Test
  public void testCreatePolicyWithIcebergCompactionType() {
    PolicyContent content =
        PolicyContents.icebergDataCompaction(
            1000L,
            1L,
            ImmutableMap.of("target-file-size-bytes", "1048576", "min-input-files", "1"));
    PolicyEntity policy1 =
        PolicyEntity.builder()
            .withId(1L)
            .withName("iceberg-compaction")
            .withPolicyType(Policy.BuiltInType.ICEBERG_COMPACTION)
            .withEnabled(true)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();
    when(policyManager.createPolicy(
            metalake,
            "iceberg-compaction",
            Policy.BuiltInType.ICEBERG_COMPACTION,
            null,
            true,
            content))
        .thenReturn(policy1);

    PolicyCreateRequest request =
        new PolicyCreateRequest(
            "iceberg-compaction", "system_iceberg_compaction", null, true, toDTO(content));
    Response resp =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    PolicyResponse policyResp = resp.readEntity(PolicyResponse.class);
    Assertions.assertEquals(0, policyResp.getCode());
    Assertions.assertEquals("system_iceberg_compaction", policyResp.getPolicy().policyType());
  }

  @Test
  public void testCreatePolicyWithIcebergCompactionTypeDefaults() {
    PolicyContent content = PolicyContents.icebergDataCompaction();
    PolicyEntity policy =
        PolicyEntity.builder()
            .withId(1L)
            .withName("iceberg-compaction-default")
            .withPolicyType(Policy.BuiltInType.ICEBERG_COMPACTION)
            .withEnabled(true)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();
    when(policyManager.createPolicy(
            metalake,
            "iceberg-compaction-default",
            Policy.BuiltInType.ICEBERG_COMPACTION,
            null,
            true,
            content))
        .thenReturn(policy);

    PolicyContentDTO.IcebergCompactionContentDTO minimalContent =
        PolicyContentDTO.IcebergCompactionContentDTO.builder().build();
    PolicyCreateRequest request =
        new PolicyCreateRequest(
            "iceberg-compaction-default", "system_iceberg_compaction", null, true, minimalContent);

    Response resp =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    PolicyResponse policyResp = resp.readEntity(PolicyResponse.class);
    Assertions.assertEquals(0, policyResp.getCode());
    Assertions.assertEquals("system_iceberg_compaction", policyResp.getPolicy().policyType());
  }

  @Test
  public void testCreatePolicyWithIcebergCompactionEnumTypeRejected() {
    PolicyContent content = PolicyContents.icebergDataCompaction();
    PolicyCreateRequest request =
        new PolicyCreateRequest(
            "iceberg-compaction", "ICEBERG_COMPACTION", null, true, toDTO(content));

    Response resp =
        target(policyPath(metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
  }

  @Test
  public void testGetPolicy() {
    ImmutableMap<String, Object> contentFields = ImmutableMap.of("target_file_size_bytes", 1000);
    PolicyContent content =
        PolicyContents.custom(contentFields, ImmutableSet.of(MetadataObject.Type.TABLE), null);
    PolicyEntity policy1 =
        PolicyEntity.builder()
            .withId(1L)
            .withName("policy1")
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withEnabled(false)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();
    when(policyManager.getPolicy(metalake, "policy1")).thenReturn(policy1);

    Response resp =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    PolicyResponse policyResp = resp.readEntity(PolicyResponse.class);
    Assertions.assertEquals(0, policyResp.getCode());

    Policy respPolicy = policyResp.getPolicy();
    Assertions.assertEquals(policy1.name(), respPolicy.name());
    Assertions.assertEquals(policy1.comment(), respPolicy.comment());
    Assertions.assertEquals(Optional.empty(), respPolicy.inherited());

    // Test throw NoSuchPolicyException
    doThrow(new NoSuchPolicyException("mock error"))
        .when(policyManager)
        .getPolicy(metalake, "policy1");

    Response resp2 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp2.getStatus());

    ErrorResponse errorResp = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchPolicyException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(policyManager).getPolicy(metalake, "policy1");

    Response resp3 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp3.getStatus());

    ErrorResponse errorResp1 = resp3.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp1.getType());
  }

  @Test
  public void testAlterPolicy() {
    ImmutableMap<String, Object> contentFields = ImmutableMap.of("target_file_size_bytes", 1000);
    PolicyContent content =
        PolicyContents.custom(contentFields, ImmutableSet.of(MetadataObject.Type.TABLE), null);
    PolicyEntity newPolicy =
        PolicyEntity.builder()
            .withId(1L)
            .withName("new_policy1")
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withComment("new policy1 comment")
            .withEnabled(false)
            .withContent(content)
            .withAuditInfo(testAuditInfo1)
            .build();

    PolicyChange[] changes =
        new PolicyChange[] {
          PolicyChange.rename("new_policy1"),
          PolicyChange.updateComment("new policy1 comment"),
          PolicyChange.updateContent("custom", content)
        };

    when(policyManager.alterPolicy(metalake, "policy1", changes)).thenReturn(newPolicy);

    PolicyUpdateRequest[] requests =
        new PolicyUpdateRequest[] {
          new PolicyUpdateRequest.RenamePolicyRequest("new_policy1"),
          new PolicyUpdateRequest.UpdatePolicyCommentRequest("new policy1 comment"),
          new PolicyUpdateRequest.UpdatePolicyContentRequest("custom", toDTO(content))
        };
    PolicyUpdatesRequest request = new PolicyUpdatesRequest(Lists.newArrayList(requests));
    Response resp =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    PolicyResponse policyResp = resp.readEntity(PolicyResponse.class);
    Assertions.assertEquals(0, policyResp.getCode());

    Policy respPolicy = policyResp.getPolicy();
    Assertions.assertEquals(newPolicy.name(), respPolicy.name());
    Assertions.assertEquals(newPolicy.comment(), respPolicy.comment());
    Assertions.assertEquals(Optional.empty(), respPolicy.inherited());

    // Test throw NoSuchPolicyException
    doThrow(new NoSuchPolicyException("mock error"))
        .when(policyManager)
        .alterPolicy(any(), any(), any());

    Response resp1 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp1.getStatus());

    ErrorResponse errorResp = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchPolicyException.class.getSimpleName(), errorResp.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error"))
        .when(policyManager)
        .alterPolicy(any(), any(), any());

    Response resp2 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());

    ErrorResponse errorResp1 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp1.getType());
  }

  @Test
  public void testSetPolicy() {
    PolicySetRequest req = new PolicySetRequest(true);
    doNothing().when(policyManager).enablePolicy(any(), any());

    Response resp =
        target(policyPath(metalake))
            .path("policy1")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .method("PATCH", Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    BaseResponse baseResponse = resp.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, baseResponse.getCode());

    req = new PolicySetRequest(false);
    doNothing().when(policyManager).disablePolicy(any(), any());

    Response resp1 =
        target(policyPath(metalake))
            .path("policy1")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .method("PATCH", Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());
    BaseResponse baseResponse1 = resp1.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, baseResponse1.getCode());
  }

  // Test to check exception on failure in set policy is dynamic based on enable/disable
  @Test
  public void testSetPolicyDisableFailure() {
    PolicySetRequest req = new PolicySetRequest(false);
    doThrow(new RuntimeException("mock disable exception"))
        .when(policyManager)
        .disablePolicy(any(), any());
    Response resp =
        target(policyPath(metalake))
            .path("policy1")
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .method("PATCH", Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());

    ErrorResponse errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp.getType());
  }

  @Test
  public void testDeletePolicy() {
    when(policyManager.deletePolicy(metalake, "policy1")).thenReturn(true);

    Response resp =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    DropResponse dropResp = resp.readEntity(DropResponse.class);
    Assertions.assertEquals(0, dropResp.getCode());
    Assertions.assertTrue(dropResp.dropped());

    when(policyManager.deletePolicy(metalake, "policy1")).thenReturn(false);
    Response resp1 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());

    DropResponse dropResp1 = resp1.readEntity(DropResponse.class);
    Assertions.assertEquals(0, dropResp1.getCode());
    Assertions.assertFalse(dropResp1.dropped());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error")).when(policyManager).deletePolicy(any(), any());

    Response resp2 =
        target(policyPath(metalake))
            .path("policy1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .delete();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());

    ErrorResponse errorResp1 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResp1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResp1.getType());
  }

  @Test
  public void testListMetadataObjectForPolicy() {
    MetadataObject[] objects =
        new MetadataObject[] {
          MetadataObjects.parse("object1", MetadataObject.Type.CATALOG),
          MetadataObjects.parse("object1.object2", MetadataObject.Type.SCHEMA),
          MetadataObjects.parse("object1.object2.object3", MetadataObject.Type.TABLE),
        };

    when(policyManager.listMetadataObjectsForPolicy(metalake, "policy1")).thenReturn(objects);

    Response response =
        target(policyPath(metalake))
            .path("policy1")
            .path("objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

    MetadataObjectListResponse objectListResponse =
        response.readEntity(MetadataObjectListResponse.class);
    Assertions.assertEquals(0, objectListResponse.getCode());

    MetadataObject[] respObjects = objectListResponse.getMetadataObjects();
    Assertions.assertEquals(objects.length, respObjects.length);

    for (int i = 0; i < objects.length; i++) {
      Assertions.assertEquals(objects[i].type(), respObjects[i].type());
      Assertions.assertEquals(objects[i].fullName(), respObjects[i].fullName());
    }

    // Test throw NoSuchPolicyException
    doThrow(new NoSuchPolicyException("mock error"))
        .when(policyManager)
        .listMetadataObjectsForPolicy(metalake, "policy1");

    Response response1 =
        target(policyPath(metalake))
            .path("policy1")
            .path("objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response1.getStatus());

    ErrorResponse errorResponse = response1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchPolicyException.class.getSimpleName(), errorResponse.getType());

    // Test throw RuntimeException
    doThrow(new RuntimeException("mock error"))
        .when(policyManager)
        .listMetadataObjectsForPolicy(any(), any());

    Response response2 =
        target(policyPath(metalake))
            .path("policy1")
            .path("objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response2.getStatus());

    ErrorResponse errorResponse1 = response2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse1.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse1.getType());
  }

  private Response patchRestorePolicy(String id, String ifMatch) {
    Invocation.Builder request =
        target(policyPath(metalake))
            .path("policy1")
            .queryParam("include", "deleted")
            .queryParam("id", id)
            .property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json");
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    return request.method(
        "PATCH", Entity.entity(new EntityRestoreRequest(false), "application/merge-patch+json"));
  }

  private PolicyEntity policyEntity(long id, String name) {
    PolicyContent content =
        PolicyContents.custom(
            ImmutableMap.of("target_file_size_bytes", 1000),
            ImmutableSet.of(MetadataObject.Type.TABLE),
            null);
    return PolicyEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(policyNamespace)
        .withPolicyType(Policy.BuiltInType.CUSTOM)
        .withEnabled(false)
        .withContent(content)
        .withAuditInfo(testAuditInfo1)
        .build();
  }

  private String policyPath(String metalake) {
    return "/metalakes/" + metalake + "/policies";
  }
}
