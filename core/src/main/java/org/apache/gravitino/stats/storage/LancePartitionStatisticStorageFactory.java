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
package org.apache.gravitino.stats.storage;

import java.util.Map;
import org.apache.gravitino.EntityStore;

public class LancePartitionStatisticStorageFactory implements PartitionStatisticStorageFactory {
  /**
   * Not supported: the storage reads table metadata from an entity store. Call {@link #create(Map,
   * EntityStore)}.
   *
   * @param properties the storage configuration
   * @return never
   * @throws UnsupportedOperationException always
   */
  @Override
  public PartitionStatisticStorage create(Map<String, String> properties) {
    throw new UnsupportedOperationException(
        "LancePartitionStatisticStorage needs an entity store; call create(Map, EntityStore)");
  }

  @Override
  public PartitionStatisticStorage create(Map<String, String> properties, EntityStore entityStore) {
    return new LancePartitionStatisticStorage(properties, entityStore);
  }
}
