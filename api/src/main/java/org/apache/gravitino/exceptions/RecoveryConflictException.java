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
package org.apache.gravitino.exceptions;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.Objects;
import org.apache.gravitino.RecoveryConflictReason;

/** Exception thrown when recovery conflicts with current metadata or connector state. */
public class RecoveryConflictException extends GravitinoRuntimeException {

  /** The stable machine-readable reason for the conflict. */
  private final RecoveryConflictReason reason;

  /**
   * Constructs a recovery-conflict exception.
   *
   * @param reason The stable recovery-conflict reason.
   * @param message The detail message.
   * @param args The arguments to the message.
   */
  @FormatMethod
  public RecoveryConflictException(
      RecoveryConflictReason reason, @FormatString String message, Object... args) {
    super(message, args);
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  /**
   * Constructs a recovery-conflict exception with a cause.
   *
   * @param cause The cause of the exception.
   * @param reason The stable recovery-conflict reason.
   * @param message The detail message.
   * @param args The arguments to the message.
   */
  @FormatMethod
  public RecoveryConflictException(
      Throwable cause,
      RecoveryConflictReason reason,
      @FormatString String message,
      Object... args) {
    super(cause, message, args);
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  /**
   * Returns the stable reason for this recovery conflict.
   *
   * @return The recovery-conflict reason.
   */
  public RecoveryConflictReason getReason() {
    return reason;
  }
}
