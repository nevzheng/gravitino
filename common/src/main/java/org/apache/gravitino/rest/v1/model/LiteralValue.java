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
package org.apache.gravitino.rest.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import javax.annotation.Nullable;

/** A shorthand raw JSON literal from the Iceberg expression grammar. */
public final class LiteralValue implements ValueExpression {
  private final Object value;

  /**
   * @param value an Iceberg JSON single value, including a struct, list, map, or null.
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public LiteralValue(@Nullable Object value) {
    this.value = ModelSupport.immutableJsonValue(value, "value");
  }

  /**
   * @return the immutable Iceberg JSON single value, including null.
   */
  @Nullable
  @JsonValue
  public Object getValue() {
    return value;
  }
}
