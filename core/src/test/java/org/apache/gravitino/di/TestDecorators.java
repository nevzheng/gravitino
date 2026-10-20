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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class TestDecorators {

  @Test
  public void testNoDecoratorsReturnsTheChainItself() {
    Object chain = new Object();
    assertSame(chain, Decorators.apply("chain", chain, ImmutableSet.of()));
  }

  @Test
  public void testDecoratorsApplyInAscendingOrderWhateverTheSetOrder() {
    List<Integer> applied = new ArrayList<>();
    Set<DispatcherDecorator<String>> decorators =
        ImmutableSet.of(recording(30, applied), recording(-5, applied), recording(10, applied));

    String decorated = Decorators.apply("chain", "base", decorators);

    assertEquals(List.of(-5, 10, 30), applied);
    // The lowest order wraps the chain first; the highest is outermost.
    assertEquals("30(10(-5(base)))", decorated);
  }

  @Test
  public void testADecoratorCanReplaceTheChain() {
    Set<DispatcherDecorator<String>> decorators =
        ImmutableSet.of(
            DispatcherDecorator.of(1, ignored -> "replacement"),
            DispatcherDecorator.of(2, chain -> "outer(" + chain + ")"));

    assertEquals("outer(replacement)", Decorators.apply("chain", "base", decorators));
  }

  @Test
  public void testTwoDecoratorsWithTheSameOrderFailBeforeAnyIsApplied() {
    List<Integer> applied = new ArrayList<>();
    DispatcherDecorator<String> first = recording(7, applied);
    DispatcherDecorator<String> second = recording(7, applied);
    Set<DispatcherDecorator<String>> decorators =
        ImmutableSet.of(recording(1, applied), first, second);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> Decorators.apply("@Public TableDispatcher", "base", decorators));

    assertTrue(e.getMessage().contains("@Public TableDispatcher"), e.getMessage());
    assertTrue(e.getMessage().contains("7"), e.getMessage());
    assertTrue(applied.isEmpty());
  }

  @Test
  public void testADecoratorMayNotReturnNull() {
    Set<DispatcherDecorator<String>> decorators =
        ImmutableSet.of(DispatcherDecorator.of(3, ignored -> null));

    assertThrows(IllegalStateException.class, () -> Decorators.apply("chain", "base", decorators));
  }

  private static DispatcherDecorator<String> recording(int order, List<Integer> applied) {
    return DispatcherDecorator.of(
        order,
        chain -> {
          applied.add(order);
          return order + "(" + chain + ")";
        });
  }
}
