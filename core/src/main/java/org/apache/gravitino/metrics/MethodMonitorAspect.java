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

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import javax.annotation.Nullable;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Times the methods annotated with {@link Monitored} and counts their successes and failures in the
 * metrics source each one names.
 *
 * <p>AspectJ instantiates this aspect itself, so it cannot take the metrics system by constructor.
 * The component hands its metrics system to {@link #setMetricsSystem(MetricsSystem)} when it builds
 * it, and every advised call reads the one set last. Until one is set, the methods run unmonitored.
 */
@Aspect
public class MethodMonitorAspect {

  private static final Logger LOG = LoggerFactory.getLogger(MethodMonitorAspect.class);

  @Nullable private static volatile MetricsSystem metricsSystem;

  /** Creates the aspect; AspectJ calls this. */
  public MethodMonitorAspect() {}

  /**
   * Sets the metrics system whose sources the advised methods record into.
   *
   * @param metricsSystem the metrics system, or null to stop monitoring
   */
  public static void setMetricsSystem(@Nullable MetricsSystem metricsSystem) {
    MethodMonitorAspect.metricsSystem = metricsSystem;
    if (metricsSystem != null) {
      LOG.info("MethodMonitorAspect initialized.");
    }
  }

  /**
   * Returns the metrics system the advised methods record into.
   *
   * @return the metrics system, or null when none is set
   */
  @Nullable
  public static MetricsSystem metricsSystem() {
    return metricsSystem;
  }

  @Pointcut("execution(@org.apache.gravitino.metrics.Monitored * *(..))")
  public void monitoredMethods() {}

  @Around("monitoredMethods() && @annotation(monitored)")
  public Object monitorMethod(ProceedingJoinPoint pjp, Monitored monitored) throws Throwable {
    MetricsSystem metricsSystem = MethodMonitorAspect.metricsSystem;
    if (metricsSystem == null) {
      return pjp.proceed();
    }

    MetricsSource metricsSource = metricsSystem.getMetricsSource(monitored.metricsSource());
    if (metricsSource == null) {
      LOG.warn(
          "MetricsSource {} is not registered in MetricsSystem, skip monitoring for method {}",
          monitored.metricsSource(),
          pjp.getSignature().toShortString());
      return pjp.proceed();
    }

    String baseMetricName = monitored.baseMetricName();
    Timer timer = metricsSource.getTimer(MetricRegistry.name(baseMetricName, "total"));
    Meter successMeter = metricsSource.getMeter(MetricRegistry.name(baseMetricName, "success"));
    Meter failureMeter = metricsSource.getMeter(MetricRegistry.name(baseMetricName, "failure"));

    try (Timer.Context ignore = timer.time()) {
      Object result = pjp.proceed();
      successMeter.mark();
      return result;
    } catch (Throwable t) {
      failureMeter.mark();
      throw t;
    }
  }
}
