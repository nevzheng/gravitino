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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationServices;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.messaging.Topic;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Test;

public class TestHookAuthorizationServices {

  @Test
  public void testHooksKeepTheGivenServices() throws Exception {
    AuthorizationServices services = mock(AuthorizationServices.class);
    List<Object> hooks =
        List.of(
            new AccessControlHookDispatcher(mock(AccessControlDispatcher.class), services),
            new CatalogHookDispatcher(mock(CatalogDispatcher.class), services),
            new FilesetHookDispatcher(mock(FilesetDispatcher.class), services),
            new FunctionHookDispatcher(mock(FunctionDispatcher.class), services),
            new JobHookDispatcher(mock(JobOperationDispatcher.class), services),
            new MetalakeHookDispatcher(mock(MetalakeDispatcher.class), services),
            new ModelHookDispatcher(mock(ModelDispatcher.class), services),
            new PolicyHookDispatcher(mock(PolicyDispatcher.class), services),
            new SchemaHookDispatcher(
                mock(SchemaDispatcher.class), mock(LockManager.class), services),
            new SemanticModelHookDispatcher(mock(SemanticModelDispatcher.class), services),
            new TableHookDispatcher(mock(TableDispatcher.class), services),
            new TagHookDispatcher(mock(TagDispatcher.class), services),
            new TopicHookDispatcher(mock(TopicDispatcher.class), services),
            new ViewHookDispatcher(mock(ViewDispatcher.class), services));
    for (Object hook : hooks) {
      assertSame(services, servicesOf(hook), hook.getClass().getName());
    }
  }

  @Test
  public void testCreateTopicSetsTheOwnerThroughTheServices() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog", "schema", "topic");
    TopicDispatcher dispatcher = mock(TopicDispatcher.class);
    Topic topic = mock(Topic.class);
    when(dispatcher.createTopic(eq(ident), any(), any(), any())).thenReturn(topic);
    OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);
    AuthorizationServices services = mock(AuthorizationServices.class);
    when(services.internalOwnerDispatcher()).thenReturn(ownerDispatcher);

    assertSame(
        topic,
        new TopicHookDispatcher(dispatcher, services)
            .createTopic(ident, "comment", null, ImmutableMap.of()));

    verify(ownerDispatcher)
        .setOwner(eq("metalake"), any(MetadataObject.class), any(), eq(Owner.Type.USER));
  }

  @Test
  public void testFunctionHookNotifiesTheServicesAuthorizer() {
    NameIdentifier ident = NameIdentifier.of("metalake", "catalog", "schema", "function");
    FunctionDispatcher dispatcher = mock(FunctionDispatcher.class);
    when(dispatcher.dropFunction(ident)).thenReturn(true);
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    AuthorizationServices services = mock(AuthorizationServices.class);
    when(services.gravitinoAuthorizer()).thenReturn(authorizer);

    new FunctionHookDispatcher(dispatcher, services).dropFunction(ident);

    verify(authorizer).handleEntityNameIdMappingChange(eq("metalake"), eq(ident), any());
  }

  private static Object servicesOf(Object hook) throws IllegalAccessException {
    return FieldUtils.readField(hook, "authorizationServices", true);
  }
}
