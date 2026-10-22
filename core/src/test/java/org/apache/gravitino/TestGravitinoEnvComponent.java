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
package org.apache.gravitino;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.gravitino.di.GravitinoComponent;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link GravitinoEnv} takes the objects the component owns from its component. The
 * full and metadata profiles must run on the singleton, so {@link
 * TestGravitinoEnvMetadataComponents} covers them.
 */
public class TestGravitinoEnvComponent {

  @Test
  public void testUninitializedEnvironmentHasNoComponent() {
    assertNull(new TestGravitinoEnv().component());
  }

  @Test
  public void testBaseProfileTakesOwnedObjectsFromTheComponent() {
    TestGravitinoEnv env = new TestGravitinoEnv();
    Config config = new Config(false) {};
    try {
      env.initializeBaseComponents(config);

      GravitinoComponent component = env.component();
      assertNotNull(component);
      assertSame(config, component.config());
      assertSame(component.metricsSystem(), env.metricsSystem());
      // The base profile never built a lock manager or an id generator, and still does not.
      assertNull(env.lockManager());
      assertNull(env.idGenerator());
      assertSame(env.secretManager(), component.secretManager());
      assertSame(env.kmsClientRegistry(), component.kmsClientRegistry());
      assertSame(env.eventListenerManager(), component.eventListenerManager());
      assertSame(env.eventBus(), component.eventBus());
    } finally {
      env.shutdown();
    }
  }

  @Test
  public void testEachInitializationBuildsANewComponent() {
    TestGravitinoEnv env = new TestGravitinoEnv();
    try {
      env.initializeBaseComponents(new Config(false) {});
      GravitinoComponent first = env.component();
      env.shutdown();

      env.initializeBaseComponents(new Config(false) {});

      assertNotSame(first, env.component());
      assertNotSame(first.metricsSystem(), env.metricsSystem());
      assertSame(env.component().metricsSystem(), env.metricsSystem());
    } finally {
      env.shutdown();
    }
  }

  private static final class TestGravitinoEnv extends GravitinoEnv {}
}
