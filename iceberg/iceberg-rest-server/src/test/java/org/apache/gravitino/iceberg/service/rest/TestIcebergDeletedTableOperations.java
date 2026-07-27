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

package org.apache.gravitino.iceberg.service.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.IcebergRESTUtils;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionAction;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionETags;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionException;
import org.apache.gravitino.iceberg.service.deletion.IcebergTableDeletionLifecycle;
import org.apache.gravitino.storage.relational.po.EntityDeletionPO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTUtil;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the ordinary table resources' deleted-only HTTP contract. */
public class TestIcebergDeletedTableOperations extends IcebergTestBase {

  private static final Namespace NAMESPACE = Namespace.of("sales");
  private static final String CATALOG = IcebergRestTestUtil.PREFIX;

  private IcebergTableDeletionLifecycle lifecycle;
  private EntityDeletionPO first;
  private EntityDeletionPO hidden;
  private EntityDeletionPO second;
  private EntityDeletionPO third;

  @Override
  protected Application configure() {
    lifecycle = mock(IcebergTableDeletionLifecycle.class);
    HttpServletRequest httpRequest = IcebergRestTestUtil.createMockHttpRequest();
    ResourceConfig config =
        IcebergRestTestUtil.getIcebergResourceConfig(MockIcebergDeletedTableOperations.class);
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(lifecycle).to(IcebergTableDeletionLifecycle.class).ranked(3);
            bind(httpRequest).to(HttpServletRequest.class);
          }
        });
    return config;
  }

  @BeforeEach
  void setUpDeletedActions() {
    first = deletion("D1", 41L, "customers", 1L);
    hidden = deletion("D-hidden", 99L, "hidden", 1L);
    second = deletion("D2", 42L, "orders", 2L);
    third = deletion("D3", 43L, "returns", 3L);
    when(lifecycle.toAction(eq(first), anyLong())).thenReturn(action(first));
    when(lifecycle.toAction(eq(hidden), anyLong())).thenReturn(action(hidden));
    when(lifecycle.toAction(eq(second), anyLong())).thenReturn(action(second));
    when(lifecycle.toAction(eq(third), anyLong())).thenReturn(action(third));
  }

  @Test
  void testDeletedOnlyListUsesTablePaginationAndJoinedRepresentation() throws Exception {
    when(lifecycle.listDeleted(CATALOG, NAMESPACE))
        .thenReturn(List.of(first, hidden, second, third));

    String path = tableCollectionPath();
    Response firstPage =
        getIcebergClientBuilder(
                path, Optional.of(ImmutableMap.of("deleted", "true", "pageSize", "2")))
            .get();

    assertEquals(200, firstPage.getStatus());
    assertEquals("private, no-store", firstPage.getHeaderString(HttpHeaders.CACHE_CONTROL));
    JsonNode firstJson =
        IcebergObjectMapper.getInstance().readTree(firstPage.readEntity(String.class));
    assertEquals(2, firstJson.get("tables").size());
    assertEquals("41", firstJson.at("/tables/0/table/id").asText());
    assertEquals("customers", firstJson.at("/tables/0/table/name").asText());
    assertEquals("D1", firstJson.at("/tables/0/deletion/deletionId").asText());
    assertTrue(firstJson.at("/tables/0/etag").asText().startsWith("\"iceberg-deletion-D1-r1-"));
    String pageToken = firstJson.get("next-page-token").asText();
    assertFalse(pageToken.isEmpty());

    Response secondPage =
        getIcebergClientBuilder(
                path,
                Optional.of(
                    ImmutableMap.of("deleted", "true", "pageSize", "2", "pageToken", pageToken)))
            .get();
    JsonNode secondJson =
        IcebergObjectMapper.getInstance().readTree(secondPage.readEntity(String.class));
    assertEquals(200, secondPage.getStatus());
    assertEquals(1, secondJson.get("tables").size());
    assertEquals("returns", secondJson.at("/tables/0/table/name").asText());
    assertFalse(secondJson.has("next-page-token"));
    assertFalse(firstJson.toString().contains("hidden"));
    assertFalse(secondJson.toString().contains("hidden"));
  }

  @Test
  void testDefaultListUsesLivePathInsteadOfDeletionLifecycle() {
    Response response = getIcebergClientBuilder(tableCollectionPath(), Optional.empty()).get();

    assertEquals(404, response.getStatus());
    verify(lifecycle, never()).listDeleted(CATALOG, NAMESPACE);
  }

  @Test
  void testDeletedItemReturnsMatchingStrongEtagAndSupportsRevalidation() throws Exception {
    TableIdentifier identifier = TableIdentifier.of(NAMESPACE, "orders");
    when(lifecycle.getDeleted(CATALOG, identifier)).thenReturn(second);
    String expected =
        '"' + IcebergDeletionETags.strongTag(second, System.currentTimeMillis()) + '"';

    Response loaded =
        getIcebergClientBuilder(
                tablePath("orders"), Optional.of(ImmutableMap.of("deleted", "true")))
            .get();
    assertEquals(200, loaded.getStatus());
    assertEquals(expected, loaded.getHeaderString(HttpHeaders.ETAG));
    assertEquals("private, no-store", loaded.getHeaderString(HttpHeaders.CACHE_CONTROL));
    JsonNode body = IcebergObjectMapper.getInstance().readTree(loaded.readEntity(String.class));
    assertEquals(expected, body.get("etag").asText());
    assertEquals("42", body.at("/table/id").asText());
    assertEquals("D2", body.at("/deletion/deletionId").asText());
    assertNotNull(body.get("deletion").get("retentionExpiresAt"));

    Response unchanged =
        getIcebergClientBuilder(
                tablePath("orders"), Optional.of(ImmutableMap.of("deleted", "true")))
            .header(HttpHeaders.IF_NONE_MATCH, expected)
            .get();
    assertEquals(304, unchanged.getStatus());
    assertEquals(expected, unchanged.getHeaderString(HttpHeaders.ETAG));
    assertFalse(unchanged.hasEntity());
  }

  @Test
  void testDeletedItemPropagatesLifecycleNotFound() {
    TableIdentifier identifier = TableIdentifier.of(NAMESPACE, "missing");
    when(lifecycle.getDeleted(CATALOG, identifier))
        .thenThrow(
            new IcebergDeletionException(
                IcebergDeletionException.Outcome.NOT_FOUND, "Deleted table does not exist"));

    Response response =
        getIcebergClientBuilder(
                tablePath("missing"), Optional.of(ImmutableMap.of("deleted", "true")))
            .get();
    assertEquals(404, response.getStatus());
  }

  @Test
  void testUnauthorizedDeletedItemIsConcealedAsNotFound() {
    TableIdentifier identifier = TableIdentifier.of(NAMESPACE, "hidden");
    when(lifecycle.getDeleted(CATALOG, identifier)).thenReturn(hidden);

    Response response =
        getIcebergClientBuilder(
                tablePath("hidden"), Optional.of(ImmutableMap.of("deleted", "true")))
            .get();

    assertEquals(404, response.getStatus());
  }

  private static EntityDeletionPO deletion(
      String deletionId, long entityId, String name, long revision) {
    return EntityDeletionPO.builder()
        .deletionId(deletionId)
        .entityType("TABLE")
        .entityId(entityId)
        .entityVersion(7L)
        .namespaceSnapshot(NAMESPACE.toString())
        .entityNameSnapshot(name)
        .state("DELETED")
        .revision(revision)
        .deletedAt(100L)
        .retentionExpiresAt(Long.MAX_VALUE)
        .deletedBy("alice")
        .build();
  }

  private static IcebergDeletionAction action(EntityDeletionPO deletion) {
    return IcebergDeletionAction.builder()
        .deletionId(deletion.getDeletionId())
        .entityId(String.valueOf(deletion.getEntityId()))
        .state(deletion.getState())
        .revision(deletion.getRevision())
        .deletedAt(deletion.getDeletedAt())
        .retentionExpiresAt(deletion.getRetentionExpiresAt())
        .deletedBy(deletion.getDeletedBy())
        .recoverable(true)
        .build();
  }

  private static String tableCollectionPath() {
    return "/v1/"
        + CATALOG
        + "/namespaces/"
        + RESTUtil.encodeNamespace(NAMESPACE, IcebergRESTUtils.NAMESPACE_SEPARATOR_URLENCODED_UTF_8)
        + "/tables";
  }

  private static String tablePath(String table) {
    return tableCollectionPath() + "/" + RESTUtil.encodeString(table);
  }
}
