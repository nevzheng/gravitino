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

import com.google.common.collect.ImmutableMap;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestChain {

  @Test
  public void testWithoutDecoratorsTheChainIsItsLayers() {
    String chain =
        Chain.of("chain", "op", ImmutableMap.<Layer, DispatcherDecorator<String>>of())
            .hook(d -> "hook(" + d + ")")
            .normalize(d -> "normalize(" + d + ")")
            .event(d -> "event(" + d + ")")
            .build();

    assertEquals("event(normalize(hook(op)))", chain);
  }

  @Test
  public void testEachDecoratorSitsOverItsLayer() {
    Map<Layer, DispatcherDecorator<String>> decorators = new EnumMap<>(Layer.class);
    for (Layer layer : Layer.values()) {
      decorators.put(layer, d -> layer + "(" + d + ")");
    }

    String chain =
        Chain.of("chain", "op", decorators)
            .hook(d -> "hook(" + d + ")")
            .normalize(d -> "normalize(" + d + ")")
            .event(d -> "event(" + d + ")")
            .build();

    assertEquals("EXTENSION(EVENT(event(NORMALIZE(normalize(HOOK(hook(OPERATION(op))))))))", chain);
  }

  @Test
  public void testADecoratorAtAMissingLayerSitsAtItsPosition() {
    Map<Layer, DispatcherDecorator<String>> decorators =
        ImmutableMap.of(Layer.HOOK, d -> "HOOK(" + d + ")", Layer.EVENT, d -> "EVENT(" + d + ")");

    // An internal chain: normalize over operation, with no hook or event layer.
    String chain =
        Chain.of("chain", "op", decorators).normalize(d -> "normalize(" + d + ")").build();

    assertEquals("EVENT(normalize(HOOK(op)))", chain);
  }

  @Test
  public void testABareChainTakesEveryDecoratorInOrder() {
    Map<Layer, DispatcherDecorator<String>> decorators =
        ImmutableMap.of(
            Layer.EXTENSION,
            d -> "EXTENSION(" + d + ")",
            Layer.OPERATION,
            d -> "OPERATION(" + d + ")");

    assertEquals("EXTENSION(OPERATION(manager))", Chain.of("chain", "manager", decorators).build());
  }

  @Test
  public void testLayersMustBeAddedInnermostFirst() {
    Chain<String> chain =
        Chain.of("@Public Thing", "op", ImmutableMap.<Layer, DispatcherDecorator<String>>of())
            .normalize(d -> d);

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> chain.hook(d -> d));
    assertTrue(e.getMessage().contains("@Public Thing"), e.getMessage());
  }

  @Test
  public void testNullsAreRejectedWithTheChainName() {
    Map<Layer, DispatcherDecorator<String>> returnsNull = ImmutableMap.of(Layer.HOOK, d -> null);

    NullPointerException e =
        assertThrows(
            NullPointerException.class,
            () -> Chain.of("@Internal Thing", "op", returnsNull).build());
    assertTrue(e.getMessage().contains("@Internal Thing"), e.getMessage());
    assertTrue(e.getMessage().contains("HOOK"), e.getMessage());

    assertThrows(
        NullPointerException.class,
        () ->
            Chain.of("chain", "op", ImmutableMap.<Layer, DispatcherDecorator<String>>of())
                .hook(d -> null));
    assertThrows(
        NullPointerException.class,
        () -> Chain.of("chain", null, ImmutableMap.<Layer, DispatcherDecorator<String>>of()));
  }

  @Test
  public void testTheBottomIsReturnedUnchangedWithoutLayersOrDecorators() {
    Object bottom = new Object();
    assertSame(
        bottom,
        Chain.of("chain", bottom, ImmutableMap.<Layer, DispatcherDecorator<Object>>of()).build());
  }
}
