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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dagger.BindsInstance;
import dagger.Component;
import java.io.IOException;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.EntityStoreFactory;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.secret.SecretManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class TestStorageModule {

  @Test
  public void testEntityStoreIsCreatedAndInitializedOnceOnFirstRequest() {
    Config config = new Config(false) {};
    EntityStore entityStore = mock(EntityStore.class);
    try (MockedStatic<EntityStoreFactory> factory = mockStatic(EntityStoreFactory.class)) {
      factory.when(() -> EntityStoreFactory.createEntityStore(config)).thenReturn(entityStore);
      StorageComponent component =
          DaggerTestStorageModule_StorageComponent.factory().create(config);
      verify(entityStore, never()).initialize(config);

      assertSame(entityStore, component.entityStore());
      assertSame(entityStore, component.entityStore());

      factory.verify(() -> EntityStoreFactory.createEntityStore(config));
      verify(entityStore).initialize(config);
    }
  }

  @Test
  public void testEntityStoreIsClosedWhenItsInitializationFails() throws Exception {
    Config config = new Config(false) {};
    EntityStore entityStore = mock(EntityStore.class);
    IllegalStateException failure = new IllegalStateException("backend unreachable");
    IOException closeFailure = new IOException("close failed");
    doThrow(failure).when(entityStore).initialize(config);
    doThrow(closeFailure).when(entityStore).close();
    try (MockedStatic<EntityStoreFactory> factory = mockStatic(EntityStoreFactory.class)) {
      factory.when(() -> EntityStoreFactory.createEntityStore(config)).thenReturn(entityStore);
      StorageComponent component =
          DaggerTestStorageModule_StorageComponent.factory().create(config);

      assertSame(failure, assertThrows(IllegalStateException.class, component::entityStore));

      // The store started part of its backend before it failed, so it is closed here.
      verify(entityStore).close();
      assertArrayEquals(new Throwable[] {closeFailure}, failure.getSuppressed());
    }
  }

  @Test
  public void testSecretsAreSingletons() {
    StorageComponent component =
        DaggerTestStorageModule_StorageComponent.factory().create(new Config(false) {});

    assertNotNull(component.secretManager().getRegistry());
    assertSame(component.secretManager(), component.secretManager());
    assertSame(component.kmsClientRegistry(), component.kmsClientRegistry());
  }

  /** A graph of the storage module alone. */
  @Singleton
  @Component(modules = StorageModule.class)
  interface StorageComponent {
    EntityStore entityStore();

    KmsClientRegistry kmsClientRegistry();

    SecretManager secretManager();

    /** Creates storage components. */
    @Component.Factory
    interface Factory {
      StorageComponent create(@BindsInstance Config config);
    }
  }
}
