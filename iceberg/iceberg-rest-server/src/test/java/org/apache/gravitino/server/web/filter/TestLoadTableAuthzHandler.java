/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.server.web.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.QueryParam;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.IcebergAuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests deleted-table authorization behavior in {@link LoadTableAuthzHandler}. */
public class TestLoadTableAuthzHandler {

  private static final String METALAKE = "test_metalake";
  private static final String CATALOG = "test_catalog";
  private static final String SCHEMA = "test_schema";

  @BeforeAll
  public static void initAuthorizer() {
    GravitinoAuthorizerProvider.getInstance().initialize(new ServerConfig());
  }

  @Test
  public void testDeletedReadDoesNotConsultLiveIcebergRegistration() throws Exception {
    IcebergCatalogWrapperManager wrapperManager = Mockito.mock(IcebergCatalogWrapperManager.class);
    CatalogWrapperForREST catalogWrapper = Mockito.mock(CatalogWrapperForREST.class);
    when(wrapperManager.getCatalogWrapper(CATALOG)).thenReturn(catalogWrapper);
    resetContext(wrapperManager);

    Method method =
        TestOperations.class.getMethod(
            "loadTable", String.class, String.class, String.class, boolean.class);
    LoadTableAuthzHandler handler =
        new LoadTableAuthzHandler(
            method.getAnnotation(AuthorizationExpression.class),
            method.getParameters(),
            new Object[] {CATALOG + "/", SCHEMA, "orders", true});

    Map<Entity.EntityType, NameIdentifier> identifiers = new HashMap<>();
    identifiers.put(Entity.EntityType.METALAKE, NameIdentifierUtil.ofMetalake(METALAKE));
    identifiers.put(Entity.EntityType.CATALOG, NameIdentifierUtil.ofCatalog(METALAKE, CATALOG));
    identifiers.put(
        Entity.EntityType.SCHEMA, NameIdentifierUtil.ofSchema(METALAKE, CATALOG, SCHEMA));

    assertDoesNotThrow(() -> handler.process(identifiers));
    verify(catalogWrapper, never()).viewExists(any(TableIdentifier.class));
    verify(catalogWrapper, never()).tableExists(any(TableIdentifier.class));
  }

  private static void resetContext(IcebergCatalogWrapperManager wrapperManager) {
    IcebergConfigProvider configProvider = Mockito.mock(IcebergConfigProvider.class);
    when(configProvider.getMetalakeName()).thenReturn(METALAKE);
    when(configProvider.getDefaultCatalogName()).thenReturn(CATALOG);
    IcebergRESTServerContext.create(configProvider, false, false, true, wrapperManager);
  }

  /** Test operation signature used to supply resource annotations. */
  @SuppressWarnings("unused")
  public static class TestOperations {
    @AuthorizationExpression(
        expression = AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
        allowCheckExistence =
            AuthorizationExpressionConstants
                .ICEBERG_TABLE_EXISTS_SECONDARY_AUTHORIZATION_EXPRESSION,
        accessMetadataType = MetadataObject.Type.TABLE)
    public void loadTable(
        @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String prefix,
        @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String namespace,
        @IcebergAuthorizationMetadata(type = IcebergAuthorizationMetadata.RequestType.LOAD_TABLE)
            @AuthorizationMetadata(type = Entity.EntityType.TABLE)
            String table,
        @QueryParam("deleted") boolean deleted) {}
  }
}
