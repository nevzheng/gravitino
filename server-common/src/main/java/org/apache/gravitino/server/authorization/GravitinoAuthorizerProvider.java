/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.authorization;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import org.apache.gravitino.authorization.GravitinoAuthorizer;

/**
 * Holds the {@link GravitinoAuthorizer} of the server for the code that reads it statically. When
 * the Gravitino server starts up, it {@link #install installs} the authorizer its component built,
 * and takes it back with {@link #close()} while it shuts down; the component closes it. The
 * GravitinoAuthorizer instance can then be retrieved using the getGravitinoAuthorizer method.
 */
public class GravitinoAuthorizerProvider implements Closeable {

  private static final GravitinoAuthorizerProvider INSTANCE = new GravitinoAuthorizerProvider();

  private volatile GravitinoAuthorizer gravitinoAuthorizer;

  private GravitinoAuthorizerProvider() {}

  /**
   * Returns the provider of the process.
   *
   * @return the provider
   */
  public static GravitinoAuthorizerProvider getInstance() {
    return INSTANCE;
  }

  /**
   * Installs an authorizer that something else built and closes, such as the component of the
   * server.
   *
   * @param authorizer the authorizer
   * @throws IllegalStateException if another authorizer is installed
   */
  public synchronized void install(GravitinoAuthorizer authorizer) {
    Preconditions.checkNotNull(authorizer, "authorizer");
    Preconditions.checkState(
        gravitinoAuthorizer == null || gravitinoAuthorizer == authorizer,
        "Another authorizer is already installed");
    gravitinoAuthorizer = authorizer;
  }

  /**
   * Retrieve GravitinoAuthorizer instance.
   *
   * @return the installed GravitinoAuthorizer, or null when none is installed
   */
  public GravitinoAuthorizer getGravitinoAuthorizer() {
    return gravitinoAuthorizer;
  }

  /** Takes the installed authorizer back without closing it; its owner closes it. */
  @Override
  public synchronized void close() {
    gravitinoAuthorizer = null;
  }
}
