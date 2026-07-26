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
package org.apache.gravitino;

import javax.annotation.Nullable;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.storage.IdGenerator;

/**
 * Compatibility accessors for public constructors that predate explicit dependency injection.
 *
 * <p>New production wiring must pass exact dependencies instead. This class isolates the remaining
 * binary-compatibility bridge so it can be removed together with the legacy constructors in a
 * future compatibility-breaking release.
 *
 * @deprecated only legacy public constructors may use this bridge
 */
@Deprecated(forRemoval = true)
public final class LegacyRuntimeDependencies {

  private LegacyRuntimeDependencies() {}

  /** Returns the current owner dispatcher, or {@code null} when authorization is disabled. */
  public static @Nullable OwnerDispatcher ownerDispatcher() {
    return environment().ownerDispatcher();
  }

  /** Returns the current metadata lock manager. */
  public static LockManager lockManager() {
    return environment().lockManager();
  }

  /** Returns the current schema dispatcher. */
  public static SchemaDispatcher schemaDispatcher() {
    return environment().schemaDispatcher();
  }

  /** Returns the current persistent identifier generator. */
  public static IdGenerator idGenerator() {
    return environment().idGenerator();
  }

  /** Returns the current event bus. */
  public static EventBus eventBus() {
    return environment().eventBus();
  }

  private static GravitinoEnv environment() {
    return GravitinoEnv.getInstance();
  }
}
