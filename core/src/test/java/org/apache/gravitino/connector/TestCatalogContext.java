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
package org.apache.gravitino.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.TestCatalog;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.secret.SecretManager;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Test;

/** Checks how a {@link CatalogContext} reaches catalog operations. */
public class TestCatalogContext {

  @Test
  public void testOfReturnsTheGivenServices() {
    EntityStore store = mock(EntityStore.class);
    SecretManager secrets = mock(SecretManager.class);
    MetricsSystem metrics = mock(MetricsSystem.class);
    IdGenerator ids = mock(IdGenerator.class);

    CatalogContext context = CatalogContext.of(store, secrets, metrics, ids);

    assertSame(store, context.entityStore());
    assertSame(secrets, context.secretManager());
    assertSame(metrics, context.metricsSystem());
    assertSame(ids, context.idGenerator());
    assertNull(CatalogContext.of(store, secrets, null, ids).metricsSystem());
  }

  @Test
  public void testCredentialBackfillComesFromTheContext() {
    EntityStore store = mock(EntityStore.class);
    SecretManager secrets = mock(SecretManager.class);
    IdGenerator ids = mock(IdGenerator.class);

    assertFalse(CatalogContext.of(store, secrets, null, ids).credentialBackfillToProperties());
    CatalogContext backfill = CatalogContext.of(store, secrets, null, ids, true);
    assertTrue(backfill.credentialBackfillToProperties());
    assertTrue(new TestCatalog().withCatalogContext(backfill).shouldBackfillCredential());
    assertFalse(
        new TestCatalog()
            .withCatalogContext(CatalogContext.of(store, secrets, null, ids, false))
            .shouldBackfillCredential());
  }

  @Test
  public void testDefaultInitializeCallsTheThreeArgumentMethod() throws Exception {
    // Operations written before CatalogContext implement only the three-argument method.
    List<String> calls = new ArrayList<>();
    CatalogOperations ops =
        new CatalogOperations() {
          @Override
          public void initialize(
              Map<String, String> config, CatalogInfo info, HasPropertyMetadata metadata) {
            calls.add("initialize " + config);
          }

          @Override
          public void close() {}
        };

    ops.initialize(
        ImmutableMap.of("k", "v"),
        null,
        null,
        CatalogContext.of(
            mock(EntityStore.class), mock(SecretManager.class), null, mock(IdGenerator.class)));

    assertEquals(List.of("initialize {k=v}"), calls);
  }

  @Test
  public void testOpsReceiveTheCatalogContext() {
    CatalogContext context =
        CatalogContext.of(
            mock(EntityStore.class), mock(SecretManager.class), null, mock(IdGenerator.class));
    TestCatalog catalog =
        new TestCatalog()
            .withCatalogConf(ImmutableMap.of())
            .withCatalogEntity(mock(CatalogEntity.class))
            .withCatalogContext(context);

    TestCatalogOperations ops = (TestCatalogOperations) catalog.ops();

    assertSame(context, ops.catalogContext());
  }

  @Test
  public void testOpsOfACatalogWithoutContextUseTheThreeArgumentMethod() {
    TestCatalog catalog =
        new TestCatalog()
            .withCatalogConf(ImmutableMap.of())
            .withCatalogEntity(mock(CatalogEntity.class));

    TestCatalogOperations ops = (TestCatalogOperations) catalog.ops();

    assertNull(ops.catalogContext());
  }

  @Test
  public void testACatalogWithoutContextDoesNotBackfillCredentials() {
    assertFalse(new TestCatalog().shouldBackfillCredential());
  }

  @Test
  public void testRequireCatalogContext() {
    CatalogContext context =
        CatalogContext.of(
            mock(EntityStore.class), mock(SecretManager.class), null, mock(IdGenerator.class));

    assertSame(context, new TestCatalog().withCatalogContext(context).requireCatalogContext());
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> new TestCatalog().requireCatalogContext());
    assertTrue(e.getMessage().contains("withCatalogContext"));
  }
}
