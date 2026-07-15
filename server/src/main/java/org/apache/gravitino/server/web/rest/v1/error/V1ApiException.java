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
package org.apache.gravitino.server.web.rest.v1.error;

import java.util.Objects;

/** Wraps an internal V1 route failure until the public V1 exception mapper translates it. */
public final class V1ApiException extends RuntimeException {

  private final V1ErrorContext errorContext;

  /**
   * Creates a V1 route exception wrapper.
   *
   * @param cause the internal failure.
   * @param errorContext public request context safe to expose in an error.
   */
  public V1ApiException(Throwable cause, V1ErrorContext errorContext) {
    super(Objects.requireNonNull(cause, "cause"));
    this.errorContext = Objects.requireNonNull(errorContext, "errorContext");
  }

  /**
   * Returns public request context used to translate the failure.
   *
   * @return the error context.
   */
  public V1ErrorContext errorContext() {
    return errorContext;
  }
}
