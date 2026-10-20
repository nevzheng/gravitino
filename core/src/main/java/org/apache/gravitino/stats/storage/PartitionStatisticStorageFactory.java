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

/**
 * Factory interface for creating instances of {@link
 * org.apache.gravitino.stats.storage.PartitionStatisticStorage}.
 */
public interface PartitionStatisticStorageFactory {

  /**
   * Creates an instance of {@link org.apache.gravitino.stats.storage.PartitionStatisticStorage}.
   *
   * @param properties additional properties for the storage configuration
   * @return an instance of {@link org.apache.gravitino.stats.storage.PartitionStatisticStorage}
   */
  PartitionStatisticStorage create(Map<String, String> properties);

  /**
   * Creates an instance of {@link org.apache.gravitino.stats.storage.PartitionStatisticStorage}
   * that reads table metadata from the given entity store.
   *
   * <p>The default ignores the store and calls {@link #create(Map)}, for factories that do not read
   * table metadata.
   *
   * @param properties additional properties for the storage configuration
   * @param entityStore the entity store that holds the tables whose statistics are stored
   * @return an instance of {@link org.apache.gravitino.stats.storage.PartitionStatisticStorage}
   */
  default PartitionStatisticStorage create(
      Map<String, String> properties, EntityStore entityStore) {
    return create(properties);
  }
}
