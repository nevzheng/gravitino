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
package org.apache.gravitino.di;

import static org.apache.gravitino.di.DispatcherChains.assertChain;
import static org.apache.gravitino.di.DispatcherChains.field;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.catalog.FunctionNormalizeDispatcher;
import org.apache.gravitino.catalog.FunctionOperationDispatcher;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.catalog.SemanticModelNormalizeDispatcher;
import org.apache.gravitino.catalog.SemanticModelOperationDispatcher;
import org.apache.gravitino.catalog.TopicNormalizeDispatcher;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.hook.FilesetHookDispatcher;
import org.apache.gravitino.hook.FunctionHookDispatcher;
import org.apache.gravitino.hook.ModelHookDispatcher;
import org.apache.gravitino.hook.SemanticModelHookDispatcher;
import org.apache.gravitino.hook.TopicHookDispatcher;
import org.apache.gravitino.listener.FilesetEventDispatcher;
import org.apache.gravitino.listener.FunctionEventDispatcher;
import org.apache.gravitino.listener.ModelEventDispatcher;
import org.apache.gravitino.listener.SemanticModelEventDispatcher;
import org.apache.gravitino.listener.TopicEventDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestFilesetTopicModelModule {

  private ModuleTestComponent component;

  @BeforeEach
  public void setUp() {
    component = ModuleTestComponent.create(TestGravitinoComponent.testConfig(), new FakeLeaves());
  }

  @AfterEach
  public void tearDown() {
    component.catalogManager().close();
  }

  @Test
  public void testFilesetChainsShareOneOperationDispatcher() throws Exception {
    Object internal =
        assertChain(
            component.internalFilesetDispatcher(),
            FilesetNormalizeDispatcher.class,
            FilesetOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.filesetDispatcher(),
            FilesetEventDispatcher.class,
            FilesetNormalizeDispatcher.class,
            FilesetHookDispatcher.class,
            FilesetOperationDispatcher.class);
    assertSame(internal, publicOperations);
    // The schema operation dispatcher drops filesets through the internal fileset chain.
    assertSame(
        component.internalFilesetDispatcher(),
        field(component.schemaOperationDispatcher(), "filesetDispatcher"));
  }

  @Test
  public void testTopicChainsShareOneOperationDispatcher() throws Exception {
    Object internal =
        assertChain(
            component.internalTopicDispatcher(),
            TopicNormalizeDispatcher.class,
            TopicOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.topicDispatcher(),
            TopicEventDispatcher.class,
            TopicNormalizeDispatcher.class,
            TopicHookDispatcher.class,
            TopicOperationDispatcher.class);
    assertSame(internal, publicOperations);
  }

  @Test
  public void testModelChainsShareOneOperationDispatcher() throws Exception {
    Object internal =
        assertChain(
            component.internalModelDispatcher(),
            ModelNormalizeDispatcher.class,
            ModelOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.modelDispatcher(),
            ModelEventDispatcher.class,
            ModelNormalizeDispatcher.class,
            ModelHookDispatcher.class,
            ModelOperationDispatcher.class);
    assertSame(internal, publicOperations);
  }

  @Test
  public void testFunctionChainsShareOneOperationDispatcher() throws Exception {
    Object internal =
        assertChain(
            component.internalFunctionDispatcher(),
            FunctionNormalizeDispatcher.class,
            FunctionOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.functionDispatcher(),
            FunctionEventDispatcher.class,
            FunctionNormalizeDispatcher.class,
            FunctionHookDispatcher.class,
            FunctionOperationDispatcher.class);
    assertSame(internal, publicOperations);
    assertSame(component.schemaOperationDispatcher(), field(internal, "schemaOps"));
    Object hook = field(field(component.functionDispatcher(), "dispatcher"), "dispatcher");
    assertNull(
        ((AuthorizationServices) field(hook, "authorizationServices")).internalOwnerDispatcher());
  }

  @Test
  public void testSemanticModelChainsEachHaveTheirOwnOperationDispatcher() throws Exception {
    Object internal =
        assertChain(
            component.internalSemanticModelDispatcher(),
            SemanticModelNormalizeDispatcher.class,
            SemanticModelOperationDispatcher.class);
    Object publicOperations =
        assertChain(
            component.semanticModelDispatcher(),
            SemanticModelEventDispatcher.class,
            SemanticModelNormalizeDispatcher.class,
            SemanticModelHookDispatcher.class,
            SemanticModelOperationDispatcher.class);
    assertNotSame(internal, publicOperations);
    assertSame(component.schemaOperationDispatcher(), field(internal, "schemaDispatcher"));
    assertSame(component.schemaOperationDispatcher(), field(publicOperations, "schemaDispatcher"));
  }

  @Test
  public void testInternalChainsDoNotNeedTheEventBus() {
    FakeLeaves noEventBus = new FakeLeaves();
    noEventBus.eventBus = null;
    ModuleTestComponent metadataOnly =
        ModuleTestComponent.create(TestGravitinoComponent.testConfig(), noEventBus);
    try {
      metadataOnly.internalFilesetDispatcher();
      metadataOnly.internalTopicDispatcher();
      metadataOnly.internalModelDispatcher();
      metadataOnly.internalFunctionDispatcher();
      metadataOnly.internalSemanticModelDispatcher();
    } finally {
      metadataOnly.catalogManager().close();
    }
  }
}
