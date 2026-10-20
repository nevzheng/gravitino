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
package org.apache.gravitino.authorization;

import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;

/**
 * An {@link AuthorizerContext} that reads each service from the getters of a {@link GravitinoEnv}
 * when it is asked for it.
 *
 * <p>Each method returns what the {@code GravitinoEnv} getter of the same name returns. It is
 * deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentAuthorizerContext implements AuthorizerContext {

  private final Supplier<GravitinoEnv> env;

  private EnvironmentAuthorizerContext(Supplier<GravitinoEnv> env) {
    this.env = env;
  }

  /**
   * Returns a context that reads the getters of an environment.
   *
   * @param env the environment
   * @return the context
   */
  public static EnvironmentAuthorizerContext of(GravitinoEnv env) {
    Preconditions.checkNotNull(env, "env");
    return new EnvironmentAuthorizerContext(() -> env);
  }

  @Override
  public Config config() {
    return env.get().config();
  }

  @Override
  public EntityStore entityStore() {
    return env.get().entityStore();
  }

  @Override
  @Nullable
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return env.get().internalAccessControlDispatcher();
  }
}
