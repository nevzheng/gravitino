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

/** Identifies an explicitly validated V1 client-input violation safe to report publicly. */
public final class V1ClientInputException extends IllegalArgumentException {

  private final String field;
  private final String safeDescription;

  /**
   * Creates an explicit public V1 client-input violation.
   *
   * @param field public request field that violated the contract.
   * @param safeDescription safe explanation of the violated rule.
   */
  public V1ClientInputException(String field, String safeDescription) {
    super(requireNonEmpty(safeDescription, "safeDescription"));
    this.field = requireNonEmpty(field, "field");
    this.safeDescription = safeDescription;
  }

  /**
   * Returns the public request field that violated the contract.
   *
   * @return the request field.
   */
  public String field() {
    return field;
  }

  /**
   * Returns the safe public explanation of the violated rule.
   *
   * @return the safe description.
   */
  public String safeDescription() {
    return safeDescription;
  }

  private static String requireNonEmpty(String value, String name) {
    Objects.requireNonNull(value, name + " cannot be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " cannot be empty");
    }
    return value;
  }
}
