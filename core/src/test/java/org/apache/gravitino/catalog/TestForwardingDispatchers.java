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
package org.apache.gravitino.catalog;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TestForwardingDispatchers {

  /** A forwarding class, the interface it forwards, and how to build one over a delegate. */
  private static final class Case<T> {
    private final Class<T> type;
    private final Class<?> forwardingClass;
    private final Function<T, T> forwarding;

    private Case(Class<T> type, Class<?> forwardingClass, Function<T, T> forwarding) {
      this.type = type;
      this.forwardingClass = forwardingClass;
      this.forwarding = forwarding;
    }

    @Override
    public String toString() {
      return forwardingClass.getSimpleName();
    }
  }

  static Stream<Case<?>> cases() {
    return Stream.of(
        new Case<>(
            SchemaDispatcher.class,
            ForwardingSchemaDispatcher.class,
            d -> new ForwardingSchemaDispatcher(d) {}),
        new Case<>(
            TableDispatcher.class,
            ForwardingTableDispatcher.class,
            d -> new ForwardingTableDispatcher(d) {}),
        new Case<>(
            FilesetDispatcher.class,
            ForwardingFilesetDispatcher.class,
            d -> new ForwardingFilesetDispatcher(d) {}),
        new Case<>(
            TopicDispatcher.class,
            ForwardingTopicDispatcher.class,
            d -> new ForwardingTopicDispatcher(d) {}),
        new Case<>(
            ModelDispatcher.class,
            ForwardingModelDispatcher.class,
            d -> new ForwardingModelDispatcher(d) {}),
        new Case<>(
            ViewDispatcher.class,
            ForwardingViewDispatcher.class,
            d -> new ForwardingViewDispatcher(d) {}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void testEveryMethodIsOverridden(Case<?> c) {
    for (Method method : interfaceMethods(c.type)) {
      Method override =
          Assertions.assertDoesNotThrow(
              () ->
                  c.forwardingClass.getDeclaredMethod(method.getName(), method.getParameterTypes()),
              () -> c.forwardingClass.getSimpleName() + " does not override " + method);
      Assertions.assertTrue(Modifier.isPublic(override.getModifiers()), override::toString);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void testEveryMethodForwardsToTheSameMethodOfTheDelegate(Case<?> c) throws Exception {
    assertForwards(c);
  }

  @Test
  void testTheDelegateIsRequired() {
    Assertions.assertThrows(
        NullPointerException.class, () -> new ForwardingTableDispatcher(null) {});
  }

  @Test
  void testTheDelegateIsExposedToSubclasses() {
    TopicDispatcher delegate = mock(TopicDispatcher.class);
    Assertions.assertSame(delegate, new ForwardingTopicDispatcher(delegate) {}.delegate());
  }

  private static <T> void assertForwards(Case<T> c) throws Exception {
    for (Method method : interfaceMethods(c.type)) {
      T delegate = mock(c.type);
      T forwarding = c.forwarding.apply(delegate);
      Object[] args =
          Arrays.stream(method.getParameterTypes())
              .map(TestForwardingDispatchers::defaultValue)
              .toArray();

      invoke(method, forwarding, args);

      invoke(method, verify(delegate), args);
      verifyNoMoreInteractions(delegate);
    }
  }

  private static Method[] interfaceMethods(Class<?> type) {
    return Arrays.stream(type.getMethods())
        .filter(m -> !Modifier.isStatic(m.getModifiers()))
        .toArray(Method[]::new);
  }

  private static void invoke(Method method, Object target, Object[] args) throws Exception {
    try {
      method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw new AssertionError(method + " threw", e.getCause());
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    Assertions.assertFalse(type.isPrimitive(), () -> "no default value for " + type);
    return null;
  }
}
