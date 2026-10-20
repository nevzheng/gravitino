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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Field;

/** Walks a dispatcher chain through the field each decorator keeps its delegate in. */
final class DispatcherChains {

  private DispatcherChains() {}

  /**
   * Asserts that {@code dispatcher} is a chain of the given classes, outermost first.
   *
   * @param dispatcher the outermost dispatcher
   * @param fieldName the name of the field each decorator keeps its delegate in
   * @param classes the expected class of each layer, outermost first
   * @return the innermost dispatcher
   */
  static Object assertChain(Object dispatcher, String fieldName, Class<?>... classes)
      throws ReflectiveOperationException {
    Object current = dispatcher;
    for (int i = 0; i < classes.length; i++) {
      assertInstanceOf(classes[i], current);
      if (i < classes.length - 1) {
        current = delegate(current, fieldName);
      }
    }
    return current;
  }

  /**
   * Asserts that {@code dispatcher} is a chain of the given classes whose decorators keep their
   * delegate in a field named {@code dispatcher}.
   *
   * @param dispatcher the outermost dispatcher
   * @param classes the expected class of each layer, outermost first
   * @return the innermost dispatcher
   */
  static Object assertChain(Object dispatcher, Class<?>... classes)
      throws ReflectiveOperationException {
    return assertChain(dispatcher, "dispatcher", classes);
  }

  private static Object delegate(Object decorator, String fieldName)
      throws ReflectiveOperationException {
    for (Class<?> type = decorator.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(decorator);
      } catch (NoSuchFieldException e) {
        // Look in the superclass.
      }
    }
    throw new NoSuchFieldException(decorator.getClass().getName() + "." + fieldName);
  }
}
