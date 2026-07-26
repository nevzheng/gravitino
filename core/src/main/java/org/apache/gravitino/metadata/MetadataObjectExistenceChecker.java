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
package org.apache.gravitino.metadata;

import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;

/** Resolves a metadata object through its owning service and verifies that it exists. */
@FunctionalInterface
public interface MetadataObjectExistenceChecker {

  /**
   * Verifies that a metadata object exists.
   *
   * <p>The owning service may load an object from its underlying source as part of this check. The
   * check must therefore run outside the metadata object's tree lock.
   *
   * @param metalake the metalake containing the object
   * @param metadataObject the metadata object to verify
   * @throws NoSuchMetadataObjectException if the object does not exist
   */
  void check(String metalake, MetadataObject metadataObject);
}
