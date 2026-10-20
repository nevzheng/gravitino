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

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.EntityStoreFactory;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.secret.SecretManager;

/**
 * Binds the stores the server keeps its state in: the entity store, the KMS client registry and the
 * secret manager.
 *
 * <p>Dagger does not close them; {@code GravitinoEnv.shutdown()} does.
 */
@SuppressWarnings("CloseableProvides")
@Module
abstract class StorageModule {

  private StorageModule() {}

  /**
   * Creates the entity store named by the configuration and initializes it, which connects to its
   * backend.
   */
  @Provides
  @Singleton
  static EntityStore entityStore(Config config) {
    EntityStore entityStore = EntityStoreFactory.createEntityStore(config);
    entityStore.initialize(config);
    return entityStore;
  }

  @Provides
  @Singleton
  static KmsClientRegistry kmsClientRegistry(Config config) {
    return new KmsClientRegistry(config);
  }

  @Provides
  @Singleton
  static SecretManager secretManager(Config config) {
    return new SecretManager(config);
  }
}
