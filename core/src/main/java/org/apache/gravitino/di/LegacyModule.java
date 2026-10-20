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
import java.util.function.Supplier;
import org.apache.gravitino.authorization.GravitinoAuthorizer;

/**
 * Binds the objects the graph does not build, read through {@link LegacyBridge}.
 *
 * <p>The bridge owns these objects and may replace them after the graph is built, so each is bound
 * as a {@link Supplier} that reads the bridge on every call, never as the bare type: a consumer
 * that took the bare type would keep whatever the bridge held when the consumer was built. This
 * module shrinks as areas move into their own modules, and is deleted when the migration is
 * complete.
 */
// The server closes the authorizer; the graph only hands it out.
@SuppressWarnings("CloseableProvides")
@Module
abstract class LegacyModule {

  private LegacyModule() {}

  /**
   * Binds the authorizer the server installed. The server installs it after the component is built
   * and takes it back while it shuts down, so call {@link Supplier#get()} on each check rather than
   * keeping its result.
   */
  @Provides
  static Supplier<GravitinoAuthorizer> gravitinoAuthorizer(LegacyBridge legacy) {
    return legacy::gravitinoAuthorizer;
  }
}
