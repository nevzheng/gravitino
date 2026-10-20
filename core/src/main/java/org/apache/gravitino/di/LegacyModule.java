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
import org.apache.gravitino.authorization.GravitinoAuthorizer;

/**
 * Binds the objects the graph does not build, read through {@link LegacyBridge}.
 *
 * <p>The bindings are unscoped on purpose: the bridge owns these objects, so every request reads
 * the bridge again instead of caching a copy in the graph. This module shrinks as areas move into
 * their own modules, and is deleted when the migration is complete.
 */
// The server closes the authorizer; the graph only hands it out.
@SuppressWarnings("CloseableProvides")
@Module
abstract class LegacyModule {

  private LegacyModule() {}

  @Provides
  static GravitinoAuthorizer gravitinoAuthorizer(LegacyBridge legacy) {
    return legacy.gravitinoAuthorizer();
  }
}
