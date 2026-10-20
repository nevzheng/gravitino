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

import static org.apache.gravitino.di.DispatcherChains.assertChain;
import static org.apache.gravitino.di.DispatcherChains.field;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.hook.JobHookDispatcher;
import org.apache.gravitino.job.JobManager;
import org.apache.gravitino.job.JobTemplateValidationDispatcher;
import org.apache.gravitino.listener.JobEventDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestJobModule {

  @TempDir Path stagingDir;

  private FakeLeaves leaves;
  private ModuleTestComponent component;

  @BeforeEach
  public void setUp() {
    Config config = TestGravitinoComponent.testConfig();
    config.set(Configs.JOB_STAGING_DIR, stagingDir.toString());
    leaves = new FakeLeaves();
    component = ModuleTestComponent.create(config, leaves);
  }

  @AfterEach
  public void tearDown() throws Exception {
    component.jobOperationDispatcher().close();
  }

  @Test
  public void testJobChains() throws Exception {
    Object manager =
        assertChain(
            component.internalJobOperationDispatcher(),
            JobTemplateValidationDispatcher.class,
            JobManager.class);
    assertChain(
        component.jobOperationDispatcher(),
        "jobOperationDispatcher",
        JobEventDispatcher.class,
        JobHookDispatcher.class,
        JobTemplateValidationDispatcher.class);
    Object hook = field(component.jobOperationDispatcher(), "jobOperationDispatcher");
    assertSame(component.internalJobOperationDispatcher(), field(hook, "jobOperationDispatcher"));
    assertSame(leaves.entityStore, field(manager, "entityStore"));
    assertSame(component.lockManager(), field(manager, "lockManager"));
  }

  @Test
  public void testBuiltInTemplateListenerUsesTheJobManager() throws Exception {
    Object manager = field(component.internalJobOperationDispatcher(), "dispatcher");
    assertSame(
        component.builtInJobTemplateEventListener(), component.builtInJobTemplateEventListener());
    assertSame(manager, field(component.builtInJobTemplateEventListener(), "jobManager"));
  }
}
