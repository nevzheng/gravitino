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

package org.apache.gravitino.iceberg.service.deletion.purge;

import java.util.Objects;

/** Classified purge-planning or target-deletion failure. */
public class IcebergPurgeException extends Exception {

  private final boolean retryable;
  private final String reasonCode;

  /**
   * Creates a classified failure.
   *
   * <p>The worker persists only a fixed description derived from a validated reason code. This
   * exception message is diagnostic input and must never be written directly to durable state,
   * audit output, or an API response.
   *
   * @param retryable whether automatic retry is safe
   * @param reasonCode bounded machine-readable category
   * @param message diagnostic failure description
   */
  public IcebergPurgeException(boolean retryable, String reasonCode, String message) {
    super(message);
    this.retryable = retryable;
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
  }

  /** Returns whether automatic retry is safe. */
  public boolean retryable() {
    return retryable;
  }

  /** Returns the bounded machine-readable failure category. */
  public String reasonCode() {
    return reasonCode;
  }
}
