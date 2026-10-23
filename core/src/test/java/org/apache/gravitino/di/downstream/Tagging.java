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
package org.apache.gravitino.di.downstream;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.gravitino.di.DispatcherDecorator;

/** Decorators that wrap a dispatcher in a labelled proxy which forwards every call. */
final class Tagging {

  private Tagging() {}

  /**
   * Returns a decorator that wraps a dispatcher in a proxy with a label.
   *
   * @param type the dispatcher type
   * @param label the label of the proxy
   * @param <T> the dispatcher type
   * @return the decorator
   */
  static <T> DispatcherDecorator<T> decorator(Class<T> type, String label) {
    return dispatcher ->
        type.cast(
            Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, new Tag(label, dispatcher)));
  }

  /**
   * Returns the tag of a dispatcher.
   *
   * @param dispatcher a dispatcher
   * @return its tag, or null if it is not a tagging proxy
   */
  static Tag tag(Object dispatcher) {
    if (!Proxy.isProxyClass(dispatcher.getClass())) {
      return null;
    }
    InvocationHandler handler = Proxy.getInvocationHandler(dispatcher);
    return handler instanceof Tag ? (Tag) handler : null;
  }

  /** The handler of a tagging proxy. */
  static final class Tag implements InvocationHandler {
    final String label;
    final Object delegate;

    Tag(String label, Object delegate) {
      this.label = label;
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(delegate, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
