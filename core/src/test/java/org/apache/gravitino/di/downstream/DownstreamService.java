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

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.gravitino.lifecycle.ManagedService;
import org.apache.gravitino.lock.LockManager;

/**
 * A service only the downstream component has. It depends on an upstream service, so it must start
 * after it and close before it.
 */
@Singleton
class DownstreamService implements ManagedService {

  final List<String> calls = new ArrayList<>();
  final LockManager lockManager;

  @Inject
  DownstreamService(LockManager lockManager) {
    this.lockManager = lockManager;
  }

  @Override
  public String name() {
    return "downstream service";
  }

  @Override
  public void start() {
    calls.add("start");
  }

  @Override
  public void close() {
    calls.add("close");
  }
}
