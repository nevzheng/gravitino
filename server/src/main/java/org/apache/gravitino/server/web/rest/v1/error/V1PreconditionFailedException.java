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

/** Identifies a V1 mutation whose required strong representation validator was not satisfied. */
public final class V1PreconditionFailedException extends IllegalStateException {

  /** The public message for a failed V1 representation precondition. */
  public static final String SAFE_DESCRIPTION = "The resource has changed since it was read.";

  /** Creates a V1 representation-precondition failure with a safe public message. */
  public V1PreconditionFailedException() {
    super(SAFE_DESCRIPTION);
  }

  /**
   * Returns the safe public explanation for this failure.
   *
   * @return the safe public explanation.
   */
  public String safeDescription() {
    return SAFE_DESCRIPTION;
  }
}
