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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.meta.UserEntity;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link DispatcherDecorator} contract: a decorator that delegates to the chain it is
 * given keeps the hook layer under it, so authorization stays in step with the changes it passes
 * on.
 */
public class TestDelegatingDecorator {

  @Test
  public void testTheHookLayerRunsUnderADelegatingDecorator() throws Exception {
    FakeLeaves leaves = new FakeLeaves();
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    leaves.gravitinoAuthorizer = authorizer;
    UserEntity user = mock(UserEntity.class);
    when(user.name()).thenReturn("user");
    when(user.roles()).thenReturn(Collections.emptyList());
    when(leaves.entityStore.update(any(), eq(UserEntity.class), eq(Entity.EntityType.USER), any()))
        .thenReturn(user);
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.ENABLE_AUTHORIZATION, true);
    DecoratedComponent component =
        DaggerTestDelegatingDecorator_DecoratedComponent.factory().create(config, leaves);

    AccessControlDispatcher chain = component.accessControlDispatcher();
    chain.revokeRolesFromUser("metalake", Collections.emptyList(), "user");

    Recorder recorder = (Recorder) Proxy.getInvocationHandler(chain);
    assertEquals(List.of("revokeRolesFromUser"), recorder.calls);
    // The hook layer under the decorator refreshes the authorizer's view of the user's roles.
    verify(authorizer).handleUserRoleRelChange("metalake", "user");
  }

  /** The production modules, the fake leaves and one delegating access-control decorator. */
  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        LockModule.class,
        CatalogModule.class,
        SchemaTableModule.class,
        FilesetTopicModelModule.class,
        GovernanceModule.class,
        AuthorizationModule.class,
        JobModule.class,
        AuxiliaryModule.class,
        LifecycleModule.class,
        FakeLeavesModule.class,
        DelegatingDecoratorModule.class
      })
  interface DecoratedComponent extends GravitinoComponent {

    /** Creates {@link DecoratedComponent} instances. */
    @Component.Factory
    interface Factory {
      DecoratedComponent create(@BindsInstance Config config, @BindsInstance FakeLeaves leaves);
    }
  }

  /** Contributes a decorator that records each call and passes it on to the chain it wraps. */
  @Module
  abstract static class DelegatingDecoratorModule {

    private DelegatingDecoratorModule() {}

    @Provides
    @IntoSet
    @Public
    static DispatcherDecorator<AccessControlDispatcher> recording() {
      return DispatcherDecorator.of(
          100,
          dispatcher ->
              (AccessControlDispatcher)
                  Proxy.newProxyInstance(
                      AccessControlDispatcher.class.getClassLoader(),
                      new Class<?>[] {AccessControlDispatcher.class},
                      new Recorder(dispatcher)));
    }
  }

  /** Records the name of each call and passes the call on to its delegate. */
  static final class Recorder implements InvocationHandler {
    final List<String> calls = new ArrayList<>();
    private final Object delegate;

    Recorder(Object delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      calls.add(method.getName());
      try {
        return method.invoke(delegate, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
