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

package org.apache.gravitino.auxiliary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.utils.IsolatedClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAuxiliaryServiceManager {

  private final AuxiliaryContext context = mock(AuxiliaryContext.class);

  static class DummyConfig extends Config {

    public static DummyConfig of(Map<String, String> m) {
      DummyConfig dummyConfig = new DummyConfig();
      dummyConfig.loadFromMap(m, k -> true);
      return dummyConfig;
    }
  }

  @Test
  public void testGravitinoAuxServiceManagerEmptyServiceName() throws Exception {
    AuxiliaryServiceManager auxServiceManager = new AuxiliaryServiceManager(context);
    auxServiceManager.serviceInit(
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "")));
    auxServiceManager.start();
    auxServiceManager.close();
  }

  @Test
  public void testGravitinoAuxServiceNotSetClassPath() {
    AuxiliaryServiceManager auxServiceManager = new AuxiliaryServiceManager(context);
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            auxServiceManager.serviceInit(
                DummyConfig.of(
                    ImmutableMap.of(
                        AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                            + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                        "mock1"))));
  }

  @Test
  public void testGravitinoAuxServiceManager() throws Exception {
    GravitinoAuxiliaryService auxService = mock(GravitinoAuxiliaryService.class);
    GravitinoAuxiliaryService auxService2 = mock(GravitinoAuxiliaryService.class);

    IsolatedClassLoader isolatedClassLoader =
        new IsolatedClassLoader(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    AuxiliaryServiceManager auxServiceManager = new AuxiliaryServiceManager(context);
    AuxiliaryServiceManager spyAuxManager = spy(auxServiceManager);

    doReturn(isolatedClassLoader).when(spyAuxManager).getIsolatedClassLoader(anyList());
    doReturn(auxService).when(spyAuxManager).loadAuxService("mock1", isolatedClassLoader);
    doReturn(auxService2).when(spyAuxManager).loadAuxService("mock2", isolatedClassLoader);

    spyAuxManager.serviceInit(
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "mock1,mock2",
                "gravitino.mock1." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp",
                "gravitino.mock2." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp")));
    verify(auxService, times(1)).serviceInit(any(), anyBoolean(), same(context));
    verify(auxService2, times(1)).serviceInit(any(), anyBoolean(), same(context));

    spyAuxManager.start();
    verify(auxService, times(1)).serviceStart();
    verify(auxService2, times(1)).serviceStart();

    spyAuxManager.close();
    verify(auxService, times(1)).serviceStop();
    verify(auxService2, times(1)).serviceStop();
  }

  @Test
  public void testServicesAreInitializedWithTheContext() throws Exception {
    GravitinoAuxiliaryService auxService = mock(GravitinoAuxiliaryService.class);
    IsolatedClassLoader isolatedClassLoader =
        new IsolatedClassLoader(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    AuxiliaryServiceManager spyAuxManager = spy(new AuxiliaryServiceManager(context));
    doReturn(isolatedClassLoader).when(spyAuxManager).getIsolatedClassLoader(anyList());
    doReturn(auxService).when(spyAuxManager).loadAuxService("mock1", isolatedClassLoader);

    spyAuxManager.serviceInit(
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "mock1",
                "gravitino.mock1." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp",
                "gravitino.mock1.key",
                "value")));

    verify(auxService)
        .serviceInit(ImmutableMap.of("classpath", "/tmp", "key", "value"), true, context);
    verify(auxService, never()).serviceInit(any(), anyBoolean());
  }

  @Test
  public void testCloseStopsEveryServiceAndLogsFailures() throws Exception {
    GravitinoAuxiliaryService auxService = mock(GravitinoAuxiliaryService.class);
    GravitinoAuxiliaryService auxService2 = mock(GravitinoAuxiliaryService.class);
    doThrow(new IllegalStateException("mock1 cannot stop")).when(auxService).serviceStop();
    doThrow(new IllegalStateException("mock2 cannot stop")).when(auxService2).serviceStop();
    IsolatedClassLoader isolatedClassLoader =
        new IsolatedClassLoader(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    AuxiliaryServiceManager spyAuxManager = spy(new AuxiliaryServiceManager(context));
    doReturn(isolatedClassLoader).when(spyAuxManager).getIsolatedClassLoader(anyList());
    doReturn(auxService).when(spyAuxManager).loadAuxService("mock1", isolatedClassLoader);
    doReturn(auxService2).when(spyAuxManager).loadAuxService("mock2", isolatedClassLoader);
    spyAuxManager.serviceInit(
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "mock1,mock2",
                "gravitino.mock1." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp",
                "gravitino.mock2." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp")));

    // A service that fails to stop does not keep the other from stopping, and close() does not
    // fail the shutdown.
    Assertions.assertDoesNotThrow(spyAuxManager::close);
    verify(auxService).serviceStop();
    verify(auxService2).serviceStop();
  }

  @Test
  public void testRequiresAContext() {
    Assertions.assertThrows(NullPointerException.class, () -> new AuxiliaryServiceManager(null));
  }

  @Test
  public void testIsAuxServiceRegistered() throws Exception {
    GravitinoAuxiliaryService auxService = mock(GravitinoAuxiliaryService.class);
    IsolatedClassLoader isolatedClassLoader =
        new IsolatedClassLoader(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    AuxiliaryServiceManager auxServiceManager = new AuxiliaryServiceManager(context);
    AuxiliaryServiceManager spyAuxManager = spy(auxServiceManager);
    doReturn(isolatedClassLoader).when(spyAuxManager).getIsolatedClassLoader(anyList());
    doReturn(auxService).when(spyAuxManager).loadAuxService("iceberg-rest", isolatedClassLoader);

    Assertions.assertFalse(spyAuxManager.isAuxServiceRegistered("iceberg-rest"));

    spyAuxManager.serviceInit(
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "iceberg-rest",
                "gravitino.iceberg-rest." + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
                "/tmp")));

    Assertions.assertTrue(spyAuxManager.isAuxServiceRegistered("iceberg-rest"));
    Assertions.assertFalse(spyAuxManager.isAuxServiceRegistered("lance-rest"));
  }

  @Test
  void testGetAuxServiceConfig() {
    DummyConfig config =
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "iceberg-rest",
                "gravitino.iceberg-rest.host",
                "irc-host",
                "gravitino.iceberg-rest.httpPort",
                "9001"));

    Map<String, String> resolved =
        AuxiliaryServiceManager.getAuxServiceConfig(config, "iceberg-rest");

    Assertions.assertEquals("irc-host", resolved.get("host"));
    Assertions.assertEquals("9001", resolved.get("httpPort"));
  }

  @Test
  void testGetAuxServiceConfigHonorsDeprecatedPrefix() {
    // gravitino.auxService.<name>.<key> is deprecated but still honored; the effective config a
    // caller reads through this method must match what the service itself receives.
    DummyConfig config =
        DummyConfig.of(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "iceberg-rest",
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX + "iceberg-rest.host",
                "irc-host"));

    Map<String, String> resolved =
        AuxiliaryServiceManager.getAuxServiceConfig(config, "iceberg-rest");

    Assertions.assertEquals("irc-host", resolved.get("host"));
  }

  @Test
  void testAuxiliaryServiceConfigs() {
    Map<String, String> m =
        ImmutableMap.of(
            AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
            "a,b",
            "gravitino.a.test1",
            "test1",
            AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX + "a.test4",
            "test4",
            "gravitino.b.test2",
            "test2",
            "gravitino.aa.test3",
            "test3");
    DummyConfig config = new DummyConfig();
    config.loadFromMap(m, k -> true);
    Map<String, String> serviceConfigs =
        AuxiliaryServiceManager.extractAuxiliaryServiceConfigs(config);
    Assertions.assertEquals(
        ImmutableMap.of(
            AuxiliaryServiceManager.AUX_SERVICE_NAMES,
            "a,b",
            "a.test1",
            "test1",
            "a.test4",
            "test4",
            "b.test2",
            "test2"),
        serviceConfigs);

    // Test gravitino.a.test1 overwrite gravitino.auxService.a.test1
    m =
        ImmutableMap.of(
            AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
            "a",
            "gravitino.a.test1",
            "test1",
            AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX + "a.test1",
            "test4");
    DummyConfig config2 = new DummyConfig();
    config2.loadFromMap(m, k -> true);
    serviceConfigs = AuxiliaryServiceManager.extractAuxiliaryServiceConfigs(config2);
    Assertions.assertEquals(
        ImmutableMap.of(AuxiliaryServiceManager.AUX_SERVICE_NAMES, "a", "a.test1", "test1"),
        serviceConfigs);
  }
}
