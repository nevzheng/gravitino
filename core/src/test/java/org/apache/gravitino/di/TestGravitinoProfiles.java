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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.job.BuiltInJobTemplateEventListener;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Checks the order in which each {@link GravitinoProfiles profile} requests its objects. */
public class TestGravitinoProfiles {

  @AfterEach
  public void tearDown() {
    HierarchicalSchemaUtil.seedSchemaSeparator(null);
  }

  @Test
  public void testBaseRequestsOnlyTheBaseServices() {
    GravitinoComponent component = component(false);

    GravitinoProfiles.base(component);

    InOrder order = inOrder(component);
    order.verify(component).kmsClientRegistry();
    order.verify(component).secretManager();
    order.verify(component).metricsSystem();
    order.verify(component).eventListenerManager();
    order.verify(component).eventBus();
    verify(component, never()).entityStore();
    verify(component, never()).catalogAccess();
    verify(component, never()).auxiliaryServiceManager();
  }

  @Test
  public void testMetadataBuildsTheStoreFirstAndNoServerServices() {
    GravitinoComponent component = component(false);

    GravitinoProfiles.metadata(component);

    InOrder order = inOrder(component);
    order.verify(component).metricsSystem();
    order.verify(component).entityStore();
    order.verify(component).catalogAccess();
    order.verify(component).internalMetalakeDispatcher();
    order.verify(component).internalFilesetDispatcher();
    order.verify(component).internalSchemaDispatcher();
    order.verify(component).internalPolicyDispatcher();
    verify(component, never()).eventListenerManager();
    verify(component, never()).eventBus();
    verify(component, never()).metalakeDispatcher();
    verify(component, never()).auxiliaryServiceManager();
    verify(component, never()).internalJobOperationDispatcher();
    // Authorization is disabled.
    verify(component, never()).internalAccessControlDispatcher();
    verify(component, never()).internalOwnerDispatcher();
  }

  @Test
  public void testFullBuildsTheAuxiliaryServicesAfterTheHostServicesAndThenTheJobs() {
    GravitinoComponent component = component(true);
    EventListenerManager listeners = mock(EventListenerManager.class);
    BuiltInJobTemplateEventListener listener = mock(BuiltInJobTemplateEventListener.class);
    when(component.eventListenerManager()).thenReturn(listeners);
    when(component.builtInJobTemplateEventListener()).thenReturn(listener);

    GravitinoProfiles.full(component);

    InOrder order = inOrder(component);
    order.verify(component).eventBus();
    order.verify(component).entityStore();
    order.verify(component).internalAccessControlDispatcher();
    order.verify(component).futureGrantManager();
    order.verify(component).metalakeDispatcher();
    order.verify(component).accessControlDispatcher();
    order.verify(component).ownerDispatcher();
    order.verify(component).policyDispatcher();
    order.verify(component).auxiliaryServiceManager();
    order.verify(component).internalJobOperationDispatcher();
    order.verify(component).jobOperationDispatcher();
    verify(listeners).addEventListener(eq("builtin-job-template"), eq(listener));
  }

  @Test
  public void testFullSkipsTheAccessControlChainsWhileAuthorizationIsDisabled() {
    GravitinoComponent component = component(false);
    EventListenerManager listeners = component.eventListenerManager();

    GravitinoProfiles.full(component);

    verify(component, never()).internalAccessControlDispatcher();
    verify(component, never()).accessControlDispatcher();
    verify(component, never()).ownerDispatcher();
    verify(component, never()).bulkManager();
    verify(listeners).addEventListener(eq("builtin-job-template"), any());
  }

  @Test
  public void testEveryProfileSeedsTheStaticSettings() {
    GravitinoComponent component = component(false);
    component.config().set(Configs.SCHEMA_SEPARATOR, "|");

    GravitinoProfiles.base(component);

    assertEquals("|", HierarchicalSchemaUtil.schemaSeparator());
  }

  private static GravitinoComponent component(boolean authorizationEnabled) {
    Config config = new Config(false) {};
    config.set(Configs.ENABLE_AUTHORIZATION, authorizationEnabled);
    GravitinoComponent component = mock(GravitinoComponent.class, RETURNS_MOCKS);
    when(component.config()).thenReturn(config);
    when(component.eventListenerManager()).thenReturn(mock(EventListenerManager.class));
    return component;
  }
}
