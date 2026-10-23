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
package org.apache.gravitino.metrics;

import com.codahale.metrics.MetricRegistry;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestMethodMonitorAspect {

  private static final String SOURCE = "test-source";

  private MetricsSystem previous;

  @BeforeEach
  public void remember() {
    previous = MethodMonitorAspect.metricsSystem();
  }

  @AfterEach
  public void restore() {
    MethodMonitorAspect.setMetricsSystem(previous);
  }

  @Test
  public void testRunsUnmonitoredWithoutAMetricsSystem() throws Throwable {
    MethodMonitorAspect.setMetricsSystem(null);
    ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
    Mockito.when(joinPoint.proceed()).thenReturn("result");

    Assertions.assertEquals(
        "result", new MethodMonitorAspect().monitorMethod(joinPoint, monitored("op")));
  }

  @Test
  public void testRecordsIntoTheMetricsSystemSetAfterTheAspectWasCreated() throws Throwable {
    MethodMonitorAspect aspect = new MethodMonitorAspect();
    MetricsSource source = new MetricsSource(SOURCE) {};
    MetricsSystem metricsSystem = Mockito.mock(MetricsSystem.class);
    Mockito.when(metricsSystem.getMetricsSource(SOURCE)).thenReturn(source);
    MethodMonitorAspect.setMetricsSystem(metricsSystem);
    ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
    Mockito.when(joinPoint.getSignature()).thenReturn(Mockito.mock(Signature.class));
    Mockito.when(joinPoint.proceed()).thenReturn("result");

    Assertions.assertEquals("result", aspect.monitorMethod(joinPoint, monitored("op")));

    Assertions.assertEquals(1, source.getMeter(MetricRegistry.name("op", "success")).getCount());
    Assertions.assertEquals(1, source.getTimer(MetricRegistry.name("op", "total")).getCount());
  }

  private static Monitored monitored(String baseMetricName) {
    Monitored monitored = Mockito.mock(Monitored.class);
    Mockito.when(monitored.metricsSource()).thenReturn(SOURCE);
    Mockito.when(monitored.baseMetricName()).thenReturn(baseMetricName);
    return monitored;
  }
}
